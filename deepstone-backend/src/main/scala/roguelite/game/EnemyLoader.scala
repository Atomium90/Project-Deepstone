package roguelite.game

import cats.effect.IO
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

import scala.io.Source

/** Loads enemy type definitions from the JSON data file at startup.
  *
  * Follows the same pattern as [[RoomLoader]]: read once, fail fast on bad data, return an
  * immutable map used for the lifetime of the server.
  */
object EnemyLoader:

  private val EnemiesResourcePath = "data/enemies.json"

  /** Load all enemy types from the bundled JSON resource.
    *
    * @return
    *   A map from typeId to [[EnemyStats]], wrapped in IO.
    */
  def loadAll(): IO[Map[String, EnemyStats]] =
    for
      json <- readResource(EnemiesResourcePath)
      stats <- IO.fromEither(
        decode[List[EnemyStatsJson]](json).left.map(
          err => RuntimeException(s"Failed to parse enemies.json: ${err.getMessage}")
        )
      )
    yield stats
      .map(toStats)
      .map(
        s => s.typeId -> s
      )
      .toMap

  // ---------------------------
  // JSON parsing
  // ---------------------------

  private def toStats(j: EnemyStatsJson): EnemyStats =
    EnemyStats(
      typeId = j.typeId,
      label = j.label,
      maxHp = j.maxHp,
      attack = j.attack,
      defense = j.defense,
      xpReward = j.xpReward,
      actions = j.actions.map(
        a => EnemyActionWeight(a.action, a.weight)
      )
    )

  private def readResource(path: String): IO[String] =
    IO.blocking:
      val stream = getClass.getClassLoader.getResourceAsStream(path)
      if stream == null then throw RuntimeException(s"Resource not found on classpath: $path")
      Source.fromInputStream(stream, "UTF-8").mkString

  // --------------------------
  // Internal JSON DTOs
  // --------------------------
  private case class EnemyActionJson(action: String, weight: Int)

  private case class EnemyStatsJson(
      typeId: String,
      label: String,
      maxHp: Int,
      attack: Int,
      defense: Int,
      xpReward: Int,
      actions: List[EnemyActionJson]
  )

  private given Decoder[EnemyActionJson] = Decoder.instance:
    (c: HCursor) =>
      for
        action <- c.get[String]("action")
        weight <- c.get[Int]("weight")
      yield EnemyActionJson(action, weight)

  private given Decoder[EnemyStatsJson] = Decoder.instance:
    (c: HCursor) =>
      for
        typeId   <- c.get[String]("typeId")
        label    <- c.get[String]("label")
        maxHp    <- c.get[Int]("maxHp")
        attack   <- c.get[Int]("attack")
        defense  <- c.get[Int]("defense")
        xpReward <- c.get[Int]("xpReward")
        actions  <- c.get[List[EnemyActionJson]]("actions")
      yield EnemyStatsJson(typeId, label, maxHp, attack, defense, xpReward, actions)
