package roguelite.game

import java.util.UUID

/** Rarity tier for an item drop. */
enum Rarity:
  case Common, Uncommon

  def label: String = this match {
    case Common   => "common"
    case Uncommon => "uncommon"
  }

/** Effect applied when a consumable is used. */
enum ConsumableEffect:
  /** Restore a fixed amount of HP. */
  case HealFixed(amount: Int)

  /** Restore a percentage of max HP. */
  case HealPercent(percent: Int)

  /** Restore a fixed amount of the player's class resource (Rage / Focus / Mana). */
  case RestoreResource(amount: Int)

/** A runtime item instance held in a player's [[Inventory]].
  *
  * Items are immutable value objects. The `id` field is a unique instance identifier generated at
  * drop time (see [[Item.newId]]); `typeId` references the static definition in items.json.
  *
  * Items loaded by [[ItemLoader]] are ''prototypes'' with `id = ""`. Call [[withNewId]] to produce
  * a fresh inventory instance with a unique id.
  */
sealed trait Item:
  def id: String
  def typeId: String
  def name: String
  def rarity: Rarity

  /** Category string used by the client protocol. */
  def kind: String

  /** One-line stat summary for the UI — e.g. "+3 ATK", "Heal 30 HP". */
  def statLine: String

  /** Return a copy of this item with a freshly generated instance id. */
  def withNewId: Item

case class Weapon(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    attackBonus: Int
) extends Item:
  val kind             = "weapon"
  def statLine: String = s"+$attackBonus ATK"
  def withNewId: Item  = copy(id = Item.newId())

case class Armor(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    defenseBonus: Int
) extends Item:
  val kind: String     = "armor"
  def statLine: String = s"+$defenseBonus DEF"
  def withNewId: Item  = copy(id = Item.newId())

/** Accessories increase the player's max HP when picked up. */
case class Accessory(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    hpBonus: Int
) extends Item:
  val kind             = "accessory"
  def statLine: String = s"+$hpBonus MAX HP"
  def withNewId: Item  = copy(id = Item.newId())

case class Consumable(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    effect: ConsumableEffect
) extends Item:
  val kind = "consumable"
  def statLine: String = effect match {
    case ConsumableEffect.HealFixed(n)       => s"Heal $n HP"
    case ConsumableEffect.HealPercent(pct)   => s"Heal $pct% HP"
    case ConsumableEffect.RestoreResource(n) => s"Restore $n Resource"
  }
  def withNewId: Item = copy(id = Item.newId())

object Item:
  /** Generate a short unique instance identifier (8 hex chars). */
  def newId(): String = UUID.randomUUID().toString.take(8)
