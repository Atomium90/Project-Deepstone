package roguelite.sim

import cats.effect.IO
import roguelite.engine.{ ClassId, Difficulty, HubState, Player, StateMachine }
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

  /** A hub for a save that owns nothing but the access to `classId` (so the class can be chosen at
    * all): no stat upgrade, no starting kit, no Shards.
    */
  def freshHub(classId: ClassId): HubState =
    val classAccess = upgradeDefs.values.filter(_.effect == UpgradeEffect.UnlockClass(classId)).map(_.id).toSet
    val placeholder = Player(ClassId.Warrior, hp = 100, maxHp = 100, resourceCurrent = 0, resourceMax = 100, level = 1, xp = 0, metaCurrency = 0)
    HubState(placeholder, upgradeDefs, MetaProgression(currency = 0, unlockedUpgrades = classAccess))

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
