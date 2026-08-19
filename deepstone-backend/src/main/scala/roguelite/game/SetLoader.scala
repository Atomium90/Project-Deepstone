package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.ClassId

/** Loads equipment set definitions from `data/sets.json` on the classpath.
  *
  * Resource reading and error wrapping are handled by [[JsonResourceLoader]].
  */
object SetLoader extends JsonResourceLoader[SetDef, String]:

  protected val resourcePath = "data/sets.json"

  protected def keyOf(entry: SetDef): String = entry.id

  protected def parseEntries(json: String): Either[String, List[SetDef]] =
    decode[List[SetDefJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toSetDef))

  private def toSetDef(j: SetDefJson): Either[String, SetDef] =
    for
      classId   <- parseClassId(j.classId)
      twoPiece  <- toSetBonus(j.twoPiece)
      fourPiece <- toSetBonus(j.fourPiece)
    yield SetDef(id = j.id, name = j.name, classId = classId, twoPiece = twoPiece, fourPiece = fourPiece)

  private def toSetBonus(j: SetBonusJson): Either[String, SetBonus] =
    parseEffect(j).map(effect => SetBonus(effect, j.label))

  private def parseClassId(s: String): Either[String, ClassId] =
    ClassId.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown classId: '$s'")

  private def parseEffect(j: SetBonusJson): Either[String, SetBonusEffect] =
    j.`type` match
      case "MaxHpPercent" =>
        j.value.toRight("MaxHpPercent is missing 'value' field").map(SetBonusEffect.MaxHpPercent.apply)
      case "FlatDefense" =>
        j.value.toRight("FlatDefense is missing 'value' field").map(SetBonusEffect.FlatDefense.apply)
      case "FlatAttack" =>
        j.value.toRight("FlatAttack is missing 'value' field").map(SetBonusEffect.FlatAttack.apply)
      case "CritChancePercent" =>
        j.value.toRight("CritChancePercent is missing 'value' field")
          .map(SetBonusEffect.CritChancePercent.apply)
      case "AttackDamagePercent" =>
        j.value.toRight("AttackDamagePercent is missing 'value' field")
          .map(SetBonusEffect.AttackDamagePercent.apply)
      case "FirstAttackAlwaysCrit" =>
        Right(SetBonusEffect.FirstAttackAlwaysCrit)
      case "AbilityCostReductionPercent" =>
        j.value.toRight("AbilityCostReductionPercent is missing 'value' field")
          .map(SetBonusEffect.AbilityCostReductionPercent.apply)
      case "HealOnKillPercent" =>
        j.value.toRight("HealOnKillPercent is missing 'value' field")
          .map(SetBonusEffect.HealOnKillPercent.apply)
      case other =>
        Left(s"Unknown set bonus effect type: '$other'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class SetBonusJson(`type`: String, label: String, value: Option[Int] = None)

  private case class SetDefJson(
      id: String,
      name: String,
      classId: String,
      twoPiece: SetBonusJson,
      fourPiece: SetBonusJson
  )

  private given Decoder[SetBonusJson] = Decoder.instance:
    (c: HCursor) =>
      for
        t     <- c.get[String]("type")
        label <- c.get[String]("label")
        value <- c.get[Option[Int]]("value")
      yield SetBonusJson(t, label, value)

  private given Decoder[SetDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id        <- c.get[String]("id")
        name      <- c.get[String]("name")
        classId   <- c.get[String]("classId")
        twoPiece  <- c.get[SetBonusJson]("twoPiece")
        fourPiece <- c.get[SetBonusJson]("fourPiece")
      yield SetDefJson(id, name, classId, twoPiece, fourPiece)
