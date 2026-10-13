package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.Difficulty

/** Loads the chapters from `data/chapters.json` on the classpath.
  *
  * Mirrors [[PerkLoader]]: resource reading and error wrapping are handled by
  * [[JsonResourceLoader]], this object only supplies the parsing.
  */
object ChapterLoader extends JsonResourceLoader[ChapterDef, Int]:

  protected val resourcePath = "data/chapters.json"

  protected def keyOf(entry: ChapterDef): Int = entry.id

  protected def parseEntries(json: String): Either[String, List[ChapterDef]] =
    decode[List[ChapterJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toChapter))
      .flatMap(requireConsecutiveIds)

  private def toChapter(j: ChapterJson): Either[String, ChapterDef] =
    parseDifficulty(j.minDifficulty).map(ChapterDef(j.id, _))

  /** Chapters open one after the other, so their ids must run 1, 2, 3... with no gap and no repeat. */
  private def requireConsecutiveIds(chapters: List[ChapterDef]): Either[String, List[ChapterDef]] =
    if chapters.map(_.id).sorted == (1 to chapters.size).toList then Right(chapters)
    else Left("Chapter ids must run from 1 with no gap and no repeat")

  private def parseDifficulty(s: String): Either[String, Difficulty] =
    Difficulty.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown difficulty: '$s'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class ChapterJson(id: Int, minDifficulty: String)

  private given Decoder[ChapterJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id            <- c.get[Int]("id")
        minDifficulty <- c.get[String]("minDifficulty")
      yield ChapterJson(id, minDifficulty)
