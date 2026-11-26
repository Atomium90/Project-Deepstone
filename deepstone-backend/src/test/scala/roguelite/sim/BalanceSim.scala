package roguelite.sim

import cats.effect.{ ExitCode, IO }
import roguelite.engine.{ ClassId, Difficulty }

import java.nio.file.{ Path, Paths }

/** Command-line options of the balance simulation, see [[BalanceSim]].
  *
  * @param difficulty
  *   The tier to play, or None for the one a save at the profile's stage would be playing.
  */
final case class SimOptions(
    runs: Int = 200,
    seed: Long = 1L,
    classId: ClassId = ClassId.Warrior,
    difficulty: Option[Difficulty] = None,
    profile: SimProfile = SimProfile.Fresh,
    perkId: Option[String] = None,
    riskyPerkId: Option[String] = None,
    grid: Boolean = false,
    allProfiles: Boolean = false,
    campaign: Boolean = false,
    campaigns: Int = 30,
    campaignRuns: Int = 80,
    compare: Option[(Path, Path)] = None,
    out: Path = Paths.get("target", "sim"),
    policy: BotPolicy = BotPolicy()
):

  /** The save stages a grid plays: every one with `--profile all`, else the chosen one. */
  def gridProfiles: List[SimProfile] = if allProfiles then SimProfile.values.toList else List(profile)

  /** What each run of this simulation plays. */
  def setup: SimSetup =
    SimSetup(classId, difficulty.getOrElse(profile.defaultDifficulty), profile, perkId, riskyPerkId)

object SimOptions:

  /** Reads `--runs N --seed S --class warrior|archer|mage --difficulty easy|normal|hard|hardcore
    * --profile fresh|early|mid|late|post|all --perk ID --risky ID --heal PERCENT --policy asap|when-long --order chests-first|fights-first --grid --out DIR`.
    * `--grid` plays every class on every tier (so `--class` and `--difficulty` are not read) and
    * `--profile all` is only for a grid. `--campaign [--campaigns N] [--campaign-runs N]` lives
    * whole saves instead, run after run (see [[Campaign]]): only `--class`, `--seed`, `--heal` and
    * `--out` count then. `--compare BEFORE AFTER` reads two folders written by earlier simulations
    * (one simulation each, or two grids) and writes `compare.md` to `--out` (see [[SimCompare]]).
    */
  def parse(args: List[String]): Either[String, SimOptions] =
    def go(rest: List[String], options: SimOptions): Either[String, SimOptions] = rest match
      case Nil if options.allProfiles && !options.grid => Left("--profile all only makes sense with --grid")
      case Nil                                         => Right(options)
      case "--grid" :: tail                            => go(tail, options.copy(grid = true))
      case "--compare" :: before :: after :: tail      => go(tail, options.copy(compare = Some((Paths.get(before), Paths.get(after)))))
      case "--campaign" :: tail                        => go(tail, options.copy(campaign = true))
      case "--campaigns" :: v :: tail                  => int(v, "--campaigns").flatMap(n => go(tail, options.copy(campaigns = n)))
      case "--campaign-runs" :: v :: tail              => int(v, "--campaign-runs").flatMap(n => go(tail, options.copy(campaignRuns = n)))
      case "--profile" :: "all" :: tail                => go(tail, options.copy(allProfiles = true))
      case "--runs" :: v :: tail       => int(v, "--runs").flatMap(n => go(tail, options.copy(runs = n)))
      case "--seed" :: v :: tail       => v.toLongOption.toRight(s"--seed wants a number, got '$v'").flatMap(s => go(tail, options.copy(seed = s)))
      case "--class" :: v :: tail      => classOf(v).flatMap(c => go(tail, options.copy(classId = c)))
      case "--difficulty" :: v :: tail => difficultyOf(v).flatMap(d => go(tail, options.copy(difficulty = Some(d))))
      case "--profile" :: v :: tail    => SimProfile.parse(v).flatMap(p => go(tail, options.copy(profile = p)))
      case "--perk" :: v :: tail       => go(tail, options.copy(perkId = Some(v)))
      case "--risky" :: v :: tail      => go(tail, options.copy(riskyPerkId = Some(v)))
      case "--policy" :: v :: tail =>
        AbilityRule.parse(v).flatMap(r => go(tail, options.copy(policy = options.policy.copy(abilityRule = r))))
      case "--order" :: v :: tail =>
        v.toLowerCase match
          case "chests-first" => go(tail, options.copy(policy = options.policy.copy(chestsFirst = true)))
          case "fights-first" => go(tail, options.copy(policy = options.policy.copy(chestsFirst = false)))
          case other          => Left(s"--order wants chests-first or fights-first, got '$other'")
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
      case Right(options) if options.compare.isDefined =>
        val (beforeDir, afterDir) = options.compare.get
        IO.blocking(compareFolders(beforeDir, afterDir, options.out)).flatMap:
          case Left(error) => IO.println(error).as(ExitCode.Error)
          case Right(file) => IO.println(s"Comparison written to ${file.toAbsolutePath}").as(ExitCode.Success)

      case Right(options) if options.campaign =>
        SimWorld.load().flatMap:
          world =>
            for
              steps <- IO.blocking(simulateCampaigns(world, options))
              _ <- IO.blocking:
                CampaignReport.writeCsv(options.out, steps)
                java.nio.file.Files.writeString(options.out.resolve("campaign.md"), CampaignReport.render(world, steps, options))
              _ <- IO.println(s"${options.campaigns} saves of ${options.campaignRuns} runs written to ${options.out.toAbsolutePath} (see campaign.md)")
            yield ExitCode.Success

      case Right(options) if options.grid =>
        SimWorld.load().flatMap:
          world =>
            for
              cells <- IO.blocking(SimGrid.run(world, options, options.gridProfiles))
              _ <- IO.blocking:
                cells.foreach:
                  cell =>
                    val dir = options.out.resolve(SimGrid.folder(cell.setup))
                    SimCsv.write(dir, cell.result)
                    java.nio.file.Files.writeString(dir.resolve("report.md"), SimReport.render(cell.result, header(options, cell.setup)))
                java.nio.file.Files.writeString(options.out.resolve("grid.md"), SimGrid.render(cells, options))
              _ <- IO.println(s"${cells.size} cells of ${options.runs} runs written to ${options.out.toAbsolutePath} (see grid.md)")
            yield ExitCode.Success

      case Right(options) =>
        SimWorld.load().flatMap:
          world =>
            options.setup.validate(world) match
              case Left(error) => IO.println(error).as(ExitCode.Error)
              case Right(setup) =>
                for
                  result <- IO.blocking(simulate(world, options, setup))
                  _ <- IO.blocking:
                    SimCsv.write(options.out, result)
                    java.nio.file.Files.writeString(options.out.resolve("report.md"),
                                                    SimReport.render(result, header(options, setup))
                    )
                  _ <- IO.println(s"${result.runs.size} runs written to ${options.out.toAbsolutePath}")
                yield ExitCode.Success

  /** `options.runs` runs of `setup`, run n with seed `options.seed + n`. */
  def simulate(world: SimWorld, options: SimOptions, setup: SimSetup): SimResult =
    val simulator = RunSimulator(world, options.policy)
    val played =
      (0 until options.runs).toList.map:
        i => simulator.play(i, options.seed + i, setup)
    SimResult(played.map(_.row), played.flatMap(_.combats), played.flatMap(_.drops))

  def simulate(world: SimWorld, options: SimOptions): SimResult = simulate(world, options, options.setup)

  /** Writes `compare.md` under `out` for two folders of simulations, or says what is wrong with them. */
  def compareFolders(beforeDir: Path, afterDir: Path, out: Path): Either[String, Path] =
    def check(dir: Path): Either[String, Map[String, SimResult]] =
      if !java.nio.file.Files.isDirectory(dir) then Left(s"Not a folder: $dir")
      else
        val found = SimCompare.results(dir)
        if found.isEmpty then Left(s"No simulation (runs.csv, combats.csv, drops.csv) under $dir") else Right(found)
    for
      before <- check(beforeDir)
      after  <- check(afterDir)
    yield
      java.nio.file.Files.createDirectories(out)
      val file = out.resolve("compare.md")
      java.nio.file.Files.writeString(file, SimCompare.renderFolders(before, after, (beforeDir.toString, afterDir.toString)))
      file

  /** `options.campaigns` saves of `options.campaignRuns` runs each (see [[Campaign]]). */
  def simulateCampaigns(world: SimWorld, options: SimOptions): List[Campaign.Step] =
    (0 until options.campaigns).toList.flatMap:
      c => Campaign.play(world, options.policy, options.classId, c, options.campaignRuns, options.seed)

  private def header(o: SimOptions, setup: SimSetup): List[String] =
    List(
      s"Played: ${setup.describe}",
      s"Runs: ${o.runs}, first seed: ${o.seed} (run n uses seed + n)",
      s"Bot: ability ${if o.policy.abilityRule == AbilityRule.Asap then "as soon as it can be paid" else "when the fight will last"}, attack otherwise, heal below ${o.policy.healBelowPercent}% HP, " +
        s"power potions in Elite/MiniBoss/Boss fights: ${o.policy.usePowerPotions}, clears every room: ${o.policy.clearRooms}, " +
        s"opens every chest: ${o.policy.openChests} (${if o.policy.chestsFirst then "before" else "after"} the fights), takes the MiniBoss branch: ${o.policy.takeMiniBossBranch}"
    )
