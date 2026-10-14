package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

/** Loads the NPCs of the Rest rooms from `data/npcs.json` on the classpath.
  *
  * Mirrors [[ChapterLoader]]: resource reading and error wrapping are handled by
  * [[JsonResourceLoader]], this object only supplies the parsing.
  */
object NpcLoader extends JsonResourceLoader[NpcDef, String]:

  protected val resourcePath = "data/npcs.json"

  protected def keyOf(entry: NpcDef): String = entry.id

  protected def parseEntries(json: String): Either[String, List[NpcDef]] =
    decode[List[NpcJson]](json)
      .leftMap(_.getMessage)
      .map(js => js.map(j => NpcDef(j.id, j.spriteId, j.revealedBy, j.candidate.getOrElse(false), j.epilogue.getOrElse(false))))
      .flatMap(requireDistinctIds)

  /** Two entries with one id would silently shadow each other in the catalog. */
  private def requireDistinctIds(npcs: List[NpcDef]): Either[String, List[NpcDef]] =
    npcs.groupBy(_.id).collectFirst { case (id, group) if group.sizeIs > 1 => id } match
      case Some(id) => Left(s"NPC '$id' is defined more than once")
      case None     => Right(npcs)

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class NpcJson(
      id: String,
      spriteId: String,
      revealedBy: Option[String],
      candidate: Option[Boolean],
      epilogue: Option[Boolean]
  )

  private given Decoder[NpcJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id         <- c.get[String]("id")
        spriteId   <- c.get[String]("spriteId")
        revealedBy <- c.get[Option[String]]("revealedBy")
        candidate  <- c.get[Option[Boolean]]("candidate")
        epilogue   <- c.get[Option[Boolean]]("epilogue")
      yield NpcJson(id, spriteId, revealedBy, candidate, epilogue)
