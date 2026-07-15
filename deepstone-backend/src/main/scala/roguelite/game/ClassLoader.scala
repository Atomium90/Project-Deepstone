package roguelite.game

import cats.effect.IO
import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.ClassId

import scala.io.Source

/** Loads class definitions from `data/classes.json` on the classpath.
  *
  * Class data is immutable reference data — read once at startup and held for the lifetime of the
  * server. A malformed classes.json is a programming error, so the loader raises an IO failure
  * rather than silently skipping bad entries.
  *
  * Follows the same pattern as [[ItemLoader]]: private JSON DTOs, explicit `toClassDef` conversion
  * returning Either, readResource with IO.blocking, and a traverse extension for Either chaining.
  */
object ClassLoader:
  private val ClassesResourcePath = "data/classes.json"

  def loadAll(): IO[Map[ClassId, ClassDef]] =
    for
      json <- readResource(ClassesResourcePath)
      classes <- IO.fromEither(
        parseClasses(json).leftMap(
          err => RuntimeException(s"Failed to parse classes.json: $err")
        )
      )
    yield classes
      .map(
        c => c.classId -> c
      )
      .toMap

  // -----------------------------------------------------------------------
  // JSON parsing
  // -----------------------------------------------------------------------

  private def parseClasses(json: String): Either[String, List[ClassDef]] =
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

  private def readResource(path: String): IO[String] =
    IO.blocking:
      val stream = getClass.getClassLoader.getResourceAsStream(path)
      if stream == null then throw RuntimeException(s"Resource not found on classpath: $path")
      Source.fromInputStream(stream, "UTF-8").mkString

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

  extension [A, B](list: List[A])
    private def traverse(f: A => Either[String, B]): Either[String, List[B]] =
      list.foldRight(Right(Nil): Either[String, List[B]]):
        (a, acc) => for b <- f(a); rest <- acc yield b :: rest
