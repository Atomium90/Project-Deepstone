package roguelite.sim

import roguelite.game.core.GameEvent

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters.*

/** Where a run stood when something first happened: how much had been opened, killed and dropped. */
final case class Milestone(chests: Int, kills: Int, drops: Int)

object Milestone:
  /** In the order the columns are written. */
  val Names: List[String] = List("rare", "epic", "setPiece", "ownSetPiece", "set2pc", "set4pc")

/** One fight, from the moment the player engages to the moment it ends. */
final case class CombatRow(
    run: Int,
    section: Int,
    roomType: String,
    kind: String, // normal | elite | miniboss | boss
    enemy: String,
    won: Boolean,
    turns: Int,
    level: Int,
    attack: Int,
    defense: Int,
    maxHp: Int,
    hpBefore: Int,
    hpAfter: Int,
    damageDealt: Int,
    damageTaken: Int,
    crits: Int,
    potions: Int,
    abilities: Int
)

/** One item the game handed out (a chest, an enemy, a Shrine pick).
  *
  * `rarity` is empty when the game kept no trace of it: a duplicate thrown away because the player
  * already held an equal or better copy, or one more charge added to a potion stack.
  */
final case class DropRow(
    run: Int,
    section: Int,
    source: String, // chest | enemy | shrine
    typeId: String,
    kind: String,
    rarity: String,
    setId: String,
    outcome: String, // equipped | choice | discarded
    level: Int,
    chestsOpened: Int,
    kills: Int,
    drops: Int
)

/** One whole run. */
final case class RunRow(
    run: Int,
    seed: Long,
    classId: String,
    difficulty: String,
    profile: String,
    outcome: String, // victory | death | stuck | cap
    killedBy: String,
    section: Int,
    roomsVisited: Int,
    level: Int,
    shards: Int,
    actions: Int,
    combats: Int,
    kills: Int,
    eliteKills: Int,
    chestsOpened: Int,
    drops: Int,
    potionsUsed: Int,
    maxHp: Int,
    attack: Int,
    defense: Int,
    milestones: Map[String, Milestone]
)

/** One played run: its rows, and every game event it produced (what the save's stats are worked out from). */
final case class PlayedRun(row: RunRow, combats: List[CombatRow], drops: List[DropRow], events: List[GameEvent])

/** The three tables of one simulation. */
final case class SimResult(runs: List[RunRow], combats: List[CombatRow], drops: List[DropRow])

object SimCsv:

  def write(dir: Path, result: SimResult): Unit =
    Files.createDirectories(dir)
    put(dir.resolve("runs.csv"), runHeader, result.runs.map(runCells))
    put(dir.resolve("combats.csv"), combatHeader, result.combats.map(combatCells))
    put(dir.resolve("drops.csv"), dropHeader, result.drops.map(dropCells))

  /** The three tables written by [[write]], read back: what a comparison of two simulations works from. */
  def read(dir: Path): SimResult =
    SimResult(table(dir.resolve("runs.csv")).map(toRun),
              table(dir.resolve("combats.csv")).map(toCombat),
              table(dir.resolve("drops.csv")).map(toDrop)
    )

  /** Whether `dir` holds the tables of one simulation. */
  def isResult(dir: Path): Boolean = Files.exists(dir.resolve("runs.csv")) && Files.exists(dir.resolve("combats.csv")) && Files.exists(dir.resolve("drops.csv"))

  private type Cells = Map[String, String]

  private def table(path: Path): List[Cells] =
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList.filter(_.nonEmpty)
    lines match
      case Nil => Nil
      case headerLine :: rows =>
        val header = headerLine.split(",", -1).toList
        rows.map(row => header.zip(row.split(",", -1).toList).toMap)

  private def toRun(c: Cells): RunRow =
    val milestones = Milestone.Names.flatMap:
      name =>
        if c(s"${name}_chests").isEmpty then None
        else Some(name -> Milestone(c(s"${name}_chests").toInt, c(s"${name}_kills").toInt, c(s"${name}_drops").toInt))
    RunRow(c("run").toInt, c("seed").toLong, c("class"), c("difficulty"), c("profile"), c("outcome"), c("killedBy"),
           c("section").toInt, c("roomsVisited").toInt, c("level").toInt, c("shards").toInt, c("actions").toInt,
           c("combats").toInt, c("kills").toInt, c("eliteKills").toInt, c("chestsOpened").toInt, c("drops").toInt,
           c("potionsUsed").toInt, c("maxHp").toInt, c("attack").toInt, c("defense").toInt, milestones.toMap
    )

  private def toCombat(c: Cells): CombatRow =
    CombatRow(c("run").toInt, c("section").toInt, c("roomType"), c("kind"), c("enemy"), c("won").toBoolean,
              c("turns").toInt, c("level").toInt, c("attack").toInt, c("defense").toInt, c("maxHp").toInt,
              c("hpBefore").toInt, c("hpAfter").toInt, c("damageDealt").toInt, c("damageTaken").toInt,
              c("crits").toInt, c("potions").toInt, c("abilities").toInt
    )

  private def toDrop(c: Cells): DropRow =
    DropRow(c("run").toInt, c("section").toInt, c("source"), c("typeId"), c("kind"), c("rarity"), c("setId"),
            c("outcome"), c("level").toInt, c("chestsOpened").toInt, c("kills").toInt, c("drops").toInt
    )

  private def put(path: Path, header: List[String], rows: List[List[Any]]): Unit =
    val text = (header :: rows.map(_.map(_.toString))).map(_.mkString(",")).mkString("", "\n", "\n")
    Files.writeString(path, text, StandardCharsets.UTF_8)

  private val combatHeader = List("run", "section", "roomType", "kind", "enemy", "won", "turns", "level", "attack",
                                  "defense", "maxHp", "hpBefore", "hpAfter", "damageDealt", "damageTaken", "crits",
                                  "potions", "abilities")

  private def combatCells(c: CombatRow): List[Any] =
    List(c.run, c.section, c.roomType, c.kind, c.enemy, c.won, c.turns, c.level, c.attack, c.defense, c.maxHp,
         c.hpBefore, c.hpAfter, c.damageDealt, c.damageTaken, c.crits, c.potions, c.abilities)

  private val dropHeader = List("run", "section", "source", "typeId", "kind", "rarity", "setId", "outcome", "level",
                                "chestsOpened", "kills", "drops")

  private def dropCells(d: DropRow): List[Any] =
    List(d.run, d.section, d.source, d.typeId, d.kind, d.rarity, d.setId, d.outcome, d.level, d.chestsOpened,
         d.kills, d.drops)

  private val runHeader =
    List("run", "seed", "class", "difficulty", "profile", "outcome", "killedBy", "section", "roomsVisited", "level", "shards",
         "actions", "combats", "kills", "eliteKills", "chestsOpened", "drops", "potionsUsed", "maxHp", "attack",
         "defense") ++ Milestone.Names.flatMap(n => List(s"${n}_chests", s"${n}_kills", s"${n}_drops"))

  private def runCells(r: RunRow): List[Any] =
    List(r.run, r.seed, r.classId, r.difficulty, r.profile, r.outcome, r.killedBy, r.section, r.roomsVisited, r.level, r.shards,
         r.actions, r.combats, r.kills, r.eliteKills, r.chestsOpened, r.drops, r.potionsUsed, r.maxHp, r.attack,
         r.defense) ++
      Milestone.Names.flatMap:
        name =>
          r.milestones.get(name) match
            case Some(m) => List(m.chests, m.kills, m.drops)
            case None    => List("", "", "")
