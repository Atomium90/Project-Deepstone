package roguelite.sim

import cats.effect.{ ExitCode, IO }
import roguelite.engine.{ ClassId, Difficulty }

import java.nio.file.{ Path, Paths }

/** Command-line options of the balance simulation, see [[BalanceSim]]. */
final case class SimOptions(
    runs: Int = 200,
    seed: Long = 1L,
    classId: ClassId = ClassId.Warrior,
    difficulty: Difficulty = Difficulty.Easy,
    out: Path = Paths.get("target", "sim"),
    policy: BotPolicy = BotPolicy()
)

object SimOptions:

  /** Reads `--runs N --seed S --class warrior|archer|mage --difficulty easy|normal|hard --heal PERCENT --out DIR`. */
  def parse(args: List[String]): Either[String, SimOptions] =
    def go(rest: List[String], options: SimOptions): Either[String, SimOptions] = rest match
      case Nil => Right(options)
      case "--runs" :: v :: tail       => int(v, "--runs").flatMap(n => go(tail, options.copy(runs = n)))
      case "--seed" :: v :: tail       => v.toLongOption.toRight(s"--seed wants a number, got '$v'").flatMap(s => go(tail, options.copy(seed = s)))
      case "--class" :: v :: tail      => classOf(v).flatMap(c => go(tail, options.copy(classId = c)))
      case "--difficulty" :: v :: tail => difficultyOf(v).flatMap(d => go(tail, options.copy(difficulty = d)))
      case "--heal" :: v :: tail =>
        int(v, "--heal").flatMap(n => go(tail, options.copy(policy = options.policy.copy(healBelowPercent = n))))
      case "--out" :: v :: tail => go(tail, options.copy(out = Paths.get(v)))
      case other :: _           => Left(s"Unknown or incomplete option: $other")
    go(args, SimOptions())

  private def int(v: String, name: String): Either[String, Int] = v.toIntOption.toRight(s"$name wants a number, got '$v'")

  private def classOf(v: String): Either[String, ClassId] =
    ClassId.values.find(_.toString.equalsIgnoreCase(v)).toRight(s"Unknown class '$v'")

  private def difficultyOf(v: String): Either[String, Difficulty] =
    Difficulty.values.find(_.toString.equalsIgnoreCase(v)).toRight(s"Unknown difficulty '$v'")

/** Plays many runs with a simple bot and writes what happened (CSV tables and a Markdown report) to
  * `target/sim/`. Run it from `deepstone-backend/`:
  *
  * {{{
  * sbt "Test/runMain roguelite.sim.BalanceSim --runs 200 --seed 1"
  * }}}
  *
  * The same options always give the same files: every random draw of a run comes from `seed + run`.
  */
object BalanceSim:

  /** A plain `main` rather than an `IOApp`: sbt runs it in its own JVM thread, which `IOApp` warns about. */
  def main(args: Array[String]): Unit =
    import cats.effect.unsafe.implicits.global
    if run(args.toList).unsafeRunSync() != ExitCode.Success then sys.exit(1)

  def run(args: List[String]): IO[ExitCode] =
    SimOptions.parse(args) match
      case Left(error) => IO.println(error).as(ExitCode.Error)
      case Right(options) =>
        for
          world  <- SimWorld.load()
          result <- IO.blocking(simulate(world, options))
          _ <- IO.blocking:
            SimCsv.write(options.out, result)
            java.nio.file.Files.writeString(options.out.resolve("report.md"), SimReport.render(result, header(options)))
          _ <- IO.println(s"${result.runs.size} runs written to ${options.out.toAbsolutePath}")
        yield ExitCode.Success

  def simulate(world: SimWorld, options: SimOptions): SimResult =
    val simulator = RunSimulator(world, options.policy)
    val played =
      (0 until options.runs).toList.map:
        i => simulator.play(i, options.seed + i, options.classId, options.difficulty)
    SimResult(played.map(_._1), played.flatMap(_._2), played.flatMap(_._3))

  private def header(o: SimOptions): List[String] =
    List(
      s"Class: ${o.classId.toString.toLowerCase}, difficulty: ${o.difficulty.toString.toLowerCase}, fresh save (no upgrade, no starting kit)",
      s"Runs: ${o.runs}, first seed: ${o.seed} (run n uses seed + n)",
      s"Bot: ability as soon as it can be paid, attack otherwise, heal below ${o.policy.healBelowPercent}% HP, " +
        s"power potions in Elite/MiniBoss/Boss fights: ${o.policy.usePowerPotions}, clears every room: ${o.policy.clearRooms}, " +
        s"opens every chest: ${o.policy.openChests}, takes the MiniBoss branch: ${o.policy.takeMiniBossBranch}"
    )
