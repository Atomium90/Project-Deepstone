package roguelite.sim

import java.nio.file.{ Files, Path }
import java.util.Locale
import scala.jdk.CollectionConverters.*

/** Says what a balance change did, from two simulations of the same thing run before and after it.
  *
  * Every figure is compared with the usual test for it (a difference of two rates, a difference of two
  * means) and flagged when it is too big to be luck: `**` for a difference that would happen less
  * than 1 time in 100 by chance, `*` for less than 1 in 20. With dozens of rows on a page a few `*`
  * are chance whatever happened, so trust the `**`, the biggest changes and rows with many samples.
  *
  * Run the same simulation (same options, same seed) on both sides, so that the only difference is the
  * change under test.
  */
object SimCompare:

  /** One thing measured on both sides. `z` is how many standard errors apart the two sides are
    * (positive: it went up).
    */
  final case class Change(group: String, label: String, before: String, after: String, delta: String, z: Double):
    def flag: String = if z.abs >= 2.58 then "**" else if z.abs >= 1.96 then "*" else ""

  /** The tests behind the flags. */
  object Stats:

    def mean(xs: Seq[Double]): Double = if xs.isEmpty then 0.0 else xs.sum / xs.size

    /** Sample variance, 0 for fewer than two samples. */
    def variance(xs: Seq[Double]): Double =
      if xs.size < 2 then 0.0
      else
        val m = mean(xs)
        xs.map(x => (x - m) * (x - m)).sum / (xs.size - 1)

    /** Difference of two means, in standard errors (Welch). A difference between two sides that never
      * vary at all is as certain as it gets, hence 99.
      */
    def meanZ(before: Seq[Double], after: Seq[Double]): Double =
      if before.size < 2 || after.size < 2 then 0.0
      else
        val diff = mean(after) - mean(before)
        val se   = math.sqrt(variance(before) / before.size + variance(after) / after.size)
        if se == 0.0 then (if diff == 0.0 then 0.0 else math.signum(diff) * 99.0) else diff / se

    /** Difference of two rates (`hits` out of `n`), in standard errors, from the pooled rate. */
    def rateZ(hitsBefore: Int, nBefore: Int, hitsAfter: Int, nAfter: Int): Double =
      if nBefore == 0 || nAfter == 0 then 0.0
      else
        val pooled = (hitsBefore + hitsAfter).toDouble / (nBefore + nAfter)
        val se     = math.sqrt(pooled * (1 - pooled) * (1.0 / nBefore + 1.0 / nAfter))
        val diff   = hitsAfter.toDouble / nAfter - hitsBefore.toDouble / nBefore
        if se == 0.0 then 0.0 else diff / se

  // -- formatting ----------------------------------------------------------------------------------

  private def one(value: Double): String = String.format(Locale.ROOT, "%.1f", Double.box(value))

  private def signed(value: Double, unit: String = ""): String =
    val text = one(value)
    (if value > 0 && text != "0.0" then "+" else "") + (if text == "-0.0" then "0.0" else text) + unit

  private def percent(hits: Int, n: Int): Double = if n == 0 then 0.0 else 100.0 * hits / n

  private def meanChange(group: String, label: String, before: Seq[Double], after: Seq[Double]): Change =
    Change(group, label, one(Stats.mean(before)), one(Stats.mean(after)), signed(Stats.mean(after) - Stats.mean(before)),
           Stats.meanZ(before, after))

  private def rateChange(group: String, label: String, before: (Int, Int), after: (Int, Int)): Change =
    val (b, a) = (percent(before._1, before._2), percent(after._1, after._2))
    Change(group, label, one(b) + "%", one(a) + "%", signed(a - b, " pts"), Stats.rateZ(before._1, before._2, after._1, after._2))

  // -- one simulation against another ------------------------------------------------------------------

  /** Enough fights on both sides for a comparison of one enemy to mean something. */
  private val MinFights = 10

  def changes(before: SimResult, after: SimResult): List[Change] =
    runChanges(before.runs, after.runs) ++ fightChanges(before.combats, after.combats) ++
      lootChanges(before.drops, after.drops) ++ milestoneChanges(before.runs, after.runs)

  private def runChanges(a: List[RunRow], b: List[RunRow]): List[Change] =
    def numeric(label: String)(f: RunRow => Int) = meanChange("Runs", label, a.map(f(_).toDouble), b.map(f(_).toDouble))
    List(
      rateChange("Runs", "Win rate", (a.count(_.outcome == "victory"), a.size), (b.count(_.outcome == "victory"), b.size)),
      numeric("Level at the end")(_.level),
      numeric("Section reached (1 = first)")(_.section + 1),
      numeric("Fights per run")(_.combats),
      numeric("Chests opened per run")(_.chestsOpened),
      numeric("Items dropped per run")(_.drops),
      numeric("Potions used per run")(_.potionsUsed),
      numeric("Shards at the end")(_.shards),
      numeric("Max HP at the end")(_.maxHp),
      numeric("Attack at the end")(_.attack),
      numeric("Defense at the end")(_.defense)
    )

  private def fightChanges(a: List[CombatRow], b: List[CombatRow]): List[Change] =
    val order = List("normal", "elite", "miniboss", "boss")
    val ga    = a.groupBy(c => (c.kind, c.enemy))
    val gb    = b.groupBy(c => (c.kind, c.enemy))
    val keys  = (ga.keySet intersect gb.keySet).toList.sortBy((kind, enemy) => (order.indexOf(kind), enemy))
    keys.filter(k => ga(k).size >= MinFights && gb(k).size >= MinFights).flatMap:
      key =>
        val (x, y) = (ga(key), gb(key))
        val name   = s"${key._2} (${key._1})"
        def damage(c: CombatRow) = 100.0 * c.damageTaken / math.max(1, c.maxHp)
        List(
          meanChange("Fights", s"$name: turns to kill", x.map(_.turns.toDouble), y.map(_.turns.toDouble)),
          meanChange("Fights", s"$name: damage taken, % of max HP", x.map(damage), y.map(damage)),
          rateChange("Fights", s"$name: won", (x.count(_.won), x.size), (y.count(_.won), y.size))
        )

  private def lootChanges(a: List[DropRow], b: List[DropRow]): List[Change] =
    val sources  = (a.map(_.source) ++ b.map(_.source)).distinct.sorted
    val rarities = List("common", "uncommon", "rare", "epic")
    sources.flatMap:
      source =>
        val (x, y) = (a.filter(_.source == source), b.filter(_.source == source))
        rarities.map:
          rarity => rateChange("Loot", s"$source: share of $rarity", (x.count(_.rarity == rarity), x.size), (y.count(_.rarity == rarity), y.size))

  private def milestoneChanges(a: List[RunRow], b: List[RunRow]): List[Change] =
    Milestone.Names.flatMap:
      name =>
        val (x, y) = (a.flatMap(_.milestones.get(name)), b.flatMap(_.milestones.get(name)))
        val reached = rateChange("Milestones", s"$name: runs that got there", (x.size, a.size), (y.size, b.size))
        if x.size >= MinFights && y.size >= MinFights then
          List(reached, meanChange("Milestones", s"$name: chests opened by then", x.map(_.chests.toDouble), y.map(_.chests.toDouble)))
        else List(reached)

  /** The comparison of two simulations of the same thing, as a Markdown page. */
  def render(before: SimResult, after: SimResult, labels: (String, String)): String =
    val all = changes(before, after)
    val head = List("# Balance comparison", "",
                    s"- Before: ${labels._1} (${before.runs.size} runs)",
                    s"- After: ${labels._2} (${after.runs.size} runs)",
                    "- `**`: too big to be luck (under 1 chance in 100). `*`: under 1 in 20. With dozens of rows a few `*` are luck whatever changed: trust `**` and rows with many samples", "")
    (head ++ headline(all) ++ biggest(all) ++ groups(all)).mkString("", "\n", "\n")

  private val TopChanges = 15

  /** What a player cares about first, always at the top: do they win more, and how far do they get. The
    * rest of the page is sorted by how sure the change is, which a big change in strength can crowd
    * these out of.
    */
  private def headline(all: List[Change]): List[String] =
    List("## Headline", "") ++ row(all.filter(c => c.group == "Runs").take(3), withGroup = false)

  private def biggest(all: List[Change]): List[String] =
    val top = all.filter(_.flag.nonEmpty).sortBy(c => -c.z.abs).take(TopChanges)
    List("## Biggest changes", "") ++
      (if top.isEmpty then List("_Nothing moved beyond what luck explains._", "") else row(top, withGroup = true))

  private def groups(all: List[Change]): List[String] =
    all.map(_.group).distinct.flatMap:
      group => List(s"## $group", "") ++ row(all.filter(_.group == group), withGroup = false)

  private def row(changes: List[Change], withGroup: Boolean): List[String] =
    SimReport.table(
      (if withGroup then List("Group") else Nil) ++ List("Measure", "Before", "After", "Change", ""),
      changes.map(c => (if withGroup then List(c.group) else Nil) ++ List(c.label, c.before, c.after, c.delta, c.flag))
    )

  // -- two grids -----------------------------------------------------------------------------------

  /** Every simulation under `dir` (a grid has one per cell), by its path under `dir`. A folder that is
    * itself a simulation is the only entry, under the name ".". */
  def results(dir: Path): Map[String, SimResult] =
    if SimCsv.isResult(dir) then Map("." -> SimCsv.read(dir))
    else
      val stream = Files.walk(dir)
      try
        stream.iterator.asScala.toList
          .filter(p => Files.isDirectory(p) && SimCsv.isResult(p))
          .map(p => dir.relativize(p).toString.replace('\\', '/') -> SimCsv.read(p))
          .toMap
      finally stream.close()

  /** The comparison of two folders of simulations: one simulation each, or two grids cell by cell. */
  def renderFolders(before: Map[String, SimResult], after: Map[String, SimResult], labels: (String, String)): String =
    if before.keySet == Set(".") && after.keySet == Set(".") then render(before("."), after("."), labels)
    else renderCells(before, after, labels)

  private def renderCells(before: Map[String, SimResult], after: Map[String, SimResult], labels: (String, String)): String =
    val common = (before.keySet intersect after.keySet).toList.sorted
    val cells = common.map:
      key =>
        val (a, b) = (before(key).runs, after(key).runs)
        key -> List(
          rateChange("", "win", (a.count(_.outcome == "victory"), a.size), (b.count(_.outcome == "victory"), b.size)),
          meanChange("", "level", a.map(_.level.toDouble), b.map(_.level.toDouble)),
          meanChange("", "section", a.map(r => (r.section + 1).toDouble), b.map(r => (r.section + 1).toDouble))
        )
    val moved = cells.flatMap((key, cs) => cs.filter(_.flag.nonEmpty).map(c => (key, c))).sortBy((_, c) => -c.z.abs)
    val head = List("# Balance comparison, cell by cell", "",
                    s"- Before: ${labels._1}", s"- After: ${labels._2}",
                    s"- ${common.size} cells in common, ${cells.count(_._2.exists(_.flag.nonEmpty))} with a change beyond luck (`**` under 1 chance in 100, `*` under 1 in 20)", "")
    val biggestRows = SimReport.table(List("Cell", "Measure", "Before", "After", "Change", ""),
                                      moved.take(TopChanges * 2).map((key, c) => List(key, c.label, c.before, c.after, c.delta, c.flag)))
    val allRows = SimReport.table(
      List("Cell", "Win rate before", "Win rate after", "Change", "", "Level change", "Section change"),
      cells.map:
        (key, cs) => List(key, cs(0).before, cs(0).after, cs(0).delta, cs(0).flag, cs(1).delta + cs(1).flag, cs(2).delta + cs(2).flag)
    )
    val missing =
      (before.keySet -- after.keySet).toList.sorted.map(k => s"- only before: $k") ++
        (after.keySet -- before.keySet).toList.sorted.map(k => s"- only after: $k")
    (head ++ List("## Biggest changes", "") ++ (if moved.isEmpty then List("_Nothing moved beyond what luck explains._", "") else biggestRows) ++
      List("## Every cell", "") ++ allRows ++ (if missing.isEmpty then Nil else "## Not in both" :: "" :: missing ::: List(""))).mkString("", "\n", "\n")
