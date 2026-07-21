package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

/** Loads NPC dialogue content from `data/npcs.json` on the classpath.
  *
  * Keyed by id, matching the `id` of the corresponding [[Npc]] entity in rooms.json. Resource
  * reading and error wrapping are handled by [[JsonResourceLoader]].
  */
object NpcDialogueLoader extends JsonResourceLoader[NpcDialogueDef, String]:

  protected val resourcePath = "data/npcs.json"

  protected def keyOf(entry: NpcDialogueDef): String = entry.id

  protected def parseEntries(json: String): Either[String, List[NpcDialogueDef]] =
    decode[List[NpcDialogueDefJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toNpcDialogueDef))

  private def toNpcDialogueDef(j: NpcDialogueDefJson): Either[String, NpcDialogueDef] =
    if j.dialogue.isEmpty then Left(s"Npc '${j.id}' has an empty 'dialogue' list")
    else
      Right(
        NpcDialogueDef(id = j.id,
                       name = j.name,
                       dialogue = j.dialogue,
                       fallbackDialogue = j.fallbackDialogue.getOrElse(Nil)
        )
      )

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class NpcDialogueDefJson(
      id: String,
      name: String,
      dialogue: List[String],
      fallbackDialogue: Option[List[String]] = None
  )

  private given Decoder[NpcDialogueDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id               <- c.get[String]("id")
        name             <- c.get[String]("name")
        dialogue         <- c.get[List[String]]("dialogue")
        fallbackDialogue <- c.get[Option[List[String]]]("fallbackDialogue")
      yield NpcDialogueDefJson(id, name, dialogue, fallbackDialogue)
