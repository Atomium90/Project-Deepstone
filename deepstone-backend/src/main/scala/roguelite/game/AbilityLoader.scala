package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.ClassId

/** Loads ability definitions from `data/abilities.json` on the classpath.
  *
  * Keyed by [[ClassId]] since every class has exactly one ability in V1. Resource reading and
  * error wrapping are handled by [[JsonResourceLoader]].
  */
object AbilityLoader extends JsonResourceLoader[AbilityDef, ClassId]:

  protected val resourcePath = "data/abilities.json"

  protected def keyOf(entry: AbilityDef): ClassId = entry.classId

  protected def parseEntries(json: String): Either[String, List[AbilityDef]] =
    decode[List[AbilityDefJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toAbilityDef))

  private def toAbilityDef(j: AbilityDefJson): Either[String, AbilityDef] =
    for
      classId <- parseClassId(j.classId)
      effect  <- parseEffect(j.effect)
    yield AbilityDef(
      classId = classId,
      id = j.id,
      name = j.name,
      cost = j.cost,
      resourceName = j.resourceName,
      description = j.description,
      effect = effect
    )

  private def parseClassId(s: String): Either[String, ClassId] =
    ClassId.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown classId: '$s'")

  private def parseEffect(e: AbilityEffectJson): Either[String, AbilityEffect] =
    e.`type` match
      case "DoubleNextAttack"        => Right(AbilityEffect.DoubleNextAttack)
      case "IgnoreDefenseNextAttack" => Right(AbilityEffect.IgnoreDefenseNextAttack)
      case "FlatDamage" =>
        e.amount.toRight("FlatDamage is missing 'amount' field").map(AbilityEffect.FlatDamage.apply)
      case other =>
        Left(s"Unknown ability effect type: '$other'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class AbilityEffectJson(`type`: String, amount: Option[Int] = None)

  private case class AbilityDefJson(
      classId: String,
      id: String,
      name: String,
      cost: Int,
      resourceName: String,
      description: String,
      effect: AbilityEffectJson
  )

  private given Decoder[AbilityEffectJson] = Decoder.instance:
    (c: HCursor) =>
      for
        t      <- c.get[String]("type")
        amount <- c.get[Option[Int]]("amount")
      yield AbilityEffectJson(t, amount)

  private given Decoder[AbilityDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        classId      <- c.get[String]("classId")
        id           <- c.get[String]("id")
        name         <- c.get[String]("name")
        cost         <- c.get[Int]("cost")
        resourceName <- c.get[String]("resourceName")
        description  <- c.get[String]("description")
        effect       <- c.get[AbilityEffectJson]("effect")
      yield AbilityDefJson(classId, id, name, cost, resourceName, description, effect)
