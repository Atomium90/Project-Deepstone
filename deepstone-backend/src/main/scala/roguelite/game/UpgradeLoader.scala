package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.ClassId

/** Loads upgrade definitions from `data/upgrades.json` on the classpath.
  *
  * Each upgrade pairs a cost with an [[UpgradeEffect]] describing what it actually does when
  * unlocked. Resource reading and error wrapping are handled by [[JsonResourceLoader]].
  */
object UpgradeLoader extends JsonResourceLoader[UpgradeDef, String]:

  protected val resourcePath = "data/upgrades.json"

  protected def keyOf(entry: UpgradeDef): String = entry.id

  protected def parseEntries(json: String): Either[String, List[UpgradeDef]] =
    decode[List[UpgradeDefJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toUpgradeDef))

  private def toUpgradeDef(j: UpgradeDefJson): Either[String, UpgradeDef] =
    for
      category <- parseCategory(j.category)
      effect   <- parseEffect(j.effect)
    yield UpgradeDef(
      id = j.id,
      label = j.label,
      description = j.description,
      cost = j.cost,
      displayOrder = j.displayOrder,
      icon = j.icon,
      category = category,
      effect = effect
    )

  private def parseCategory(s: String): Either[String, UpgradeCategory] =
    UpgradeCategory.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown upgrade category: '$s'")

  private def parseEffect(e: UpgradeEffectJson): Either[String, UpgradeEffect] =
    e.`type` match
      case "MaxHpBoost" =>
        e.amount.toRight("MaxHpBoost is missing 'amount' field").map(UpgradeEffect.MaxHpBoost.apply)
      case "ExtraPotionSlot" =>
        Right(UpgradeEffect.ExtraPotionSlot)
      case "ExtraPotionCapacity" =>
        Right(UpgradeEffect.ExtraPotionCapacity)
      case "StartingItem" =>
        e.typeId.toRight("StartingItem is missing 'typeId' field").map(UpgradeEffect.StartingItem.apply)
      case "UnlockClass" =>
        e.classId
          .toRight("UnlockClass is missing 'classId' field")
          .flatMap(parseClassId)
          .map(UpgradeEffect.UnlockClass.apply)
      case "FlatAttackBoost" =>
        e.amount.toRight("FlatAttackBoost is missing 'amount' field").map(UpgradeEffect.FlatAttackBoost.apply)
      case "GuaranteedChestRarity" =>
        e.rarity
          .toRight("GuaranteedChestRarity is missing 'rarity' field")
          .flatMap(parseRarity)
          .map(UpgradeEffect.GuaranteedChestRarity.apply)
      case other =>
        Left(s"Unknown upgrade effect type: '$other'")

  private def parseClassId(s: String): Either[String, ClassId] =
    ClassId.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown classId: '$s'")

  private def parseRarity(s: String): Either[String, Rarity] =
    Rarity.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown rarity: '$s'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class UpgradeEffectJson(
      `type`: String,
      amount: Option[Int] = None,
      typeId: Option[String] = None,
      classId: Option[String] = None,
      rarity: Option[String] = None
  )

  private case class UpgradeDefJson(
      id: String,
      label: String,
      description: String,
      cost: Int,
      displayOrder: Int,
      icon: String,
      category: String,
      effect: UpgradeEffectJson
  )

  private given Decoder[UpgradeEffectJson] = Decoder.instance:
    (c: HCursor) =>
      for
        t       <- c.get[String]("type")
        amount  <- c.get[Option[Int]]("amount")
        typeId  <- c.get[Option[String]]("typeId")
        classId <- c.get[Option[String]]("classId")
        rarity  <- c.get[Option[String]]("rarity")
      yield UpgradeEffectJson(t, amount, typeId, classId, rarity)

  private given Decoder[UpgradeDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id           <- c.get[String]("id")
        label        <- c.get[String]("label")
        description  <- c.get[String]("description")
        cost         <- c.get[Int]("cost")
        displayOrder <- c.get[Int]("displayOrder")
        icon         <- c.get[String]("icon")
        category     <- c.get[String]("category")
        effect       <- c.get[UpgradeEffectJson]("effect")
      yield UpgradeDefJson(id, label, description, cost, displayOrder, icon, category, effect)
