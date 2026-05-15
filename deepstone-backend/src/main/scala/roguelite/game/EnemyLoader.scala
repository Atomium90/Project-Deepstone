package roguelite.game

import cats.effect.IO
import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

import scala.io.Source

/** Loads enemy definitions from `data/enemies.json` on the classpath.
  *
  * Enemy data is immutable reference data — read once at startup and held for the lifetime of the
  * server. The loader is intentionally strict: a malformed enemies.json is a programming error, so
  * it raises an IO failure rather than silently skipping bad entries.
  */
object EnemyLoader:

  private val EnemiesResourcePath = "data/enemies.json"

  /** Load all enemy definitions from the bundled JSON resource.
    *
    * @return
    *   A map from typeId → [[EnemyStats]], wrapped in IO.
    */
  def loadAll(): IO[Map[String, EnemyStats]] =
    for
      json <- readResource(EnemiesResourcePath)
      enemies <- IO.fromEither(
        parseEnemies(json).leftMap(
          err => RuntimeException(s"Failed to parse enemies.json: $err")
        )
      )
    yield enemies
      .map(
        e => e.typeId -> e
      )
      .toMap

  // ---------------------------------------------
  // JSON parsing
  // ---------------------------------------------

  private def parseEnemies(json: String): Either[String, List[EnemyStats]] =
    decode[List[EnemyStatsJson]](json)
      .leftMap(_.getMessage)
      .flatMap(
        esjs => esjs.traverse(toEnemyStats)
      )

  private def toEnemyStats(ej: EnemyStatsJson): Either[String, EnemyStats] =
    Right(
      EnemyStats(
        typeId = ej.typeId,
        label = ej.label,
        maxHp = ej.maxHp,
        attack = ej.attack,
        defense = ej.defense,
        xpReward = ej.xpReward,
        actions = ej.actions.map(
          a => EnemyActionWeight(a.action, a.weight)
        ),
        dropChance = ej.dropChance.getOrElse(0),
        lootTable = ej.lootTable
          .getOrElse(Nil)
          .map(
            l => LootEntry(l.typeId, l.weight)
          )
      )
    )

  private def readResource(path: String): IO[String] =
    IO.blocking:
      val stream = getClass.getClassLoader.getResourceAsStream(path)
      if stream == null then throw RuntimeException(s"Resource not found on classpath: $path")
      Source.fromInputStream(stream, "UTF-8").mkString

  // ---------------------------------------------
  // Internal JSON DTOs
  // ---------------------------------------------

  private case class EnemyActionWeightJson(action: String, weight: Int)

  private case class LootEntryJson(typeId: String, weight: Int)

  private case class EnemyStatsJson(
      typeId: String,
      label: String,
      maxHp: Int,
      attack: Int,
      defense: Int,
      xpReward: Int,
      actions: List[EnemyActionWeightJson],
      dropChance: Option[Int] = None,
      lootTable: Option[List[LootEntryJson]] = None
  )

  // Circe decoders for internal DTOs
  private given Decoder[EnemyActionWeightJson] = Decoder.instance:
    (c: HCursor) =>
      for
        action <- c.get[String]("action")
        weight <- c.get[Int]("weight")
      yield EnemyActionWeightJson(action, weight)

  private given Decoder[LootEntryJson] = Decoder.instance:
    (c: HCursor) =>
      for
        typeId <- c.get[String]("typeId")
        weight <- c.get[Int]("weight")
      yield LootEntryJson(typeId, weight)

  private given Decoder[EnemyStatsJson] = Decoder.instance:
    (c: HCursor) =>
      for
        typeId     <- c.get[String]("typeId")
        label      <- c.get[String]("label")
        maxHp      <- c.get[Int]("maxHp")
        attack     <- c.get[Int]("attack")
        defense    <- c.get[Int]("defense")
        xpReward   <- c.get[Int]("xpReward")
        actions    <- c.get[List[EnemyActionWeightJson]]("actions")
        dropChance <- c.get[Option[Int]]("dropChance")
        lootTable  <- c.get[Option[List[LootEntryJson]]]("lootTable")
      yield EnemyStatsJson(typeId,
                           label,
                           maxHp,
                           attack,
                           defense,
                           xpReward,
                           actions,
                           dropChance,
                           lootTable
      )

  // Cats traverse helper for Either (same pattern as RoomLoader)
  extension [A, B](list: List[A])
    private def traverse(f: A => Either[String, B]): Either[String, List[B]] =
      list.foldRight(Right(Nil): Either[String, List[B]]):
        (a, acc) => for b <- f(a); rest <- acc yield b :: rest
