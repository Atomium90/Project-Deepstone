package roguelite.game

import cats.effect.IO
import cats.syntax.either.*

import scala.io.Source

/** Base for objects that load a JSON array resource into a Map[K, T] at server startup.
  *
  * All game reference data (classes, enemies, items, rooms) is loaded once from a bundled
  * classpath resource and held immutable for the lifetime of the server. Concrete loaders only
  * need to provide the resource path, the JSON parsing, and the map key — this trait handles
  * reading the resource, wrapping parse failures, and building the final map.
  *
  * A malformed resource is a programming error: [[loadAll]] raises an IO failure rather than
  * silently skipping bad entries.
  *
  * @tparam T the domain type produced by this loader (e.g. [[Room]], [[Item]]).
  * @tparam K the map key type entries are indexed by (e.g. `String` typeId, [[roguelite.engine.ClassId]]).
  */
trait JsonResourceLoader[T, K]:

  /** Classpath path to the JSON resource, e.g. "data/items.json". */
  protected def resourcePath: String

  /** Parse the raw JSON text into the list of domain entries. */
  protected def parseEntries(json: String): Either[String, List[T]]

  /** The map key for one loaded entry (e.g. typeId, classId, room id). */
  protected def keyOf(entry: T): K

  /** Load all entries from the bundled JSON resource into a Map keyed by [[keyOf]]. */
  def loadAll(): IO[Map[K, T]] =
    for
      json <- readResource(resourcePath)
      entries <- IO.fromEither(
        parseEntries(json).leftMap(
          err => RuntimeException(s"Failed to parse $resourcePath: $err")
        )
      )
    yield entries
      .map(
        e => keyOf(e) -> e
      )
      .toMap

  private def readResource(path: String): IO[String] =
    IO.blocking:
      val stream = getClass.getClassLoader.getResourceAsStream(path)
      if stream == null then throw RuntimeException(s"Resource not found on classpath: $path")
      Source.fromInputStream(stream, "UTF-8").mkString

  /** Cats-style traverse for Either, shared by every loader's [[parseEntries]] implementation. */
  extension [A, B](list: List[A])
    protected def traverse(f: A => Either[String, B]): Either[String, List[B]] =
      list.foldRight(Right(Nil): Either[String, List[B]]):
        (a, acc) => for b <- f(a); rest <- acc yield b :: rest
