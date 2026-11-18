package roguelite.sim

import roguelite.engine.{ ClassId, Difficulty }

/** A simulation of every class on every tier, for one or more save stages: the table to read first
  * to see where the game is easy, where it is hard and for whom.
  */
object SimGrid:

  /** The tiers of the grid, easiest first. */
  val Tiers: List[Difficulty] = List(Difficulty.Easy, Difficulty.Normal, Difficulty.Hard, Difficulty.Hardcore)

  final case class Cell(setup: SimSetup, result: SimResult):
    def runs: List[RunRow] = result.runs
    def wins: Int          = runs.count(_.outcome == "victory")

  /** Plays `options.runs` runs of every (profile, class, tier). Perks are not part of the grid. */
  def run(world: SimWorld, options: SimOptions, profiles: List[SimProfile]): List[Cell] =
    for
      profile <- profiles
      classId <- ClassId.values.toList
      tier    <- Tiers
      setup = SimSetup(classId, tier, profile)
    yield Cell(setup, BalanceSim.simulate(world, options, setup))

  /** The folder of a cell under the output folder, e.g. `mid/warrior-hard`. */
  def folder(setup: SimSetup): String = s"${setup.profile.label}/${setup.classId.toString.toLowerCase}-${setup.difficulty.toString.toLowerCase}"

  def render(cells: List[Cell], options: SimOptions): String =
    val profiles = cells.map(_.setup.profile).distinct
    val head =
      List("# Balance grid", "",
           s"- Runs per cell: ${options.runs}, first seed: ${options.seed} (run n uses seed + n)",
           s"- Bot: heal below ${options.policy.healBelowPercent}% HP, the other choices are those of a default bot (see any cell's report.md)",
           "- Each cell has its own folder with the three CSV tables and a full report.md", "")
    val body = profiles.flatMap:
      profile =>
        val own = cells.filter(_.setup.profile == profile)
        List(s"## ${profile.label} save", "", s"_${profile.description}_", "") ++
          List("### Wins, mean level at the end, mean section reached (1 = first)", "") ++
          grid(own)(cell => s"${SimReport.pct(cell.wins, cell.runs.size)} / L${SimReport.mean(cell.runs.map(_.level))} / S${meanSection(cell)}") ++
          List("### What kills the bot most, and how often it dies", "") ++
          grid(own)(killer)
    (head ++ body).mkString("", "\n", "\n")

  private def meanSection(cell: Cell): String =
    SimReport.mean(cell.runs.map(_.section + 1))

  private def killer(cell: Cell): String =
    val deaths = cell.runs.filter(_.outcome == "death")
    deaths.groupBy(_.killedBy).toList.sortBy((name, rs) => (-rs.size, name)).headOption match
      case None             => "no death"
      case Some((name, rs)) => s"$name ${SimReport.pct(rs.size, deaths.size)} (of ${SimReport.pct(deaths.size, cell.runs.size)} deaths)"

  /** One table: a row per class, a column per tier, `cell` writing each. */
  private def grid(cells: List[Cell])(content: Cell => String): List[String] =
    val rows = ClassId.values.toList.map:
      classId =>
        classId.toString.toLowerCase ::
          Tiers.map:
            tier => cells.find(c => c.setup.classId == classId && c.setup.difficulty == tier).fold("-")(content)
    SimReport.table("Class" :: Tiers.map(_.toString.toLowerCase), rows)
