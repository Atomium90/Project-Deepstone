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
    parseEffect(j.effect).map:
      effect =>
        UpgradeDef(
          id = j.id,
          label = j.label,
          description = j.description,
          cost = j.cost,
          displayOrder = j.displayOrder,
          effect = effect
        )

  private def parseEffect(e: UpgradeEffectJson): Either[String, UpgradeEffect] =
    e.`type` match
      case "MaxHpBoost" =>
        e.amount.toRight("MaxHpBoost is missing 'amount' field").map(UpgradeEffect.MaxHpBoost.apply)
      case "ExtraInventorySlot" =>
        Right(UpgradeEffect.ExtraInventorySlot)
      case "StartingItem" =>
        e.typeId.toRight("StartingItem is missing 'typeId' field").map(UpgradeEffect.StartingItem.apply)
      case "UnlockClass" =>
        e.classId
          .toRight("UnlockClass is missing 'classId' field")
          .flatMap(parseClassId)
          .map(UpgradeEffect.UnlockClass.apply)
      case other =>
        Left(s"Unknown upgrade effect type: '$other'")

  private def parseClassId(s: String): Either[String, ClassId] =
    ClassId.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown classId: '$s'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class UpgradeEffectJson(
      `type`: String,
      amount: Option[Int] = None,
      typeId: Option[String] = None,
      classId: Option[String] = None
  )

  private case class UpgradeDefJson(
      id: String,
      label: String,
      description: String,
      cost: Int,
      displayOrder: Int,
      effect: UpgradeEffectJson
  )

  private given Decoder[UpgradeEffectJson] = Decoder.instance:
    (c: HCursor) =>
      for
        t       <- c.get[String]("type")
        amount  <- c.get[Option[Int]]("amount")
        typeId  <- c.get[Option[String]]("typeId")
        classId <- c.get[Option[String]]("classId")
      yield UpgradeEffectJson(t, amount, typeId, classId)

  private given Decoder[UpgradeDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id           <- c.get[String]("id")
        label        <- c.get[String]("label")
        description  <- c.get[String]("description")
        cost         <- c.get[Int]("cost")
        displayOrder <- c.get[Int]("displayOrder")
        effect       <- c.get[UpgradeEffectJson]("effect")
      yield UpgradeDefJson(id, label, description, cost, displayOrder, effect)
