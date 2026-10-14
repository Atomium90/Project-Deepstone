package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

/** Loads enemy definitions from `data/enemies.json` on the classpath.
  *
  * Enemy data is immutable reference data: read once at startup and held for the lifetime of the
  * server. Only the JSON shape and the map key are specific to this loader; resource reading and
  * error wrapping are handled by [[JsonResourceLoader]].
  */
object EnemyLoader extends JsonResourceLoader[EnemyStats, String]:

  protected val resourcePath = "data/enemies.json"

  protected def keyOf(entry: EnemyStats): String = entry.typeId

  // ---------------------------------------------
  // JSON parsing
  // ---------------------------------------------

  protected def parseEntries(json: String): Either[String, List[EnemyStats]] =
    decode[List[EnemyStatsJson]](json)
      .leftMap(_.getMessage)
      .flatMap(
        esjs => esjs.traverse(toEnemyStats)
      )
      .flatMap(checkDevoted)

  /** Every Devoted is one enemy type: a number below 1 is no Devoted, and two types sharing a number
    * would share a name and lines.
    */
  private def checkDevoted(entries: List[EnemyStats]): Either[String, List[EnemyStats]] =
    val numbered = entries.flatMap(stats => stats.devoted.map(stats.typeId -> _))
    numbered.find((_, n) => n < 1) match
      case Some((typeId, n)) => Left(s"Enemy '$typeId' is Devoted $n, which must be 1 or more")
      case None =>
        numbered.groupBy((_, n) => n).collectFirst { case (n, group) if group.sizeIs > 1 => (n, group.map(_._1)) } match
          case Some((n, typeIds)) => Left(s"Devoted $n is given to several enemies: ${typeIds.sorted.mkString(", ")}")
          case None               => Right(entries)

  private def toEnemyStats(ej: EnemyStatsJson): Either[String, EnemyStats] =
    Right(
      EnemyStats(
        typeId = ej.typeId,
        label = ej.label,
        spriteId = ej.spriteId,
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
          ),
        devoted = ej.devoted
      )
    )

  // ---------------------------------------------
  // Internal JSON DTOs
  // ---------------------------------------------

  private case class EnemyActionWeightJson(action: String, weight: Int)

  private case class LootEntryJson(typeId: String, weight: Int)

  private case class EnemyStatsJson(
      typeId: String,
      label: String,
      spriteId: String,
      maxHp: Int,
      attack: Int,
      defense: Int,
      xpReward: Int,
      actions: List[EnemyActionWeightJson],
      dropChance: Option[Int] = None,
      lootTable: Option[List[LootEntryJson]] = None,
      devoted: Option[Int] = None
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
        spriteId   <- c.get[String]("spriteId")
        maxHp      <- c.get[Int]("maxHp")
        attack     <- c.get[Int]("attack")
        defense    <- c.get[Int]("defense")
        xpReward   <- c.get[Int]("xpReward")
        actions    <- c.get[List[EnemyActionWeightJson]]("actions")
        dropChance <- c.get[Option[Int]]("dropChance")
        lootTable  <- c.get[Option[List[LootEntryJson]]]("lootTable")
        devoted    <- c.get[Option[Int]]("devoted")
      yield EnemyStatsJson(typeId,
                           label,
                           spriteId,
                           maxHp,
                           attack,
                           defense,
                           xpReward,
                           actions,
                           dropChance,
                           lootTable,
                           devoted
      )
