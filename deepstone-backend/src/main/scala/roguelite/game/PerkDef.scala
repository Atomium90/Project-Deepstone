package roguelite.game

/** Effect granted by a run perk, chosen once per HUB visit and applied for that run only - never
  * persisted, unlike [[UpgradeEffect]].
  *
  * `ExtraStartingItem` is applied once, at the same point the class starting kit is resolved (see
  * [[roguelite.engine.StateMachine]]'s `StartRun` case). Other perk kinds (ongoing combat/loot
  * modifiers) are not part of this effect yet - they arrive alongside the resolver hooks that
  * actually check them.
  */
enum PerkEffect:
  /** Add one instance of the given item typeId to the starting inventory, same resolution as
    * [[UpgradeEffect.StartingItem]] (silently skipped if the typeId is unknown or every matching
    * slot is already full).
    */
  case ExtraStartingItem(typeId: String)

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
