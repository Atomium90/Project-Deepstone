package roguelite.sim

import java.util.Locale

/** Turns the three tables of a simulation into a readable Markdown report. */
object SimReport:

  private[sim] def pct(part: Int, whole: Int): String =
    if whole == 0 then "-" else String.format(Locale.ROOT, "%.1f%%", Double.box(100.0 * part / whole))

  private def fmt(value: Double): String = String.format(Locale.ROOT, "%.1f", Double.box(value))

  private[sim] def mean(xs: Seq[Int]): String = if xs.isEmpty then "-" else fmt(xs.sum.toDouble / xs.size)

  /** The value at the given percentile (0-100) of a sorted copy of `xs`. */
  private[sim] def percentile(xs: Seq[Int], p: Int): String =
    if xs.isEmpty then "-"
    else
      val sorted = xs.sorted
      sorted(math.min(sorted.size - 1, (p / 100.0 * sorted.size).toInt)).toString

  private[sim] def table(headers: List[String], rows: List[List[String]]): List[String] =
    if rows.isEmpty then List("_(nothing)_", "")
    else
      List(headers.mkString("| ", " | ", " |"), headers.map(_ => "---").mkString("| ", " | ", " |")) ++
        rows.map(_.mkString("| ", " | ", " |")) :+ ""

  def render(result: SimResult, header: List[String]): String =
    val lines =
      List("# Balance simulation", "") ++ header.map(h => s"- $h") ++ List("") ++
        summary(result.runs) ++ deaths(result) ++ sections(result) ++ combats(result.combats) ++
        loot(result) ++ milestones(result.runs)
    lines.mkString("", "\n", "\n")

  private def summary(runs: List[RunRow]): List[String] =
    val n = runs.size
    val outcomes = runs.groupBy(_.outcome).view.mapValues(_.size).toMap
    List("## Runs", "") ++ table(
      List("Runs", "Victories", "Deaths", "Stuck", "Hit the action cap"),
      List(List(n.toString,
                s"${outcomes.getOrElse("victory", 0)} (${pct(outcomes.getOrElse("victory", 0), n)})",
                outcomes.getOrElse("death", 0).toString,
                outcomes.getOrElse("stuck", 0).toString,
                outcomes.getOrElse("cap", 0).toString
           )
      )
    ) ++ table(
      List("", "Mean", "Median", "p90"),
      List(
        List("Level at the end") ++ spread(runs.map(_.level)),
        List("Actions") ++ spread(runs.map(_.actions)),
        List("Fights") ++ spread(runs.map(_.combats)),
        List("Chests opened") ++ spread(runs.map(_.chestsOpened)),
        List("Items dropped") ++ spread(runs.map(_.drops)),
        List("Potions used") ++ spread(runs.map(_.potionsUsed)),
        List("Shards at the end") ++ spread(runs.map(_.shards)),
        List("Max HP at the end") ++ spread(runs.map(_.maxHp)),
        List("Attack at the end") ++ spread(runs.map(_.attack)),
        List("Defense at the end") ++ spread(runs.map(_.defense))
      )
    )

  private def spread(xs: Seq[Int]): List[String] = List(mean(xs), percentile(xs, 50), percentile(xs, 90))

  private def deaths(result: SimResult): List[String] =
    val dead = result.runs.filter(_.outcome == "death")
    val kindOf = result.combats.filter(!_.won).groupBy(_.run).view.mapValues(_.head.kind).toMap
    val rows = dead
      .groupBy(r => (kindOf.getOrElse(r.run, "?"), r.killedBy))
      .toList
      .sortBy((_, rs) => -rs.size)
      .map((key, rs) => List(key._2, key._1, rs.size.toString, pct(rs.size, dead.size), mean(rs.map(_.level))))
    List("## What kills the bot", "") ++ table(List("Enemy", "Kind", "Deaths", "Share of deaths", "Mean level"), rows)

  private def sections(result: SimResult): List[String] =
    val sectionIds = (result.combats.map(_.section) ++ result.runs.map(_.section)).distinct.sorted
    val rows = sectionIds.map:
      s =>
        val fights = result.combats.filter(_.section == s)
        val reached = result.runs.count(r => r.section >= s && r.outcome != "stuck")
        val died = result.runs.count(r => r.outcome == "death" && r.section == s)
        List((s + 1).toString, reached.toString, died.toString, pct(died, reached), fights.size.toString,
             mean(fights.map(f => f.damageTaken * 100 / math.max(1, f.maxHp))))
    List("## Progress by section", "") ++
      table(List("Section", "Runs that got there", "Deaths in it", "Death rate", "Fights", "Mean damage taken per fight (% of max HP)"), rows)

  private def combats(rows: List[CombatRow]): List[String] =
    val order = List("normal", "elite", "miniboss", "boss")
    val perEnemy = rows
      .groupBy(c => (c.kind, c.enemy))
      .toList
      .sortBy((key, _) => (order.indexOf(key._1), key._2))
      .map:
        (key, cs) =>
          List(key._2, key._1, cs.size.toString, pct(cs.count(_.won), cs.size),
               mean(cs.map(_.turns)), percentile(cs.map(_.turns), 50), percentile(cs.map(_.turns), 90),
               mean(cs.map(c => c.damageTaken * 100 / math.max(1, c.maxHp))),
               fmt(cs.map(_.potions).sum.toDouble / cs.size), mean(cs.map(_.attack)), mean(cs.map(_.defense)))
    val ordinary = rows.filter(r => r.kind == "normal" || r.kind == "elite")
    List("## Fights", "",
         s"Elites are ${pct(ordinary.count(_.kind == "elite"), ordinary.size)} of the ordinary fights.", "") ++
      table(
        List("Enemy", "Kind", "Fights", "Won", "Turns (mean)", "Turns (median)", "Turns (p90)",
             "Damage taken (% of max HP)", "Potions per fight", "Player attack", "Player defense"),
        perEnemy
      )

  private def loot(result: SimResult): List[String] =
    val drops = result.drops
    val rarities = List("common", "uncommon", "rare", "epic", "")
    def label(r: String) = if r.isEmpty then "unknown" else r
    val bySource = drops.groupBy(_.source).toList.sortBy(_._1).map:
      (source, ds) =>
        List(source, ds.size.toString) ++ rarities.map(r => pct(ds.count(_.rarity == r), ds.size))
    val byOutcome = drops.groupBy(_.outcome).toList.sortBy(_._1).map((o, ds) => List(o, ds.size.toString, pct(ds.size, drops.size)))
    val byKind = drops.groupBy(_.kind).toList.sortBy(_._1).map((k, ds) => List(k, ds.size.toString, pct(ds.size, drops.size)))
    val bySet = drops.filter(_.setId.nonEmpty).groupBy(_.setId).toList.sortBy(_._1).map((s, ds) => List(s, ds.size.toString))
    List("## Loot", "",
         s"${drops.size} items handed out over ${result.runs.size} runs. \"Unknown\" is a rarity the game keeps no trace of: " +
           "a duplicate thrown away because an equal or better copy was already held, or one more charge in a potion stack.",
         "") ++
      table(List("Source", "Items") ++ rarities.map(label), bySource) ++
      table(List("Outcome", "Items", "Share"), byOutcome) ++
      table(List("Kind", "Items", "Share"), byKind) ++
      table(List("Set", "Pieces dropped"), bySet)

  private def milestones(runs: List[RunRow]): List[String] =
    val labels = Map(
      "rare" -> "First Rare or better",
      "epic" -> "First Epic",
      "setPiece" -> "First piece of any set",
      "ownSetPiece" -> "First piece of a set of the class",
      "set2pc" -> "2-piece set bonus worn",
      "set4pc" -> "4-piece set bonus worn"
    )
    val rows = Milestone.Names.map:
      name =>
        val reached = runs.flatMap(_.milestones.get(name))
        List(labels(name), pct(reached.size, runs.size),
             mean(reached.map(_.chests)), percentile(reached.map(_.chests), 50), percentile(reached.map(_.chests), 90),
             mean(reached.map(_.kills)), percentile(reached.map(_.kills), 50), percentile(reached.map(_.kills), 90),
             mean(reached.map(_.drops)), percentile(reached.map(_.drops), 50))
    List("## Milestones", "",
         "How much had been opened, killed and dropped when each thing first happened, among the runs that got there. " +
           "A run that dies before reaching a milestone is not counted in its figures, only in the first column.",
         "") ++
      table(
        List("Milestone", "Runs that got there", "Chests (mean)", "Chests (median)", "Chests (p90)", "Kills (mean)",
             "Kills (median)", "Kills (p90)", "Drops (mean)", "Drops (median)"),
        rows
      )
