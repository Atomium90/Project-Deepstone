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
      case other =>
        Left(s"Unknown perk effect type: '$other'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class PerkEffectJson(`type`: String, typeId: Option[String] = None)

  private case class PerkDefJson(id: String, label: String, description: String, icon: String, effect: PerkEffectJson)

  private given Decoder[PerkEffectJson] = Decoder.instance:
    (c: HCursor) =>
      for
        t      <- c.get[String]("type")
        typeId <- c.get[Option[String]]("typeId")
      yield PerkEffectJson(t, typeId)

  private given Decoder[PerkDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id          <- c.get[String]("id")
        label       <- c.get[String]("label")
        description <- c.get[String]("description")
        icon        <- c.get[String]("icon")
        effect      <- c.get[PerkEffectJson]("effect")
      yield PerkDefJson(id, label, description, icon, effect)
