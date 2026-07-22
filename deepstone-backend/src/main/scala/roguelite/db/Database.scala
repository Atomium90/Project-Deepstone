package roguelite.db

import doobie.util.transactor.Transactor
import doobie.implicits.*
import cats.implicits.*
import cats.effect.IO
import roguelite.game.{ AchievementStats, MetaProgression }
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
    val ddl =
      sql"""
              CREATE TABLE IF NOT EXISTS meta (
                id          INTEGER PRIMARY KEY CHECK (id = 1),
                currency    INTEGER NOT NULL DEFAULT 0
              )
            """.update.run *>
        sql"""
              CREATE TABLE IF NOT EXISTS upgrade_unlock (
                upgrade_id  TEXT PRIMARY KEY,
                unlocked_at INTEGER NOT NULL DEFAULT (unixepoch())
              )
            """.update.run *>
        sql"""
              CREATE TABLE IF NOT EXISTS achievement_unlock (
                achievement_id TEXT PRIMARY KEY,
                unlocked_at    INTEGER NOT NULL DEFAULT (unixepoch())
              )
            """.update.run *>
        sql"""
              CREATE TABLE IF NOT EXISTS achievement_stats (
                id                 INTEGER PRIMARY KEY CHECK (id = 1),
                runs_completed     INTEGER NOT NULL DEFAULT 0,
                runs_won           INTEGER NOT NULL DEFAULT 0,
                current_win_streak INTEGER NOT NULL DEFAULT 0,
                total_shards_spent INTEGER NOT NULL DEFAULT 0
              )
            """.update.run *>
        sql"""
              INSERT OR IGNORE INTO meta (id, currency) VALUES (1, 0)
            """.update.run *>
        sql"""
              INSERT OR IGNORE INTO achievement_stats (id) VALUES (1)
            """.update.run
    ddl.transact(xa).void

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
    * The two writes run in a single SQLite transaction — either both succeed or
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

  /** Load the lifetime achievement counters (runs completed/won, win streak, total Shards spent). */
  def loadAchievementStats(): IO[AchievementStats] =
    sql"""SELECT runs_completed, runs_won, current_win_streak, total_shards_spent
          FROM achievement_stats WHERE id = 1"""
      .query[(Int, Int, Int, Int)]
      .unique
      .map:
        case (runsCompleted, runsWon, currentWinStreak, totalShardsSpent) =>
          AchievementStats(runsCompleted, runsWon, currentWinStreak, totalShardsSpent)
      .transact(xa)

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

  /** Overwrite the lifetime achievement counters with their current values. */
  def saveAchievementStats(stats: AchievementStats): IO[Unit] =
    sql"""UPDATE achievement_stats
          SET runs_completed = ${stats.runsCompleted},
              runs_won = ${stats.runsWon},
              current_win_streak = ${stats.currentWinStreak},
              total_shards_spent = ${stats.totalShardsSpent}
          WHERE id = 1""".update.run.transact(xa).void

object Database:

  private val Driver = "org.sqlite.JDBC"

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
