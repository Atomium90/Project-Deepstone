package roguelite.engine

import roguelite.game.{ Accessory, Inventory, Item }

/** Full player data as stored on the server. */
case class Player(
    classId: ClassId,
    hp: Int,
    maxHp: Int,
    resourceCurrent: Int,
    resourceMax: Int,
    level: Int,
    xp: Int,
    metaCurrency: Int,
    bonusAttack: Int = 0,
    bonusDefense: Int = 0,
    affinityTags: Set[String] = Set.empty,
    inventory: Inventory = Inventory.empty
):
  def toView: PlayerView = PlayerView(
    classId = classId,
    hp = hp,
    maxHp = maxHp,
    resourceCurrent = resourceCurrent,
    resourceMax = resourceMax,
    level = level,
    xp = xp,
    metaCurrency = metaCurrency
  )

  def isAlive: Boolean = hp > 0

  /** Add an item to the inventory.
    *
    * Accessories immediately increase `maxHp` (and top up current HP by the same amount) so the
    * client always sees the correct HP cap without needing to sum inventory bonuses client-side.
    *
    * @return
    *   Right(updatedPlayer) on success, Left(error) if the inventory is full.
    */
  def withItemPickup(item: Item): Either[String, Player] =
    inventory
      .addItem(item)
      .map:
        newInventory =>
          item match {
            case acc: Accessory =>
              val newMax = maxHp + acc.hpBonus
              copy(inventory = newInventory, maxHp = newMax, hp = (hp + acc.hpBonus).min(newMax))
            case _ =>
              copy(inventory = newInventory)
          }
