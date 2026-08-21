package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

/** Loads run perk definitions from `data/perks.json` on the classpath.
  *
  * Mirrors [[UpgradeLoader]]: resource reading and error wrapping are handled by
  * [[JsonResourceLoader]], this object only supplies the parsing.
  */
object PerkLoader extends JsonResourceLoader[PerkDef, String]:

  protected val resourcePath = "data/perks.json"

  protected def keyOf(entry: PerkDef): String = entry.id

  protected def parseEntries(json: String): Either[String, List[PerkDef]] =
    decode[List[PerkDefJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toPerkDef))

  private def toPerkDef(j: PerkDefJson): Either[String, PerkDef] =
    parseEffect(j.effect).map:
      effect =>
        PerkDef(id = j.id, label = j.label, description = j.description, icon = j.icon, effect = effect)

  private def parseEffect(e: PerkEffectJson): Either[String, PerkEffect] =
    e.`type` match
      case "ExtraStartingItem" =>
        e.typeId.toRight("ExtraStartingItem is missing 'typeId' field").map(PerkEffect.ExtraStartingItem.apply)
      case "FlatDamageBonus" =>
        e.amount.toRight("FlatDamageBonus is missing 'amount' field").map(PerkEffect.FlatDamageBonus.apply)
      case "PotionHealBonusPercent" =>
        e.amount
          .toRight("PotionHealBonusPercent is missing 'amount' field")
          .map(PerkEffect.PotionHealBonusPercent.apply)
      case "AbilityCostReductionPercent" =>
        e.amount
          .toRight("AbilityCostReductionPercent is missing 'amount' field")
          .map(PerkEffect.AbilityCostReductionPercent.apply)
      case "GuaranteedRarityFirstChest" =>
        e.minRarity
          .toRight("GuaranteedRarityFirstChest is missing 'minRarity' field")
          .flatMap(parseRarity)
          .map(PerkEffect.GuaranteedRarityFirstChest.apply)
      case other =>
        Left(s"Unknown perk effect type: '$other'")

  private def parseRarity(s: String): Either[String, Rarity] =
    s match
      case "common"   => Right(Rarity.Common)
      case "uncommon" => Right(Rarity.Uncommon)
      case "rare"     => Right(Rarity.Rare)
      case "epic"     => Right(Rarity.Epic)
      case other      => Left(s"Unknown rarity: '$other'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class PerkEffectJson(`type`: String,
                                    typeId: Option[String] = None,
                                    amount: Option[Int] = None,
                                    minRarity: Option[String] = None
  )

  private case class PerkDefJson(id: String, label: String, description: String, icon: String, effect: PerkEffectJson)

  private given Decoder[PerkEffectJson] = Decoder.instance:
    (c: HCursor) =>
      for
        t         <- c.get[String]("type")
        typeId    <- c.get[Option[String]]("typeId")
        amount    <- c.get[Option[Int]]("amount")
        minRarity <- c.get[Option[String]]("minRarity")
      yield PerkEffectJson(t, typeId, amount, minRarity)

  private given Decoder[PerkDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id          <- c.get[String]("id")
        label       <- c.get[String]("label")
        description <- c.get[String]("description")
        icon        <- c.get[String]("icon")
        effect      <- c.get[PerkEffectJson]("effect")
      yield PerkDefJson(id, label, description, icon, effect)
