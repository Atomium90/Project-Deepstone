package roguelite.db

import doobie.util.transactor.Transactor
import doobie.Fragment
import doobie.implicits.*
import cats.implicits.*
import cats.effect.IO
import roguelite.engine.Difficulty
import roguelite.game.{ AchievementStats, CardRecord, MetaProgression, RunOutcome, StoryProgress, VowRecord, VowState }
import cats.effect.kernel.Resource
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import doobie.hikari.HikariTransactor

/** SQLite-backed persistence for meta-progression data.
  *
  * ## What is persisted
  * Only data that must survive individual runs lives here:
  *   - Stone Shard balance (`metaCurrency`)
  *   - Purchased hub upgrades
  *
  * Run state (dungeon progress, inventory) is in-memory only for V1.
  *
  * ## Transaction safety
  * [[purchaseUpgrade]] deducts currency and records the unlock atomically.
  * All other operations are single-statement and implicitly atomic in SQLite.
  */
class Database private (xa: Transactor[IO]):

  /** Create the schema and seed required rows. Safe to call on every startup (idempotent). */
  def initialize(): IO[Unit] =
    Database.schema.traverse_(statement => Fragment.const(statement).update.run).transact(xa)

  /** Load the full meta-progression state from the database. */
  def loadMeta(): IO[MetaProgression] =
    val getCurrency = sql"SELECT currency FROM meta where id = 1".query[Int].unique
    val getUpgrades = sql"SELECT upgrade_id FROM upgrade_unlock".query[String].to[Set]
    (for
      currency <- getCurrency
      upgrades <- getUpgrades
    yield MetaProgression(currency, upgrades)).transact(xa)

  /** Persist the current Stone Shard balance.
    *
    * Called when the player enters GameOverState so the balance is safe
    * even if the browser closes before the player clicks "Return to Hub".
    */
  def saveCurrency(amount: Int): IO[Unit] =
    sql"UPDATE meta SET currency = $amount WHERE id = 1".update.run.transact(xa).void

  /** Atomically deduct currency and record an upgrade as purchased.
    *
    * The two writes run in a single SQLite transaction: either both succeed or
    * neither does, preventing a state where currency is deducted but the unlock
    * is not recorded (or vice versa).
    *
    * The caller is responsible for validating that the purchase is legal
    * (sufficient funds, not already unlocked) before calling this method.
    *
    * @param upgradeId  The upgrade being purchased.
    * @param newCurrency  The currency balance after deduction.
    */
  def purchaseUpgrade(upgradeId: String, newCurrency: Int): IO[Unit] =
    val deduct = sql"UPDATE meta SET currency = $newCurrency WHERE id = 1".update.run
    val unlock =
      sql"INSERT OR IGNORE INTO upgrade_unlock (upgrade_id) VALUES ($upgradeId)".update.run
    (deduct *> unlock).transact(xa).void

  /** Load the lifetime achievement counters (runs completed/won, win streak, total Shards spent,
    * potions used, wins per difficulty) plus the two "which ids have I ever done this with" sets
    * (potion types used, perks won a run with) - all five backing tables are read in one
    * transaction. `consumable_stats` / `potion_type_used` / `perk_won_with` / `difficulty_win` are
    * separate tables from `achievement_stats` because `CREATE TABLE IF NOT EXISTS` cannot add
    * columns to a table that already exists in a previously-shipped database (same reason
    * `achievement_stats` itself was split out of `meta`). A `difficulty_win` row naming a tier this
    * version does not know is ignored.
    */
  def loadAchievementStats(): IO[AchievementStats] =
    val getCounters = sql"""SELECT runs_completed, runs_won, current_win_streak, total_shards_spent
          FROM achievement_stats WHERE id = 1""".query[(Int, Int, Int, Int)].unique
    val getConsumablesUsed =
      sql"SELECT consumables_used FROM consumable_stats WHERE id = 1".query[Int].unique
    val getPotionTypesUsed = sql"SELECT type_id FROM potion_type_used".query[String].to[Set]
    val getPerksWonWith    = sql"SELECT perk_id FROM perk_won_with".query[String].to[Set]
    val getDifficultyWins  = sql"SELECT difficulty, wins FROM difficulty_win".query[(String, Int)].to[List]
    (for
      counters         <- getCounters
      consumablesUsed  <- getConsumablesUsed
      potionTypesUsed  <- getPotionTypesUsed
      perksWonWith     <- getPerksWonWith
      difficultyWins   <- getDifficultyWins
    yield
      val (runsCompleted, runsWon, currentWinStreak, totalShardsSpent) = counters
      AchievementStats(runsCompleted,
                       runsWon,
                       currentWinStreak,
                       totalShardsSpent,
                       consumablesUsed,
                       potionTypesUsed,
                       perksWonWith,
                       difficultyWins.flatMap((name, wins) => Database.parseDifficulty(name).map(_ -> wins)).toMap
      )
    ).transact(xa)

  /** Load the set of permanently-unlocked achievement ids. */
  def loadUnlockedAchievements(): IO[Set[String]] =
    sql"SELECT achievement_id FROM achievement_unlock".query[String].to[Set].transact(xa)

  /** Record an achievement as unlocked. Idempotent - safe to call again for an id that is already
    * unlocked (INSERT OR IGNORE), same pattern as the unlock half of [[purchaseUpgrade]].
    */
  def unlockAchievement(achievementId: String): IO[Unit] =
    sql"INSERT OR IGNORE INTO achievement_unlock (achievement_id) VALUES ($achievementId)".update.run
      .transact(xa)
      .void

  /** Overwrite the lifetime achievement counters with their current values - the backing tables
    * (`achievement_stats`, `consumable_stats`, `difficulty_win`) in one transaction. Does not touch
    * `potionTypesUsed`/`perksWonWith`: those are append-only sets, recorded one id at a time via
    * [[recordPotionTypeUsed]]/[[recordPerkWonWith]], never overwritten wholesale.
    */
  def saveAchievementStats(stats: AchievementStats): IO[Unit] =
    val updateCounters = sql"""UPDATE achievement_stats
          SET runs_completed = ${stats.runsCompleted},
              runs_won = ${stats.runsWon},
              current_win_streak = ${stats.currentWinStreak},
              total_shards_spent = ${stats.totalShardsSpent}
          WHERE id = 1""".update.run
    val updateConsumablesUsed =
      sql"UPDATE consumable_stats SET consumables_used = ${stats.consumablesUsed} WHERE id = 1".update.run
    val updateDifficultyWins = stats.winsByDifficulty.toList.traverse_ {
      (difficulty, wins) =>
        val name = Database.difficultyName(difficulty)
        sql"""INSERT INTO difficulty_win (difficulty, wins) VALUES ($name, $wins)
              ON CONFLICT(difficulty) DO UPDATE SET wins = excluded.wins""".update.run
    }
    (updateCounters *> updateConsumablesUsed *> updateDifficultyWins).transact(xa).void

  /** Record a potion typeId as having been used at least once. Idempotent, same
    * INSERT-OR-IGNORE pattern as [[unlockAchievement]].
    */
  def recordPotionTypeUsed(typeId: String): IO[Unit] =
    sql"INSERT OR IGNORE INTO potion_type_used (type_id) VALUES ($typeId)".update.run
      .transact(xa)
      .void

  /** Record a perk id as having been active in at least one won run. Idempotent, same
    * INSERT-OR-IGNORE pattern as [[unlockAchievement]].
    */
  def recordPerkWonWith(perkId: String): IO[Unit] =
    sql"INSERT OR IGNORE INTO perk_won_with (perk_id) VALUES ($perkId)".update.run
      .transact(xa)
      .void

  /** Load what the story remembers: the chapter reached, how the last run ended, the scenes seen,
    * each NPC's vow, the cards earned (in the order they were earned), the streak of deaths and the
    * relics read, all in one transaction. A vow row or a last-run value this version does not know is ignored.
    */
  def loadStoryProgress(): IO[StoryProgress] =
    val getState  = sql"SELECT chapter, last_run FROM story_state WHERE id = 1".query[(Int, Option[String])].unique
    val getScenes = sql"SELECT scene_id FROM story_scene_seen".query[String].to[Set]
    val getVows   = sql"SELECT npc_id, state, reported FROM story_vow".query[(String, String, Boolean)].to[List]
    val getCards  = sql"SELECT card_id, seen FROM story_card ORDER BY rowid".query[(String, Boolean)].to[List]
    val getStreak = sql"SELECT streak FROM story_death_streak WHERE id = 1".query[Int].unique
    val getRelics = sql"SELECT devoted FROM story_relic_read".query[Int].to[Set]
    (for
      state  <- getState
      scenes <- getScenes
      vows   <- getVows
      cards  <- getCards
      streak <- getStreak
      relics <- getRelics
    yield
      val (chapter, lastRun) = state
      StoryProgress(
        chapter = chapter,
        lastRun = lastRun.flatMap(Database.parseRunOutcome),
        scenesSeen = scenes,
        vows = vows.flatMap((npcId, name, reported) => Database.parseVowState(name).map(npcId -> VowRecord(_, reported))).toMap,
        cards = cards.map((id, seen) => CardRecord(id, seen)),
        deathStreak = streak,
        relicsRead = relics
      )
    ).transact(xa)

  /** Overwrite the chapter reached and the outcome of the last run. */
  def saveStoryState(chapter: Int, lastRun: Option[RunOutcome]): IO[Unit] =
    val name = lastRun.map(Database.runOutcomeName)
    sql"UPDATE story_state SET chapter = $chapter, last_run = $name WHERE id = 1".update.run.transact(xa).void

  /** Overwrite the number of runs in a row that ended in death. */
  def saveDeathStreak(streak: Int): IO[Unit] =
    sql"UPDATE story_death_streak SET streak = $streak WHERE id = 1".update.run.transact(xa).void

  /** Record the relic of a Devoted as read. Idempotent. */
  def recordRelicRead(devoted: Int): IO[Unit] =
    sql"INSERT OR IGNORE INTO story_relic_read (devoted) VALUES ($devoted)".update.run.transact(xa).void

  /** Record a scene as played to its last line. Idempotent, same INSERT-OR-IGNORE pattern as
    * [[unlockAchievement]].
    */
  def recordSceneSeen(sceneId: String): IO[Unit] =
    sql"INSERT OR IGNORE INTO story_scene_seen (scene_id) VALUES ($sceneId)".update.run.transact(xa).void

  /** Record a card as earned, not seen yet. Idempotent: a card the save already has keeps its state. */
  def recordCardEarned(cardId: String): IO[Unit] =
    sql"INSERT OR IGNORE INTO story_card (card_id) VALUES ($cardId)".update.run.transact(xa).void

  /** Record a card as seen. A card that was never earned is not created. */
  def markCardSeen(cardId: String): IO[Unit] =
    sql"UPDATE story_card SET seen = 1 WHERE card_id = $cardId".update.run.transact(xa).void

  /** Save an NPC's vow, replacing the one it had. */
  def saveVow(npcId: String, vow: VowRecord): IO[Unit] =
    val name = Database.vowStateName(vow.state)
    sql"""INSERT INTO story_vow (npc_id, state, reported) VALUES ($npcId, $name, ${vow.reported})
          ON CONFLICT(npc_id) DO UPDATE SET state = excluded.state, reported = excluded.reported""".update.run
      .transact(xa)
      .void

object Database:

  private val Driver = "org.sqlite.JDBC"

  /** Every statement that builds the schema, in the order they run: the tables first, then the rows a
    * table must hold from the start. Each one is idempotent, so running them all again on an existing
    * save changes nothing. A table is only ever added here, never altered: `CREATE TABLE IF NOT EXISTS`
    * cannot add a column to a table a save already has.
    */
  private val schema: List[String] = List(
    """
      CREATE TABLE IF NOT EXISTS meta (
        id          INTEGER PRIMARY KEY CHECK (id = 1),
        currency    INTEGER NOT NULL DEFAULT 0
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS upgrade_unlock (
        upgrade_id  TEXT PRIMARY KEY,
        unlocked_at INTEGER NOT NULL DEFAULT (unixepoch())
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS achievement_unlock (
        achievement_id TEXT PRIMARY KEY,
        unlocked_at    INTEGER NOT NULL DEFAULT (unixepoch())
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS achievement_stats (
        id                 INTEGER PRIMARY KEY CHECK (id = 1),
        runs_completed     INTEGER NOT NULL DEFAULT 0,
        runs_won           INTEGER NOT NULL DEFAULT 0,
        current_win_streak INTEGER NOT NULL DEFAULT 0,
        total_shards_spent INTEGER NOT NULL DEFAULT 0
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS consumable_stats (
        id               INTEGER PRIMARY KEY CHECK (id = 1),
        consumables_used INTEGER NOT NULL DEFAULT 0
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS potion_type_used (
        type_id  TEXT PRIMARY KEY,
        used_at  INTEGER NOT NULL DEFAULT (unixepoch())
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS perk_won_with (
        perk_id TEXT PRIMARY KEY,
        won_at  INTEGER NOT NULL DEFAULT (unixepoch())
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS difficulty_win (
        difficulty TEXT PRIMARY KEY,
        wins       INTEGER NOT NULL DEFAULT 0
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS story_state (
        id       INTEGER PRIMARY KEY CHECK (id = 1),
        chapter  INTEGER NOT NULL DEFAULT 0,
        last_run TEXT
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS story_scene_seen (
        scene_id TEXT PRIMARY KEY,
        seen_at  INTEGER NOT NULL DEFAULT (unixepoch())
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS story_vow (
        npc_id   TEXT PRIMARY KEY,
        state    TEXT NOT NULL,
        reported INTEGER NOT NULL DEFAULT 1
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS story_card (
        card_id TEXT PRIMARY KEY,
        seen    INTEGER NOT NULL DEFAULT 0
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS story_death_streak (
        id     INTEGER PRIMARY KEY CHECK (id = 1),
        streak INTEGER NOT NULL DEFAULT 0
      )
    """,
    """
      CREATE TABLE IF NOT EXISTS story_relic_read (
        devoted INTEGER PRIMARY KEY
      )
    """,
    "INSERT OR IGNORE INTO meta (id, currency) VALUES (1, 0)",
    "INSERT OR IGNORE INTO achievement_stats (id) VALUES (1)",
    "INSERT OR IGNORE INTO consumable_stats (id) VALUES (1)",
    "INSERT OR IGNORE INTO story_state (id) VALUES (1)",
    "INSERT OR IGNORE INTO story_death_streak (id) VALUES (1)"
  )

  private def runOutcomeName(outcome: RunOutcome): String = outcome.toString.toLowerCase

  private def parseRunOutcome(name: String): Option[RunOutcome] =
    RunOutcome.values.find(runOutcomeName(_) == name)

  private def vowStateName(state: VowState): String = state.toString.toLowerCase

  private def parseVowState(name: String): Option[VowState] =
    VowState.values.find(vowStateName(_) == name)

  /** How a [[Difficulty]] is stored in `difficulty_win`: its lowercase name, the same spelling as on
    * the wire and in `data/difficulty.json`.
    */
  private def difficultyName(difficulty: Difficulty): String = difficulty.toString.toLowerCase

  private def parseDifficulty(name: String): Option[Difficulty] =
    Difficulty.values.find(difficultyName(_) == name)

  /** Acquire a file-backed [[Database]] as a managed [[Resource]].
    *
    * The schema is initialized on acquisition and the connection pool is released on exit.
    * The server should wrap its entire lifetime inside this resource.
    *
    * @param filePath  Path to the SQLite file, e.g. `"deepstone.db"`. Created if absent.
    */
  def resource(filePath: String): Resource[IO, Database] =
    val connectEC = ExecutionContext.fromExecutorService(
      Executors.newFixedThreadPool(1)
    )
    for
      xa <- HikariTransactor.newHikariTransactor[IO](
        Driver,
        s"jdbc:sqlite:$filePath",
        "",
        "",
        connectEC
      )
      db = new Database(xa)
      _ <- Resource.eval(db.initialize())
    yield db

  /** Acquire an in-memory [[Database]] as a managed [[Resource]].
    *
    * The schema is initialized on acquisition. Suitable for unit tests: each invocation of
    * this Resource produces a fully independent, isolated database.
    */
  def inMemory(): Resource[IO, Database] =
    val connectEC = ExecutionContext.fromExecutorService(
      Executors.newFixedThreadPool(1)
    )
    for
      xa <- HikariTransactor.newHikariTransactor[IO](
        Driver,
        "jdbc:sqlite::memory:",
        "",
        "",
        connectEC
      )
      db = new Database(xa)
      _ <- Resource.eval(db.initialize())
    yield db
