package roguelite.game

import java.util.UUID

/** Rarity tier for an item drop.
  *
  * `Common`/`Uncommon` are authored per catalog entry in items.json and act as a ''floor'':
  * [[LootTable]] rolls the actual drop tier at or above that floor, never below it, then scales
  * the item's numeric stat fields by [[statMultiplier]] relative to the floor tier's own
  * multiplier. `Rare`/`Epic` are never authored directly on a catalog entry, only ever rolled.
  */
enum Rarity:
  case Common, Uncommon, Rare, Epic

  def label: String = this match {
    case Common   => "common"
    case Uncommon => "uncommon"
    case Rare     => "rare"
    case Epic     => "epic"
  }

  /** Stat multiplier applied to a rolled item's numeric bonus fields, relative to another tier's
    * own multiplier (see [[LootTable]]'s roll-and-scale step). Not meant to be read in isolation.
    */
  def statMultiplier: Double = this match {
    case Common   => 1.0
    case Uncommon => 1.15
    case Rare     => 1.35
    case Epic     => 1.6
  }

  /** Multiplier applied to a rolled potion's flat-amount effect fields (heal/restore/damage
    * amounts), relative to another tier's own multiplier - same role as [[statMultiplier]] but
    * steeper, since a potion is a one-shot consumed effect rather than a bonus that compounds
    * over a whole run the way equipped gear does. Percent-based consumable effects (attack/crit
    * buffs) deliberately stay on the gentler [[statMultiplier]] curve instead - see
    * [[LootTable.rollRarityAndScale]].
    */
  def potionMultiplier: Double = this match {
    case Common   => 1.0
    case Uncommon => 1.5
    case Rare     => 2.0
    case Epic     => 2.5
  }

/** Effect applied when a consumable is used. */
enum ConsumableEffect:
  /** Restore a fixed amount of HP. */
  case HealFixed(amount: Int)

  /** Restore a percentage of max HP. */
  case HealPercent(percent: Int)

  /** Restore a fixed amount of the player's class resource (Rage / Focus / Mana). */
  case RestoreResource(amount: Int)

  /** Grant a final-damage-multiplier attack buff for the next `turns` rounds, stacking with any
    * active set AttackDamagePercent bonus. A new AttackBuff refreshes the duration rather than
    * stacking magnitude with an already-active one. See [[TimedBuffEffect.AttackBonusPercent]].
    */
  case AttackBuff(percent: Int, turns: Int)

  /** Deal fixed damage straight to the enemy in combat: no crit roll, no defense mitigation, same
    * pattern as Mage's Arcane Blast ability ([[AbilityEffect.FlatDamage]]).
    */
  case FlatDamage(amount: Int)

  /** Grant a crit chance buff for the next `turns` rounds, stacking with the player's normal
    * critChance. Same refresh-not-stack rule as AttackBuff. See
    * [[TimedBuffEffect.CritChanceBonusPercent]].
    */
  case CritBuff(percent: Int, turns: Int)

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

  /** One-line stat summary for the UI, e.g. "+3 ATK", "Heal 30 HP". */
  def statLine: String

  /** Flavor text, e.g. "Warm to the touch, humming with barely-checked fury." Absent for every
    * item authored before the item-pool rework; not yet surfaced to the client (see ItemView).
    */
  def description: Option[String]

  /** Atlas sprite key for this item's icon (see frontend/public/atlas/items.json). Absent for
    * every item without sourced art - the client falls back to a plain colored box.
    */
  def iconId: Option[String]

  /** Return a copy of this item with a freshly generated instance id. */
  def withNewId: Item

case class Weapon(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    attackBonus: Int,
    typeTag: Option[String] = None,
    setId: Option[String] = None,
    description: Option[String] = None,
    iconId: Option[String] = None
) extends Item:
  val kind             = "weapon"
  def statLine: String = typeTag match {
    case Some(tag)  => s"+$attackBonus ATK [$tag]"
    case None       => s"+$attackBonus ATK"
  }
  def withNewId: Item  = copy(id = Item.newId())

case class Armor(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    defenseBonus: Int,
    typeTag: Option[String] = None,
    setId: Option[String] = None,
    description: Option[String] = None,
    iconId: Option[String] = None
) extends Item:
  val kind: String     = "armor"
  def statLine: String = typeTag match {
    case Some(tag)  => s"+$defenseBonus DEF [$tag]"
    case None       => s"+$defenseBonus DEF"
  }
  def withNewId: Item  = copy(id = Item.newId())

/** Accessories may carry any combination of hpBonus/attackBonus/defenseBonus/critChanceBonus -
  * today's data always sets exactly one, but all four stay independently optional so a future
  * item can combine them. hpBonus (if present) is applied immediately/permanently on equip
  * ([[roguelite.engine.Player.equipAccessory]]); attackBonus/defenseBonus/critChanceBonus are
  * read live by CombatResolver every combat action instead, same as weapon/armor bonuses.
  */
case class Accessory(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    hpBonus: Option[Int] = None,
    typeTag: Option[String] = None,
    attackBonus: Option[Int] = None,
    defenseBonus: Option[Int] = None,
    critChanceBonus: Option[Int] = None,
    setId: Option[String] = None,
    description: Option[String] = None,
    iconId: Option[String] = None
) extends Item:
  val kind             = "accessory"
  def statLine: String =
    val parts = List(
      hpBonus.map(n => s"+$n MAX HP"),
      attackBonus.map(n => s"+$n ATK"),
      defenseBonus.map(n => s"+$n DEF"),
      critChanceBonus.map(n => s"+$n% CRIT")
    ).flatten
    val tagSuffix = typeTag.map(tag => s" [$tag]").getOrElse("")
    parts.mkString(", ") + tagSuffix
  def withNewId: Item  = copy(id = Item.newId())

case class Consumable(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    effect: ConsumableEffect,
    description: Option[String] = None,
    iconId: Option[String] = None
) extends Item:
  val kind = "consumable"
  def statLine: String = effect match {
    case ConsumableEffect.HealFixed(n)       => s"Heal $n HP"
    case ConsumableEffect.HealPercent(pct)   => s"Heal $pct% HP"
    case ConsumableEffect.RestoreResource(n) => s"Restore $n Resource"
    case ConsumableEffect.AttackBuff(pct, t) => s"+$pct% ATK for $t turns"
    case ConsumableEffect.FlatDamage(n)      => s"Deal $n damage"
    case ConsumableEffect.CritBuff(pct, t)   => s"+$pct% crit chance for $t turns"
  }
  def withNewId: Item = copy(id = Item.newId())

/** What a [[Key]] is able to unlock. Only [[KeyKind.Generic]] has content in V0.2, the other
  * variants exist so future content (unique keys, elemental-tagged keys, rare passe-partouts) can
  * be added without touching this logic.
  */
enum KeyKind:
  case Generic
  case Specific(doorId: String)
  case Typed(doorTag: String)
  case Universal

object KeyKind:
  def canUnlock(key: KeyKind, door: LockedDoor): Boolean = key match {
    case KeyKind.Generic      => true
    case KeyKind.Specific(id) => id == door.id
    case KeyKind.Typed(tag)   => door.doorTag.contains(tag)
    case KeyKind.Universal    => true
  }

/** Consumed on use to unlock a matching [[LockedDoor]]. Carries no combat stats. */
case class Key(
    id: String,
    typeId: String,
    name: String,
    rarity: Rarity,
    keyKind: KeyKind,
    description: Option[String] = None,
    iconId: Option[String] = None
) extends Item:
  val kind = "key"
  def statLine: String = "Opens a locked door"
  def withNewId: Item = copy(id = Item.newId())

object Item:
  /** Generate a short unique instance identifier (8 hex chars). */
  def newId(): String = UUID.randomUUID().toString.take(8)
