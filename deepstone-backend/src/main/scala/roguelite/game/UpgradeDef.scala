package roguelite.game

import roguelite.engine.ClassId

/** Effect applied when an upgrade is unlocked.
  *
  * Interpreted in two places:
  *   - Player-state effects (`MaxHpBoost`, `ExtraInventorySlot`, `StartingItem`) are applied once
  *     per run, at Hub → Exploration transition, by
  *     [[roguelite.engine.GameSession.applyMetaBonuses]].
  *   - `UnlockClass` is a gate, not a player-state change: [[roguelite.engine.StateMachine]]
  *     checks it when handling `StartRun` and rejects locked classes.
  */
enum UpgradeEffect:
  /** Increase max HP (and current HP by the same amount) at the start of each run. */
  case MaxHpBoost(amount: Int)

  /** Grow the starting inventory by one slot. */
  case ExtraInventorySlot

  /** Add one instance of the given item typeId to the starting inventory. Silently skipped if the
    * typeId is unknown or the inventory is full.
    */
  case StartingItem(typeId: String)

  /** Gate selecting the given class at run start behind this upgrade. Classes with no matching
    * `UnlockClass` upgrade are available by default.
    */
  case UnlockClass(classId: ClassId)

/** Static definition of one hub upgrade available for purchase, loaded from `data/upgrades.json`.
  *
  * @param displayOrder
  *   Position in the hub upgrade list (ascending). JSON array order is not preserved once loaded
  *   into a `Map`, so this field is the explicit source of truth for display order.
  */
case class UpgradeDef(
    id: String,
    label: String,
    description: String,
    cost: Int,
    displayOrder: Int,
    effect: UpgradeEffect
)
