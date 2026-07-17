package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.ClassId

/** Loads class definitions from `data/classes.json` on the classpath.
  *
  * Class data is immutable reference data — read once at startup and held for the lifetime of the
  * server. Only the JSON shape and the map key are specific to this loader; resource reading and
  * error wrapping are handled by [[JsonResourceLoader]].
  */
object ClassLoader extends JsonResourceLoader[ClassDef, ClassId]:

  protected val resourcePath = "data/classes.json"

  protected def keyOf(entry: ClassDef): ClassId = entry.classId

  protected def parseEntries(json: String): Either[String, List[ClassDef]] =
    decode[List[ClassDefJson]](json)
      .leftMap(_.getMessage)
      .flatMap(
        js => js.traverse(toClassDef)
      )

  private def toClassDef(j: ClassDefJson): Either[String, ClassDef] =
    parseClassId(j.classId).map:
      classId =>
        ClassDef(
          classId = classId,
          hp = j.hp,
          resourceMax = j.resourceMax,
          resourceStart = j.resourceStart,
          affinityTags = j.affinityTags.toSet,
          startingKit = j.startingKit
        )

  private def parseClassId(s: String): Either[String, ClassId] =
    ClassId.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown classId: '$s'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class ClassDefJson(
      classId: String,
      hp: Int,
      resourceMax: Int,
      resourceStart: Int,
      affinityTags: List[String],
      startingKit: List[String]
  )

  private given Decoder[ClassDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        classId       <- c.get[String]("classId")
        hp            <- c.get[Int]("hp")
        resourceMax   <- c.get[Int]("resourceMax")
        resourceStart <- c.get[Int]("resourceStart")
        affinityTags  <- c.get[List[String]]("affinityTags")
        startingKit   <- c.get[List[String]]("startingKit")
      yield ClassDefJson(classId, hp, resourceMax, resourceStart, affinityTags, startingKit)
