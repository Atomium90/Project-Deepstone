package roguelite.sim

import roguelite.engine.Difficulty
import roguelite.sim.Campaign.Step

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

/** What a set of campaigns says about the economy: how long it takes to open each tier and to empty
  * the shop, what the Shards are worth along the way and once there is nothing left to buy.
  */
object CampaignReport:

  def writeCsv(dir: Path, steps: List[Step]): Unit =
    Files.createDirectories(dir)
    val header = List("campaign", "run", "tier", "outcome", "level", "section", "earned", "topUp", "spent", "bought", "balance", "owned", "unlocked")
    val rows = steps.map:
      s =>
        List(s.campaign, s.run, s.tier.toString.toLowerCase, s.outcome, s.level, s.section, s.earned, s.topUp, s.spent,
             s.bought.mkString(";"), s.balance, s.owned, s.unlocked.toList.sortBy(_.ordinal).map(_.toString.toLowerCase).mkString(";"))
    val text = (header :: rows.map(_.map(_.toString))).map(_.mkString(",")).mkString("", "\n", "\n")
    Files.writeString(dir.resolve("campaigns.csv"), text, StandardCharsets.UTF_8)

  def render(world: SimWorld, steps: List[Step], options: SimOptions): String =
    val byCampaign = steps.groupBy(_.campaign).toList.sortBy(_._1).map(_._2)
    val shopSize   = world.upgradeDefs.size
    val lines =
      List("# Economy simulation", "",
           s"- ${byCampaign.size} saves of ${options.campaignRuns} runs each, as the ${options.classId.toString.toLowerCase}, first seed ${options.seed}",
           "- Each run is played on the highest tier the save has open, then the Shards are spent: the kit of the class first, then what makes the next run safer, then the cheapest upgrade left",
           "- The access to the class is given from the start, and perks are not taken",
           s"- The shop holds $shopSize upgrades, ${world.upgradeDefs.values.map(_.cost).sum} Shards in all", "") ++
        milestones(world, options, byCampaign, shopSize) ++ pace(world, byCampaign) ++ income(byCampaign, shopSize) ++
        timeline(byCampaign.headOption.getOrElse(Nil).take(40))
    lines.mkString("", "\n", "\n")

  private def firstRun(steps: List[Step])(found: Step => Boolean): Option[Int] = steps.find(found).map(_.run)

  private def spread(values: List[Int]): List[String] =
    List(SimReport.mean(values), SimReport.percentile(values, 50), SimReport.percentile(values, 90))

  /** "Reached by run N": how long it takes, among the saves that got there within the runs played. */
  private def milestones(world: SimWorld, options: SimOptions, saves: List[List[Step]], shopSize: Int): List[String] =
    val kits = world.kitUpgrade(options.classId)
    val rows: List[(String, List[Step] => Option[Int])] = List(
      "First victory" -> (s => firstRun(s)(_.victory)),
      "Class kit bought" -> (s => firstRun(s)(_.bought.exists(kits.contains))),
      "Normal open" -> (s => firstRun(s)(_.unlocked.contains(Difficulty.Normal))),
      "First Normal victory" -> (s => firstRun(s)(x => x.victory && x.tier == Difficulty.Normal)),
      "Hard open" -> (s => firstRun(s)(_.unlocked.contains(Difficulty.Hard))),
      "First Hard victory" -> (s => firstRun(s)(x => x.victory && x.tier == Difficulty.Hard)),
      "Half the shop owned" -> (s => firstRun(s)(_.owned * 2 >= shopSize)),
      "Whole shop owned" -> (s => firstRun(s)(_.owned >= shopSize))
    )
    val table = rows.map:
      (label, find) =>
        val reached = saves.flatMap(s => find(s))
        List(label, SimReport.pct(reached.size, saves.size)) ++ spread(reached)
    List("## How many runs it takes", "",
         "The run after which each thing is true, among the saves that got there. A save that never does is counted only in the second column.", "") ++
      SimReport.table(List("Milestone", "Saves that got there", "Runs (mean)", "Runs (median)", "Runs (p90)"), table)

  /** When each upgrade is bought, in the order it is bought. */
  private def pace(world: SimWorld, saves: List[List[Step]]): List[String] =
    val rows = world.upgradeDefs.values.toList.map:
      u =>
        val at = saves.flatMap(s => s.find(_.bought.contains(u.id)).map(_.run))
        (u, at)
    val sorted = rows.sortBy((u, at) => (if at.isEmpty then Int.MaxValue else at.sum / at.size, u.cost))
    List("## When each upgrade is bought", "") ++
      SimReport.table(
        List("Upgrade", "Cost", "Saves that bought it", "Run (mean)", "Run (median)", "Run (p90)"),
        sorted.map((u, at) => List(u.id, u.cost.toString, SimReport.pct(at.size, saves.size)) ++ spread(at))
      )

  /** What a run brings in, by tier, then the same once the shop is empty. */
  private def income(saves: List[List[Step]], shopSize: Int): List[String] =
    def table(steps: List[Step]): List[String] =
      val rows = steps.groupBy(_.tier).toList.sortBy(_._1.ordinal).map:
        (tier, ss) =>
          val wins = ss.filter(_.victory)
          List(tier.toString.toLowerCase, ss.size.toString, SimReport.pct(wins.size, ss.size), SimReport.mean(ss.map(_.earned)),
               SimReport.mean(wins.map(_.earned)), SimReport.mean(ss.filterNot(_.victory).map(_.earned)))
      SimReport.table(List("Tier", "Runs", "Won", "Shards per run", "Shards per win", "Shards per loss"), rows)

    val all = saves.flatten
    // Runs played with nothing left to buy: those after the one that emptied the shop.
    val emptyShop = saves.flatMap:
      s => s.find(_.owned >= shopSize).fold(List.empty[Step])(done => s.filter(_.run > done.run))
    List("## What the Shards are worth", "", "### Every run", "") ++ table(all) ++
      List("### Runs played once the shop is empty (nothing left to buy)", "") ++
      (if emptyShop.isEmpty then List("_No save emptied the shop within the runs played._", "") else table(emptyShop) ++
        List(s"Shards left unspent at the end of those saves: ${SimReport.mean(saves.flatMap(s => s.lastOption.filter(_.owned >= shopSize).map(_.balance)))} on average.", ""))

  /** The first runs of the first save, one line each. */
  private def timeline(steps: List[Step]): List[String] =
    List("## The first runs of the first save", "") ++
      SimReport.table(
        List("Run", "Tier", "Outcome", "Level", "Section", "Earned", "Top-up", "Bought", "Shards left", "Owned"),
        steps.map(s => List(s.run.toString, s.tier.toString.toLowerCase, s.outcome, s.level.toString, s.section.toString, s.earned.toString,
                            s.topUp.toString, s.bought.mkString(", "), s.balance.toString, s.owned.toString))
      )
