package roguelite.game

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
    * whole run.
    */
  case PotionHealBonusPercent(percent: Int)

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
    effect: PerkEffect
)
