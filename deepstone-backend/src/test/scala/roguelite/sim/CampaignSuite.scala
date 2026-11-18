package roguelite.sim

import munit.CatsEffectSuite
import roguelite.engine.{ ClassId, Difficulty }
import roguelite.sim.Campaign.Step

import java.nio.file.Files

class CampaignSuite extends CatsEffectSuite:

  private val policy = BotPolicy()

  private def save(world: SimWorld, classId: ClassId = ClassId.Warrior, campaign: Int = 0, runs: Int = 14): List[Step] =
    Campaign.play(world, policy, classId, campaign, runs, seed = 3L)

  test("the same save lived twice gives the same story"):
    SimWorld.load().map:
      world => assertEquals(save(world), save(world))

  test("a save plays every run, in order, and no run is left going round in circles"):
    SimWorld.load().map:
      world =>
        ClassId.values.foreach:
          classId =>
            val steps = save(world, classId)
            assertEquals(steps.map(_.run), (1 to 14).toList, classId.toString)
            assert(steps.forall(s => s.outcome == "victory" || s.outcome == "death"), s"$classId: ${steps.map(_.outcome)}")

  test("the shopping order lists every upgrade once and starts with the kit of the class"):
    SimWorld.load().map:
      world =>
        ClassId.values.foreach:
          classId =>
            val order = Campaign.shopOrder(world, classId)
            assertEquals(order.sorted, world.upgradeDefs.keys.toList.sorted)
            assertEquals(order.head, world.kitUpgrade(classId).head)

  test("what the save owns and has open only ever grows, and the Shards add up"):
    SimWorld.load().map:
      world =>
        val steps = save(world)
        steps.sliding(2).collect { case List(before, after) => (before, after) }.foreach:
          (before, after) =>
            assert(after.owned >= before.owned, s"run ${after.run}")
            assert(before.unlocked.subsetOf(after.unlocked), s"run ${after.run}")
        assert(steps.forall(_.balance >= 0))
        // Everything bought was paid for out of what the runs brought in.
        assertEquals(steps.map(_.spent).sum, steps.flatMap(_.bought).map(world.upgradeDefs(_).cost).sum)
        assertEquals(steps.last.balance, steps.map(s => s.earned + s.topUp - s.spent).sum)
        assertEquals(steps.last.owned, world.classAccess(ClassId.Warrior).size + steps.flatMap(_.bought).size)

  test("a tier is played only once the game's rules have opened it"):
    SimWorld.load().map:
      world =>
        val steps = save(world, runs = 30)
        steps.zipWithIndex.foreach:
          (step, i) =>
            val before = steps.take(i)
            step.tier match
              case Difficulty.Easy => ()
              case Difficulty.Normal =>
                assert(before.exists(s => s.victory && s.tier == Difficulty.Easy), s"run ${step.run} is Normal without an Easy win")
              case Difficulty.Hard =>
                assert(before.count(s => s.victory && s.tier == Difficulty.Normal) >= 4, s"run ${step.run} is Hard without 4 Normal wins")
                assert(before.map(_.spent).sum >= 300, s"run ${step.run} is Hard without 300 Shards spent")
              case other => fail(s"the campaign played $other")

  test("the highest open tier is the one played"):
    SimWorld.load().map:
      world =>
        val steps = save(world, runs = 30)
        assertEquals(steps.head.tier, Difficulty.Easy)
        steps.sliding(2).collect { case List(before, after) => (before, after) }.foreach:
          (before, after) => assertEquals(after.tier, before.unlocked.maxBy(_.ordinal), s"run ${after.run}")

  test("the report names every part and the CSV has a line per run"):
    SimWorld.load().map:
      world =>
        val options = SimOptions(campaigns = 3, campaignRuns = 12)
        val steps   = BalanceSim.simulateCampaigns(world, options)
        assertEquals(steps.size, 36)
        assertEquals(steps.map(_.campaign).distinct, List(0, 1, 2))
        val report = CampaignReport.render(world, steps, options)
        List("# Economy simulation", "## How many runs it takes", "## When each upgrade is bought", "## What the Shards are worth",
             "## The first runs of the first save", "Whole shop owned").foreach:
          part => assert(report.contains(part), s"missing: $part")
        val dir = Files.createTempDirectory("deepstone-campaign")
        CampaignReport.writeCsv(dir, steps)
        assertEquals(Files.readAllLines(dir.resolve("campaigns.csv")).size, 1 + steps.size)

  test("the campaign options are read"):
    val parsed = SimOptions.parse(List("--campaign", "--campaigns", "7", "--campaign-runs", "50"))
    assertEquals(parsed.map(o => (o.campaign, o.campaigns, o.campaignRuns)), Right((true, 7, 50)))
    assert(SimOptions.parse(List("--campaigns", "lots")).isLeft)
