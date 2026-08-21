package roguelite.game

import roguelite.engine.{ ExplorationState, GameState, Player }

/** Outcome of resolving an item pickup against the player's current equipment. */
enum PickupOutcome:
  /** Auto-equipped into an empty slot, or auto-replaced a strictly-lower-rarity copy of the same
    * typeId already occupying its slot (see [[resolvePickup]]'s same-typeId branches).
    */
  case Equipped(player: Player)

  /** A key's count was incremented. Keys have no "slot" to fill, so there is never a choice to
    * offer for them.
    */
  case KeyCollected(player: Player)

  /** Every slot matching the new item's kind was already occupied. Callers that support the
    * interactive keep/replace flow attach `pending` to `ExplorationState.pendingEquipChoice`;
    * callers that don't (starting kit, hub upgrade starting items) just ignore it, same as a
    * silent discard.
    */
  case ChoicePending(pending: PendingEquipChoice)

  /** The pickup was resolved with no equipment change: the player already holds an equal-or-
    * better copy of the same typeId, in the matching weapon/armor/accessory slot or potion belt
    * slot (see [[resolvePickup]]'s same-typeId branches). `player` is unchanged, carried only so
    * every case has the same "resulting player" shape at call sites.
    */
  case Discarded(player: Player)

/** Resolves an item pickup into an auto-equip, a key count bump, or a pending choice.
  *
  * Shared by [[InteractionResolver.handleChest]] and [[CombatResolver.victory]] so the two
  * near-identical "pick up a loot drop" call sites don't each reimplement slot targeting. Pure and
  * dependency-free, like [[LootTable]].
  */
object EquipmentResolver:
  def resolvePickup(player: Player,
                    item: Item,
                    setDefs: Map[String, SetDef] = Map.empty
  ): PickupOutcome = item match
    case w: Weapon =>
      // Same typeId already equipped: a rarity comparison against that one copy, never a
      // slot/choice decision - same convention as the Consumable case below.
      player.equippedWeapon match
        case Some(current) if current.typeId == w.typeId =>
          if w.rarity.ordinal > current.rarity.ordinal then
            PickupOutcome.Equipped(player.copy(equippedWeapon = Some(w)).reconcileSetHpBonus(setDefs))
          else
            PickupOutcome.Discarded(player)
        case None =>
          PickupOutcome.Equipped(player.copy(equippedWeapon = Some(w)).reconcileSetHpBonus(setDefs))
        case Some(current) =>
          PickupOutcome.ChoicePending(PendingEquipChoice(w, Map(EquipSlot.WeaponSlot -> current)))

    case a: Armor =>
      player.equippedArmor match
        case Some(current) if current.typeId == a.typeId =>
          if a.rarity.ordinal > current.rarity.ordinal then
            PickupOutcome.Equipped(player.copy(equippedArmor = Some(a)).reconcileSetHpBonus(setDefs))
          else
            PickupOutcome.Discarded(player)
        case None =>
          PickupOutcome.Equipped(player.copy(equippedArmor = Some(a)).reconcileSetHpBonus(setDefs))
        case Some(current) =>
          PickupOutcome.ChoicePending(PendingEquipChoice(a, Map(EquipSlot.ArmorSlot -> current)))

    case acc: Accessory =>
      player.equippedAccessories.zipWithIndex.collectFirst {
        case (Some(existing), i) if existing.typeId == acc.typeId => (i, existing)
      } match
        case Some((i, existing)) =>
          if acc.rarity.ordinal > existing.rarity.ordinal then
            val (_, unequipped) = player.unequipAccessory(i)
            PickupOutcome.Equipped(unequipped.equipAccessory(i, acc).reconcileSetHpBonus(setDefs))
          else
            PickupOutcome.Discarded(player)
        case None =>
          val idx = player.equippedAccessories.indexWhere(_.isEmpty)
          if idx >= 0 then
            PickupOutcome.Equipped(player.equipAccessory(idx, acc).reconcileSetHpBonus(setDefs))
          else
            val options = player.equippedAccessories.zipWithIndex.collect {
              case (Some(current), i) => EquipSlot.AccessorySlot(i) -> current
            }
            PickupOutcome.ChoicePending(PendingEquipChoice(acc, options.toMap))

    case c: Consumable =>
      // Same typeId already in the belt: a pickup below capacity always adds a charge (the stack
      // keeps its single best rarity, upgrading in place if the new pickup rolled higher) - never
      // a slot/choice decision. Only at capacity does rarity decide whether the find is wasted.
      player.potionBelt.zipWithIndex.collectFirst {
        case (Some(stack), i) if stack.item.typeId == c.typeId => (i, stack)
      } match
        case Some((i, stack)) =>
          val betterRarity = c.rarity.ordinal > stack.item.rarity.ordinal
          if stack.count < player.potionCapacity then
            val upgradedItem = if betterRarity then c else stack.item
            val newStack     = PotionStack(upgradedItem, stack.count + 1)
            PickupOutcome.Equipped(player.copy(potionBelt = player.potionBelt.updated(i, Some(newStack))))
          else if betterRarity then
            PickupOutcome.Equipped(player.copy(potionBelt = player.potionBelt.updated(i, Some(stack.copy(item = c)))))
          else
            PickupOutcome.Discarded(player)
        case None =>
          val idx = player.potionBelt.indexWhere(_.isEmpty)
          if idx >= 0
          then
            PickupOutcome.Equipped(
              player.copy(potionBelt = player.potionBelt.updated(idx, Some(PotionStack(c, 1))))
            )
          else
            val options = player.potionBelt.zipWithIndex.collect {
              case (Some(stack), i) => EquipSlot.PotionSlot(i) -> stack.item
            }
            val stackCounts = player.potionBelt.zipWithIndex.collect {
              case (Some(stack), i) => EquipSlot.PotionSlot(i) -> stack.count
            }
            PickupOutcome.ChoicePending(PendingEquipChoice(c, options.toMap, stackCounts.toMap))

    case k: Key =>
      val updatedCount = player.keyCounts.getOrElse(k.keyKind, 0) + 1
      PickupOutcome.KeyCollected(player.copy(keyCounts = player.keyCounts.updated(k.keyKind, updatedCount)))

  /** Resolve a follow-up `EQUIP_CHOICE` action against `exp.pendingEquipChoice`.
    *
    * `targetSlot = None` means "keep the current item(s), discard the new one". `Some(slot)` must
    * be one of the offered candidate slots; anything else (no choice pending, or a slot that
    * wasn't actually offered) is rejected with a log message and the pending choice, if any, is
    * left untouched so the client can retry.
    */
  def resolveChoice(exp: ExplorationState,
                    targetSlot: Option[EquipSlot],
                    setDefs: Map[String, SetDef] = Map.empty
  ): (GameState, List[String], List[GameEvent]) =
    exp.pendingEquipChoice match
      case None => (exp, List("No equip choice pending."), Nil)
      case Some(pending) =>
        targetSlot match
          case None =>
            (exp.copy(pendingEquipChoice = None), List(s"You leave ${pending.newItem.name} behind."), Nil)

          case Some(slot) if !pending.currentItems.contains(slot) =>
            (exp, List("Invalid equip slot choice."), Nil)

          case Some(slot) =>
            val updatedPlayer = applyToSlot(exp.player, slot, pending.newItem, setDefs)
            val next           = exp.copy(player = updatedPlayer, pendingEquipChoice = None)
            (next,
             List(s"You equip ${pending.newItem.name}."),
             List(GameEvent.itemPickedUp(updatedPlayer, pending.newItem, setDefs))
            )

  /** Place `newItem` into `slot`, evicting whatever was there (reversing an accessory's maxHp
    * bonus first, so swapping accessories never leaves a stale bonus behind), then reconciling
    * any set HP bonus that the swap may have gained or lost.
    */
  private def applyToSlot(player: Player,
                          slot: EquipSlot,
                          newItem: Item,
                          setDefs: Map[String, SetDef]
  ): Player = (slot, newItem) match
    case (EquipSlot.WeaponSlot, w: Weapon) =>
      player.copy(equippedWeapon = Some(w)).reconcileSetHpBonus(setDefs)
    case (EquipSlot.ArmorSlot, a: Armor) =>
      player.copy(equippedArmor = Some(a)).reconcileSetHpBonus(setDefs)
    case (EquipSlot.AccessorySlot(i), acc: Accessory) =>
      val (_, unequipped) = player.unequipAccessory(i)
      unequipped.equipAccessory(i, acc).reconcileSetHpBonus(setDefs)
    case (EquipSlot.PotionSlot(i), c: Consumable) =>
      // A choice replaces the whole stack with a fresh one of the new type, count 1 - swapping
      // types can cost the outgoing stack's remaining charges (see PotionStack).
      player.copy(potionBelt = player.potionBelt.updated(i, Some(PotionStack(c, 1))))
    case _ =>
      // Can't happen: `slot` always comes from a PendingEquipChoice built for this exact item's
      // kind (see resolvePickup above).
      player
