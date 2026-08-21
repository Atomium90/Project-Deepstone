package roguelite.engine

import roguelite.game.{ Accessory, Armor, Consumable, KeyKind, SetBonusEffect, SetDef, Weapon }

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
    keyCounts: Map[KeyKind, Int] = Map.empty,
    activeSetHpBonusFlat: Int = 0,
    activePerkId: Option[String] = None,
    firstChestBonusUsed: Boolean = false
):
  def toView: PlayerView = PlayerView(
    classId = classId,
    hp = hp,
    maxHp = maxHp,
    resourceCurrent = resourceCurrent,
    resourceMax = resourceMax,
    level = level,
    xp = xp,
    metaCurrency = metaCurrency,
    affinityTags = affinityTags.toList
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
    val hpBonus = acc.hpBonus.getOrElse(0)
    val newMax  = maxHp + hpBonus
    copy(equippedAccessories = equippedAccessories.updated(index, Some(acc)),
         maxHp = newMax,
         hp = (hp + hpBonus).min(newMax)
    )

  /** Reverse of [[equipAccessory]]: clears the slot and rolls back the maxHp bonus (current HP
    * clamped down if it was above the new cap). A no-op if the slot was already empty.
    */
  def unequipAccessory(index: Int): (Option[Accessory], Player) =
    equippedAccessories(index) match
      case None => (None, this)
      case Some(acc) =>
        val newMax = (maxHp - acc.hpBonus.getOrElse(0)).max(1)
        (Some(acc),
         copy(equippedAccessories = equippedAccessories.updated(index, None),
              maxHp = newMax,
              hp = hp.min(newMax)
         )
        )

  /** setId of every equipped weapon/armor/accessory that carries one, duplicates included (one
    * entry per piece) - the raw input to [[SetDef.activeBonuses]].
    */
  def equippedSetIds: List[String] =
    List(equippedWeapon.flatMap(_.setId), equippedArmor.flatMap(_.setId)).flatten ++
      equippedAccessories.flatten.flatMap(_.setId)

  /** Recompute the flat maxHp contribution from active `MaxHpPercent` set bonuses, given the
    * player's current equipment.
    *
    * Reverses the previously-applied amount first (tracked in [[activeSetHpBonusFlat]], since
    * there's no separate "base" maxHp field to recompute a percentage against - level-ups and
    * accessory pickups mutate `maxHp` directly), then reapplies the new percentage against the
    * resulting baseline. This makes gear swaps that cross a 2pc/4pc threshold safe to call after
    * every equip/unequip, regardless of level-ups that happened in between.
    *
    * Must be called after every change to `equippedWeapon`/`equippedArmor`/
    * `equippedAccessories` (see [[roguelite.game.EquipmentResolver]]) - it isn't automatic on
    * `copy`, since `Player` has no reference to the loaded [[SetDef]] catalog.
    */
  def reconcileSetHpBonus(setDefs: Map[String, SetDef]): Player =
    val baseline = (maxHp - activeSetHpBonusFlat).max(1)
    val percent  = SetDef.activeBonuses(equippedSetIds, setDefs, classId).collect {
      case SetBonusEffect.MaxHpPercent(p) => p
    }.sum
    val newBonus = baseline * percent / 100
    val newMax   = baseline + newBonus
    copy(maxHp = newMax,
         hp = (hp - activeSetHpBonusFlat + newBonus).max(0).min(newMax),
         activeSetHpBonusFlat = newBonus
    )

object Player:
  /** Number of accessory slots. Fixed: unlike the potion belt, no upgrade currently grows this. */
  val AccessorySlotCount: Int = 2

  /** Potion belt size before the `extra_slot` hub upgrade (which grants a 3rd). */
  val BasePotionBeltSlots: Int = 2

  def emptyAccessorySlots: Vector[Option[Accessory]] = Vector.fill(AccessorySlotCount)(None)
  def emptyPotionBelt: Vector[Option[Consumable]]     = Vector.fill(BasePotionBeltSlots)(None)
