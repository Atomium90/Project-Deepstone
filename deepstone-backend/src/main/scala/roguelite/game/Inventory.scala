package roguelite.game

/** A fixed-size item inventory backed by a slot vector.
 *
 * Slots are indexed 0 to [[MaxSlots]]−1. An empty slot is `None`. All mutation methods return a
 * new `Inventory` instance — this type is fully immutable.
 *
 * Weapon, armor, and accessory bonuses are always active (no explicit equip step). Consumables
 * must be used explicitly via a combat or hub action.
 */
case class Inventory(slots: Vector[Option[Item]]):

  /** True if every slot is occupied. */
  def isFull: Boolean = slots.forall(_.isDefined)

  /** Number of occupied slots. */
  def size: Int = slots.count(_.isDefined)

  /** Add an item to the first empty slot.
   *
   * @return Right(newInventory) on success, Left(error message) if the inventory is full.
   */
  def addItem(item: Item): Either[String, Inventory] =
    slots.indexWhere(_.isEmpty) match {
      case -1 => Left("Inventory is full.")
      case idx => Right(copy(slots = slots.updated(idx, Some(item))))
    }

  /** Remove and return the item at `index`.
   *
   * Returns `(None, this)` if the index is out of bounds or the slot is already empty.
   */
  def removeAt(index: Int): (Option[Item], Inventory) =
    if index < 0 || index >= slots.length then (None, this)
    else (slots(index), copy(slots = slots.updated(index, None)))

  /** Find an item by its unique instance id.
   *
   * @return Some((slotIndex, item)) if found, None otherwise.
   */
  def findById(id: String): Option[(Int, Item)] =
    slots.zipWithIndex.collectFirst:
      case (Some(item), idx) if item.id == id => (idx, item)

  // ---------------------------------------------
  // Passive stat aggregation
  // ---------------------------------------------

  /** Sum of all [[Weapon]] attack bonuses currently in inventory. */
  def totalAttackBonus: Int =
    slots.collect { case Some(w: Weapon) => w.attackBonus }.sum

  /** Sum of all [[Armor]] defense bonuses currently in inventory. */
  def totalDefenseBonus: Int =
    slots.collect { case Some(a: Armor) => a.defenseBonus }.sum

  /** All consumables in the inventory, paired with their slot index, in slot order. */
  def consumables: List[(Int, Consumable)] =
    slots.zipWithIndex.collect:
      case (Some(c: Consumable), idx) => (idx, c)
    .toList

  /** All items currently in the inventory, in slot order (None slots filtered out). */
  def items: List[Item] =
    slots.collect { case Some(item) => item }.toList

object Inventory:
  /** Maximum number of inventory slots. */
  val MaxSlots: Int = 6

  /** An inventory with all slots empty. */
  val empty: Inventory = Inventory(Vector.fill(MaxSlots)(None))
