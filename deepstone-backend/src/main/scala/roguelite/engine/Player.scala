package roguelite.engine

import roguelite.game.{ Accessory, Armor, Consumable, KeyKind, Weapon }

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
    equippedWeapon: Option[Weapon] = None,
    equippedArmor: Option[Armor] = None,
    equippedAccessories: Vector[Option[Accessory]] = Player.emptyAccessorySlots,
    potionBelt: Vector[Option[Consumable]] = Player.emptyPotionBelt,
    keyCounts: Map[KeyKind, Int] = Map.empty
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

  /** True once every equipment slot and every potion-belt slot is occupied. Drives the `packrat`
    * achievement (see [[roguelite.game.AchievementChecker]]). Deliberately ignores `keyCounts`: a
    * counter has no notion of "full".
    */
  def isFullyEquipped: Boolean =
    equippedWeapon.isDefined && equippedArmor.isDefined &&
      equippedAccessories.forall(_.isDefined) && potionBelt.forall(_.isDefined)

  /** Equip an accessory into `index`, bumping `maxHp` (and current HP by the same amount)
    * immediately, so the client always sees the correct HP cap without summing bonuses itself.
    */
  def equipAccessory(index: Int, acc: Accessory): Player =
    val newMax = maxHp + acc.hpBonus
    copy(equippedAccessories = equippedAccessories.updated(index, Some(acc)),
         maxHp = newMax,
         hp = (hp + acc.hpBonus).min(newMax)
    )

object Player:
  /** Number of accessory slots. Fixed: unlike the potion belt, no upgrade currently grows this. */
  val AccessorySlotCount: Int = 2

  /** Potion belt size before the `extra_slot` hub upgrade (which grants a 3rd). */
  val BasePotionBeltSlots: Int = 2

  def emptyAccessorySlots: Vector[Option[Accessory]] = Vector.fill(AccessorySlotCount)(None)
  def emptyPotionBelt: Vector[Option[Consumable]]     = Vector.fill(BasePotionBeltSlots)(None)
