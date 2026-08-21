package roguelite.game

/** One occupied potion-belt slot: up to [[roguelite.engine.Player.potionCapacity]] charges of the
  * same typeId, stacked instead of one slot per pickup. `item` is always the highest-rarity copy
  * currently held - a stack reflects its single best rarity, not a per-charge breakdown (see
  * [[EquipmentResolver.resolvePickup]]'s Consumable branch). `count` is always >= 1; an empty
  * belt slot is `None` on `Player.potionBelt`, never a `PotionStack` with `count == 0`.
  */
case class PotionStack(item: Consumable, count: Int)
