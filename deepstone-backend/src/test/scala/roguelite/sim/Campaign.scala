package roguelite.sim

import roguelite.engine.{ ClassId, Difficulty }
import roguelite.game.progression.{ AchievementChecker, AchievementStats, DifficultyUnlocks, MetaProgression, ShardTopUp }

import scala.annotation.tailrec

/** A save lived through, run after run: the player starts with nothing, plays the highest tier the
  * save has unlocked, collects Shards, spends them in the hub and goes again. What the game gives
  * (the Shards a run earns, the tiers a win or a purchase opens, the top-up for a player stuck
  * without a kit) is the game's own code, run on the events of each simulated run.
  *
  * What the simulation decides is the player: one class all along, a fixed shopping order, and
  * always the highest tier open. The access to the class is given from the start, so that the
  * campaign measures the economy of the shop and the tiers, not of the class unlock.
  */
object Campaign:

  /** One visit to the hub and the run that followed it, as the save lived it. */
  final case class Step(
      campaign: Int,
      run: Int, // 1 for the first run of the save
      tier: Difficulty,
      outcome: String,
      level: Int,
      section: Int, // 1 for the first
      earned: Int, // Shards the run brought in
      topUp: Int, // Shards the game gave on top, for a player stuck without a kit
      spent: Int, // Shards spent in the shop after the run
      bought: List[String],
      balance: Int, // Shards left after shopping
      owned: Int, // upgrades owned after shopping
      unlocked: Set[Difficulty] // tiers open after shopping, the one just played included
  ):
    def victory: Boolean = outcome == "victory"

  private final case class Save(meta: MetaProgression, stats: AchievementStats, achievements: Set[String])

  /** What a player buys first: the kit of the class, then what makes the next run safer, then the
    * rest of the shop from the cheapest. Each visit buys the first thing of this list it can pay.
    */
  def shopOrder(world: SimWorld, classId: ClassId): List[String] =
    val chosen = world.kitUpgrade(classId).toList ++
      List("hp_boost_1", "potion_start", "extra_slot", "hp_boost_2", "weapon_mastery", "extra_potion_capacity", "rarity_insight")
    val rest = world.upgradeDefs.values.toList.sortBy(u => (u.cost, u.displayOrder)).map(_.id)
    (chosen ++ rest).distinct.filter(world.upgradeDefs.contains)

  /** `runs` runs of one save. Run n of campaign c uses seed `seed + c * 100000 + n`. */
  def play(world: SimWorld, policy: BotPolicy, classId: ClassId, campaign: Int, runs: Int, seed: Long): List[Step] =
    val simulator = RunSimulator(world, policy)
    val order     = shopOrder(world, classId)
    val start     = Save(MetaProgression(currency = 0, unlockedUpgrades = world.classAccess(classId)), AchievementStats.empty, Set.empty)
    val (_, steps) = (1 to runs).foldLeft((start, List.empty[Step])):
      case ((save, done), n) =>
        val (next, step) = visit(world, simulator, order, classId, campaign, n, seed + campaign * 100000L + n, save)
        (next, step :: done)
    steps.reverse

  private def visit(world: SimWorld,
                    simulator: RunSimulator,
                    order: List[String],
                    classId: ClassId,
                    campaign: Int,
                    n: Int,
                    seed: Long,
                    save: Save
  ): (Save, Step) =
    // The highest tier the save has open: what a player who wants to get on does.
    val tier   = DifficultyUnlocks.unlockedTiers(world.difficultyDefs, save.stats).maxBy(_.ordinal)
    val setup  = SimSetup(classId, tier, SimProfile.Fresh)
    val played = simulator.play(n, seed, setup, world.hub(save.meta, setup), "campaign")
    val row    = played.row

    // The run ends with the Shards of the pocket as the player holds them: what it earned is the difference.
    val afterRun            = save.meta.copy(currency = row.shards)
    val (stats, newlyEarned) = AchievementChecker.checkEvents(world.achievementDefs, save.achievements, save.stats, played.events)
    val achievements        = save.achievements ++ newlyEarned.map(_.id)

    val grant     = ShardTopUp.grantFor(afterRun, stats, world.upgradeDefs)
    val topUp     = grant.fold(0)(_.amount)
    val withTopUp = afterRun.copy(currency = afterRun.currency + topUp)

    val (meta, finalStats, finalAchievements, bought) = shop(world, order, withTopUp, stats, achievements, Nil)
    val spent = bought.map(world.upgradeDefs(_).cost).sum
    val step = Step(
      campaign = campaign,
      run = n,
      tier = tier,
      outcome = row.outcome,
      level = row.level,
      section = row.section + 1,
      earned = row.shards - save.meta.currency,
      topUp = topUp,
      spent = spent,
      bought = bought,
      balance = meta.currency,
      owned = meta.unlockedUpgrades.size,
      unlocked = DifficultyUnlocks.unlockedTiers(world.difficultyDefs, finalStats)
    )
    (Save(meta, finalStats, finalAchievements), step)

  /** Buys, one after the other, the first upgrade of `order` the save can pay, until none is left. */
  @tailrec
  private def shop(world: SimWorld,
                   order: List[String],
                   meta: MetaProgression,
                   stats: AchievementStats,
                   achievements: Set[String],
                   bought: List[String]
  ): (MetaProgression, AchievementStats, Set[String], List[String]) =
    order.iterator.flatMap(id => meta.purchase(id, world.upgradeDefs).toOption.map(id -> _)).nextOption() match
      case None => (meta, stats, achievements, bought)
      case Some((id, next)) =>
        val (nextStats, earned) = AchievementChecker.checkPurchase(
          world.achievementDefs, achievements, stats, world.upgradeDefs(id).cost, next.unlockedUpgrades.size, world.upgradeDefs.size
        )
        shop(world, order, next, nextStats, achievements ++ earned.map(_.id), bought :+ id)
