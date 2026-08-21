package roguelite.game

/** Identifies one equipment or potion-belt slot on a [[roguelite.engine.Player]].
  *
  * `AccessorySlot`/`PotionSlot` carry an index rather than being separate enum cases per slot,
  * since both slot families are fixed-size vectors on `Player` (2 accessory slots, 2-3 potion
  * belt slots depending on the `extra_slot` upgrade).
  */
enum EquipSlot:
  case WeaponSlot
  case ArmorSlot
  case AccessorySlot(index: Int)
  case PotionSlot(index: Int)

/** A pickup offered to the player because every slot matching `newItem`'s kind is already
  * occupied. `currentItems` lists every occupied candidate slot the new item could replace - one
  * entry for weapon/armor (single slot), up to 2 for accessories, up to 3 for potions.
  *
  * `stackCounts` carries each potion slot's current charge count (see [[PotionStack]]), keyed the
  * same way as `currentItems` - empty for weapon/armor/accessory choices, which never stack.
  *
  * Held on [[roguelite.engine.GameState.ExplorationState]] until resolved by a follow-up
  * `EQUIP_CHOICE` action - deliberately durable state, not a transient event, so it survives a
  * reconnect or an unrelated action sent in between.
  */
case class PendingEquipChoice(newItem: Item,
                              currentItems: Map[EquipSlot, Item],
                              stackCounts: Map[EquipSlot, Int] = Map.empty
)
