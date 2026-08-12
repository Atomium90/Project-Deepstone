package roguelite.game

import roguelite.engine.Player

/** Outcome of resolving an item pickup against the player's current equipment. */
enum PickupOutcome:
  /** Auto-equipped into an empty slot. */
  case Equipped(player: Player)

  /** A key's count was incremented. Keys have no "slot" to fill, so there is never a choice to
    * offer for them.
    */
  case KeyCollected(player: Player)

  /** Every slot matching the new item's kind was already occupied. Until the interactive
    * keep/replace choice lands, callers treat this the same as today's "inventory full" case: the
    * new item is lost, nothing changes.
    */
  case Discarded

/** Resolves an item pickup into an auto-equip, a key count bump, or a discard.
  *
  * Shared by [[InteractionResolver.handleChest]] and [[CombatResolver.victory]] so the two
  * near-identical "pick up a loot drop" call sites don't each reimplement slot targeting. Pure and
  * dependency-free, like [[LootTable]].
  */
object EquipmentResolver:
  def resolvePickup(player: Player, item: Item): PickupOutcome = item match
    case w: Weapon =>
      if player.equippedWeapon.isEmpty then PickupOutcome.Equipped(player.copy(equippedWeapon = Some(w)))
      else PickupOutcome.Discarded

    case a: Armor =>
      if player.equippedArmor.isEmpty then PickupOutcome.Equipped(player.copy(equippedArmor = Some(a)))
      else PickupOutcome.Discarded

    case acc: Accessory =>
      val idx = player.equippedAccessories.indexWhere(_.isEmpty)
      if idx >= 0 then PickupOutcome.Equipped(player.equipAccessory(idx, acc))
      else PickupOutcome.Discarded

    case c: Consumable =>
      val idx = player.potionBelt.indexWhere(_.isEmpty)
      if idx >= 0
      then PickupOutcome.Equipped(player.copy(potionBelt = player.potionBelt.updated(idx, Some(c))))
      else PickupOutcome.Discarded

    case k: Key =>
      val updatedCount = player.keyCounts.getOrElse(k.keyKind, 0) + 1
      PickupOutcome.KeyCollected(player.copy(keyCounts = player.keyCounts.updated(k.keyKind, updatedCount)))
