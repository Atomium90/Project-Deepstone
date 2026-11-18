package roguelite.game.combat

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.ClassId
import roguelite.game.core.JsonResourceLoader

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
      case "EmpowerNextAttack" =>
        e.multiplierPercent
          .toRight("EmpowerNextAttack is missing 'multiplierPercent' field")
          .map(AbilityEffect.EmpowerNextAttack(_, e.ignoreDefense.getOrElse(false), e.guaranteedCrit.getOrElse(false)))
      case "AttackScaledDamage" =>
        e.percent.toRight("AttackScaledDamage is missing 'percent' field").map(AbilityEffect.AttackScaledDamage.apply)
      case other =>
        Left(s"Unknown ability effect type: '$other'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class AbilityEffectJson(
      `type`: String,
      multiplierPercent: Option[Int] = None,
      ignoreDefense: Option[Boolean] = None,
      guaranteedCrit: Option[Boolean] = None,
      percent: Option[Int] = None
  )

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
        t              <- c.get[String]("type")
        multiplier     <- c.get[Option[Int]]("multiplierPercent")
        ignoreDefense  <- c.get[Option[Boolean]]("ignoreDefense")
        guaranteedCrit <- c.get[Option[Boolean]]("guaranteedCrit")
        percent        <- c.get[Option[Int]]("percent")
      yield AbilityEffectJson(t, multiplier, ignoreDefense, guaranteedCrit, percent)

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
