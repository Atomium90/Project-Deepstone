package roguelite.sim

import cats.effect.IO
import roguelite.engine.{ ClassId, Difficulty, HubState, Player, RunStartBonuses, StateMachine }
import roguelite.game.combat.{ AbilityDef, AbilityLoader, CombatResolver, EnemyLoader, EnemyStats, StatCalculator }
import roguelite.game.dungeon.{ Room, RoomLoader }
import roguelite.game.items.{ Item, ItemLoader, SetDef, SetLoader }
import roguelite.game.progression.{
  ClassDef,
  ClassLoader,
  DifficultyLoader,
  DifficultyTierDef,
  MetaProgression,
  PerkDef,
  PerkLoader,
  UpgradeDef,
  UpgradeEffect,
  UpgradeLoader
}

import scala.util.Random

/** Every catalog the game reads at start-up, loaded once and shared by all the simulated runs.
  *
  * Mirrors what `Main` builds, minus everything that needs IO at play time (database, story).
  */
final case class SimWorld(
    roomPool: Map[String, Room],
    enemyStats: Map[String, EnemyStats],
    itemDefs: Map[String, Item],
    classDefs: Map[ClassId, ClassDef],
    abilityDefs: Map[ClassId, AbilityDef],
    upgradeDefs: Map[String, UpgradeDef],
    setDefs: Map[String, SetDef],
    perkDefs: Map[String, PerkDef],
    difficultyDefs: Map[Difficulty, DifficultyTierDef]
):

  /** The same attack, defense and crit math the combat uses. */
  val stats: StatCalculator = StatCalculator(setDefs, perkDefs)

  /** A state machine whose every random draw (dungeon, loot, combat) comes from `seed`, so a run can
    * be replayed exactly.
    */
  def stateMachine(seed: Long): StateMachine =
    val resolver = CombatResolver(Random(seed * 31 + 7), itemDefs, abilityDefs, setDefs, perkDefs)
    StateMachine(roomPool,
                 enemyStats,
                 itemDefs,
                 classDefs,
                 upgradeDefs,
                 resolver,
                 rng = Random(seed),
                 setDefs = setDefs,
                 perkDefs = perkDefs,
                 difficultyDefs = difficultyDefs
    )

  /** What the owned upgrades and the perks change in the player as the run starts, as the server does it. */
  val runStart: RunStartBonuses = RunStartBonuses(upgradeDefs, itemDefs, setDefs, perkDefs)

  /** The upgrade that opens `classId` at the hub, if any (the Warrior is open from the start). */
  def classAccess(classId: ClassId): Set[String] =
    upgradeDefs.values.filter(_.effect == UpgradeEffect.UnlockClass(classId)).map(_.id).toSet

  /** The upgrade that gives `classId` its starting kit. */
  def kitUpgrade(classId: ClassId): Set[String] =
    upgradeDefs.values.filter(_.effect == UpgradeEffect.UnlockStartingKit(classId)).map(_.id).toSet

  /** The hub of a save that owns `setup`'s upgrades (and the access to its class, so it can be
    * chosen at all), with the chosen perks on offer.
    */
  def hub(setup: SimSetup): HubState =
    val owned       = setup.profile.upgrades(this, setup.classId) ++ classAccess(setup.classId)
    val placeholder = Player(ClassId.Warrior, hp = 100, maxHp = 100, resourceCurrent = 0, resourceMax = 100, level = 1, xp = 0, metaCurrency = 0)
    HubState(placeholder,
             upgradeDefs,
             MetaProgression(currency = 0, unlockedUpgrades = owned),
             perkOptions = setup.perkId.flatMap(perkDefs.get).toList,
             riskyPerkOptions = setup.riskyPerkId.flatMap(perkDefs.get).toList
    )

object SimWorld:

  def load(): IO[SimWorld] =
    for
      rooms       <- RoomLoader.loadAll()
      enemies     <- EnemyLoader.loadAll()
      items       <- ItemLoader.loadAll()
      classes     <- ClassLoader.loadAll()
      abilities   <- AbilityLoader.loadAll()
      upgrades    <- UpgradeLoader.loadAll()
      sets        <- SetLoader.loadAll()
      perks       <- PerkLoader.loadAll()
      difficulties <- DifficultyLoader.loadAll()
    yield SimWorld(rooms, enemies, items, classes, abilities, upgrades, sets, perks, difficulties)
