package roguelite.game.progression

import roguelite.engine.Player
import roguelite.game.items.Rarity

/** Effect granted by a run perk, chosen once per HUB visit and applied for that run only - never
  * persisted, unlike [[UpgradeEffect]].
  *
  * `ExtraStartingItem` is applied once, at the same point the class starting kit is resolved (see
  * [[roguelite.engine.StateMachine]]'s `StartRun` case). Every other kind is read live from
  * [[roguelite.engine.Player.activePerkId]] wherever it applies (e.g. [[FlatDamageBonus]] in
  * [[CombatResolver]]'s `attack` extension), the same "resolve an id against an external catalog"
  * pattern already used for equipped set bonuses.
  */
enum PerkEffect:
  /** Add one instance of the given item typeId to the starting inventory, same resolution as
    * [[UpgradeEffect.StartingItem]] (silently skipped if the typeId is unknown or every matching
    * slot is already full).
    */
  case ExtraStartingItem(typeId: String)

  /** Flat bonus added to the player's effective attack for the whole run. */
  case FlatDamageBonus(amount: Int)

  /** Percent bonus applied to every potion's heal amount (HealFixed and HealPercent alike) for the
    * whole run. A negative percent is a malus, which a risky perk takes on.
    */
  case PotionHealBonusPercent(percent: Int)

  /** Percent reduction applied to every ability's resource cost for the whole run. Stacks
    * additively with any active set AbilityCostReductionPercent bonus (Pyromancer 4pc), same
    * combination rule the set system already uses for stacking multiple effects of one kind. A
    * negative percent is a surcharge, which a risky perk takes on.
    */
  case AbilityCostReductionPercent(percent: Int)

  /** The first chest opened this run rolls at least `minRarity`, consumed by
    * [[roguelite.engine.Player.firstChestBonusUsed]] regardless of what the roll actually lands on
    * - a one-shot boost to the roll itself, not a standing floor for the rest of the run.
    */
  case GuaranteedRarityFirstChest(minRarity: Rarity)

  /** Percent added to the HP and attack of every enemy the player meets this run, on top of the
    * difficulty and Elite scaling (see [[roguelite.game.combat.EnemyInstance.fromStats]]). The XP an
    * enemy is worth does not follow, so the fight is harder for the same reward. A malus.
    */
  case EnemyStatPercent(percent: Int)

  /** Percent added to the player's max HP once the hub's upgrades have been applied at the start of
    * the run (negative for a malus), see [[roguelite.engine.GameSession]]'s run start.
    */
  case MaxHpPercent(percent: Int)

/** Static definition of one run perk, loaded from `data/perks.json`.
  *
  * Unlike [[UpgradeDef]], perks have no cost and no `displayOrder` - a random subset is rolled
  * fresh every HUB visit (see `HubState.perkOptions`), so a fixed catalog position doesn't apply.
  */
case class PerkDef(
    id: String,
    label: String,
    description: String,
    icon: String,
    effect: PerkEffect,
    /** Further effects of the same perk, for one that does several things (a risky pact). */
    extraEffects: List[PerkEffect] = Nil,
    /** A risky perk takes a malus in exchange for more Shards. It is offered in a row of its own,
      * only to a player who can start a Hard run, and never rolled among the ordinary perks.
      */
    risky: Boolean = false,
    /** Percent added to the Shards a kill earns, rounded up and worth at least one Shard more. */
    shardBonusPercent: Int = 0,
    /** Shards paid once when the run is won. */
    victoryShardBonus: Int = 0
):
  /** Every effect of the perk, the main one first. */
  def effects: List[PerkEffect] = effect :: extraEffects

object PerkDef:

  /** The perks a player took into the run: the ordinary one, then the risky one, each found in the
    * catalog (an id the catalog no longer has gives nothing).
    */
  def activeDefs(player: Player, perkDefs: Map[String, PerkDef]): List[PerkDef] =
    List(player.activePerkId, player.activeRiskyPerkId).flatten.flatMap(perkDefs.get)

  /** The effects of those perks, in that order. */
  def activeEffects(player: Player, perkDefs: Map[String, PerkDef]): List[PerkEffect] =
    activeDefs(player, perkDefs).flatMap(_.effects)

  /** The percent a player's perks add to the Shards of a kill. */
  def shardBonusPercent(player: Player, perkDefs: Map[String, PerkDef]): Int =
    activeDefs(player, perkDefs).map(_.shardBonusPercent).sum

  /** The Shards a player's perks pay when the run is won. */
  def victoryShardBonus(player: Player, perkDefs: Map[String, PerkDef]): Int =
    activeDefs(player, perkDefs).map(_.victoryShardBonus).sum

  /** The Shards a won run pays: what the perks pay, and nothing on a run that takes no blessing or once
    * the altar is shut, like the Shards of a kill.
    */
  def winBonus(player: Player, perkDefs: Map[String, PerkDef], blessings: Boolean, altarShut: Boolean): Int =
    if !blessings || altarShut then 0 else victoryShardBonus(player, perkDefs).max(0)

  /** `base` Shards with `bonusPercent` added: rounded up, and at least one more so that a bonus on a
    * kill that earns 1 or 2 Shards is felt. No bonus leaves `base` as it is.
    */
  def shardsWithBonus(base: Int, bonusPercent: Int): Int =
    if bonusPercent <= 0 || base <= 0 then base
    else math.max(base + 1, math.ceil(base * (100 + bonusPercent) / 100.0).toInt)
