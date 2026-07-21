package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

/** Loads item definitions from `data/items.json` on the classpath.
 *
 * Item data is immutable reference data — read once at startup and held for the lifetime of the
 * server. Each entry is decoded into a ''prototype'' [[Item]] with `id = ""`; call
 * [[Item.withNewId]] (via [[LootTable]]) at drop time to produce distinct inventory instances.
 * Resource reading and error wrapping are handled by [[JsonResourceLoader]].
 */
object ItemLoader extends JsonResourceLoader[Item, String]:

  protected val resourcePath = "data/items.json"

  protected def keyOf(entry: Item): String = entry.typeId

  // ---------------------------------------------
  // JSON parsing
  // ---------------------------------------------

  protected def parseEntries(json: String): Either[String, List[Item]] =
    decode[List[ItemJson]](json)
      .leftMap(_.getMessage)
      .flatMap(ijs => ijs.traverse(toItem))

  private def toItem(ij: ItemJson): Either[String, Item] =
    parseRarity(ij.rarity).flatMap: rarity =>
      ij.kind.toLowerCase match
        case "weapon" =>
          ij.attackBonus.toRight("Weapon is missing 'attackBonus' field").map:
            bonus => Weapon(id = "", ij.typeId, ij.name, rarity, bonus)

        case "armor" =>
          ij.defenseBonus.toRight("Armor is missing 'defenseBonus' field").map:
            bonus => Armor(id = "", ij.typeId, ij.name, rarity, bonus)

        case "accessory" =>
          ij.hpBonus.toRight("Accessory is missing 'hpBonus' field").map:
            bonus => Accessory(id = "", ij.typeId, ij.name, rarity, bonus)

        case "consumable" =>
          ij.effect.toRight("Consumable is missing 'effect' field").flatMap:
            e => parseConsumableEffect(e).map:
              effect => Consumable(id = "", ij.typeId, ij.name, rarity, effect)

        case "key" =>
          ij.keyKind.toRight("Key is missing 'keyKind' field").flatMap(parseKeyKind(_, ij)).map:
            kk => Key(id = "", ij.typeId, ij.name, rarity, kk)

        case other =>
          Left(s"Unknown item kind: '$other'")

  private def parseRarity(s: String): Either[String, Rarity] =
    s.toLowerCase match
      case "common"   => Right(Rarity.Common)
      case "uncommon" => Right(Rarity.Uncommon)
      case other      => Left(s"Unknown rarity: '$other'")

  private def parseKeyKind(s: String, ij: ItemJson): Either[String, KeyKind] =
    s.toLowerCase match
      case "generic"   => Right(KeyKind.Generic)
      case "universal" => Right(KeyKind.Universal)
      case "specific"  => ij.doorId.toRight("Specific key is missing 'doorId' field").map(KeyKind.Specific.apply)
      case "typed"     => ij.doorTag.toRight("Typed key is missing 'doorTag' field").map(KeyKind.Typed.apply)
      case other       => Left(s"Unknown keyKind: '$other'")

  private def parseConsumableEffect(e: ConsumableEffectJson): Either[String, ConsumableEffect] =
    e.`type` match
      case "HealFixed" =>
        e.amount.toRight("HealFixed is missing 'amount' field").map(ConsumableEffect.HealFixed.apply)
      case "HealPercent" =>
        e.percent.toRight("HealPercent is missing 'percent' field").map(ConsumableEffect.HealPercent.apply)
      case "RestoreResource" =>
        e.amount.toRight("RestoreResource is missing 'amount' field").map(ConsumableEffect.RestoreResource.apply)
      case other =>
        Left(s"Unknown consumable effect type: '$other'")

  // ---------------------------------------------
  // Internal JSON DTOs
  // ---------------------------------------------

  private case class ConsumableEffectJson(
                                           `type`: String,
                                           amount: Option[Int] = None,
                                           percent: Option[Int] = None
                                         )

  private case class ItemJson(
                               typeId: String,
                               kind: String,
                               name: String,
                               rarity: String,
                               typeTag: Option[String] = None,
                               attackBonus: Option[Int] = None,
                               defenseBonus: Option[Int] = None,
                               hpBonus: Option[Int] = None,
                               effect: Option[ConsumableEffectJson] = None,
                               keyKind: Option[String] = None,
                               doorId: Option[String] = None,
                               doorTag: Option[String] = None
                             )

  // Circe decoders for internal DTOs
  private given Decoder[ConsumableEffectJson] = Decoder.instance: (c: HCursor) =>
    for
      t       <- c.get[String]("type")
      amount  <- c.get[Option[Int]]("amount")
      percent <- c.get[Option[Int]]("percent")
    yield ConsumableEffectJson(t, amount, percent)

  private given Decoder[ItemJson] = Decoder.instance: (c: HCursor) =>
    for
      typeId       <- c.get[String]("typeId")
      kind         <- c.get[String]("kind")
      name         <- c.get[String]("name")
      rarity       <- c.get[String]("rarity")
      typeTag      <- c.get[Option[String]]("typeTag")
      attackBonus  <- c.get[Option[Int]]("attackBonus")
      defenseBonus <- c.get[Option[Int]]("defenseBonus")
      hpBonus      <- c.get[Option[Int]]("hpBonus")
      effect       <- c.get[Option[ConsumableEffectJson]]("effect")
      keyKind      <- c.get[Option[String]]("keyKind")
      doorId       <- c.get[Option[String]]("doorId")
      doorTag      <- c.get[Option[String]]("doorTag")
    yield ItemJson(typeId, kind, name, rarity, typeTag, attackBonus, defenseBonus, hpBonus, effect,
                    keyKind, doorId, doorTag)
