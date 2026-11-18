package roguelite.sim

import munit.CatsEffectSuite
import roguelite.engine.{ ClassId, Difficulty }
import roguelite.sim.SimCompare.Stats

import java.nio.file.{ Files, Path, Paths }

class SimCompareSuite extends CatsEffectSuite:

  private def options(runs: Int) = SimOptions(runs = runs, seed = 11L)

  private def played(world: SimWorld, profile: SimProfile, difficulty: Difficulty, runs: Int = 40): SimResult =
    BalanceSim.simulate(world, options(runs), SimSetup(ClassId.Warrior, difficulty, profile))

  test("the tables read back from CSV are the ones written"):
    SimWorld.load().map:
      world =>
        val result = played(world, SimProfile.Early, Difficulty.Normal, runs = 15)
        val dir    = Files.createTempDirectory("deepstone-roundtrip")
        SimCsv.write(dir, result)
        assert(SimCsv.isResult(dir))
        assertEquals(SimCsv.read(dir), result)

  test("a folder with no tables is not a simulation"):
    assert(!SimCsv.isResult(Files.createTempDirectory("deepstone-empty")))

  test("two samples alike are no change, a clear gap is one, a gap with no spread at all is a sure one"):
    assertEquals(Stats.meanZ(List(1.0, 2.0, 3.0), List(1.0, 2.0, 3.0)), 0.0)
    assertEquals(Stats.rateZ(50, 100, 50, 100), 0.0)
    assert(Stats.rateZ(50, 100, 80, 100) > 4.0)
    assert(Stats.rateZ(80, 100, 50, 100) < -4.0)
    assert(Stats.meanZ(List(10.0, 11.0, 9.0, 10.0), List(20.0, 21.0, 19.0, 20.0)) > 5.0)
    assertEquals(Stats.meanZ(List(5.0, 5.0, 5.0), List(7.0, 7.0, 7.0)), 99.0)
    assertEquals(Stats.meanZ(List(5.0), List(7.0)), 0.0, "one sample says nothing")
    assertEquals(Stats.rateZ(0, 0, 3, 10), 0.0, "an empty side says nothing")

  test("a rate that is 0% or 100% on both sides is no change"):
    assertEquals(Stats.rateZ(0, 50, 0, 50), 0.0)
    assertEquals(Stats.rateZ(50, 50, 50, 50), 0.0)

  test("a simulation compared with itself moves nothing"):
    SimWorld.load().map:
      world =>
        val result = played(world, SimProfile.Fresh, Difficulty.Normal)
        val all    = SimCompare.changes(result, result)
        assert(all.nonEmpty)
        assert(all.forall(_.flag.isEmpty), all.filter(_.flag.nonEmpty).toString)
        assert(SimCompare.render(result, result, ("a", "b")).contains("Nothing moved beyond what luck explains"))

  test("buying the class kit is seen in the win rate, flagged as no luck, and the page leads with it"):
    SimWorld.load().map:
      world =>
        // On Hard the kit is the gap between about one win in six and every win, a change far past luck
        // however the bot or the loot is tuned.
        val before = played(world, SimProfile.Fresh, Difficulty.Hard)
        val after  = played(world, SimProfile.Early, Difficulty.Hard)
        val win    = SimCompare.changes(before, after).find(_.label == "Win rate").get
        assertEquals(win.flag, "**")
        assert(win.z > 0 && win.delta.startsWith("+"), win.toString)
        val page = SimCompare.render(before, after, ("without the kit", "with the kit"))
        assert(page.contains("without the kit") && page.contains("with the kit"))
        val headline = page.split("## Headline")(1).split("## Biggest changes")(0)
        assert(headline.contains("Win rate") && headline.contains("**"), headline)

  test("every group of the page is there when there is something to compare"):
    SimWorld.load().map:
      world =>
        val page = SimCompare.render(played(world, SimProfile.Fresh, Difficulty.Easy), played(world, SimProfile.Early, Difficulty.Easy), ("a", "b"))
        List("## Runs", "## Fights", "## Loot", "## Milestones").foreach:
          part => assert(page.contains(part), s"missing: $part")

  test("two grids are compared cell by cell, and a folder that is not a simulation is refused with a message"):
    SimWorld.load().map:
      world =>
        val opts   = options(6)
        val before = Files.createTempDirectory("deepstone-grid-before")
        val after  = Files.createTempDirectory("deepstone-grid-after")
        def write(dir: Path, profile: SimProfile): Unit =
          SimGrid.run(world, opts, List(profile)).foreach(cell => SimCsv.write(dir.resolve(SimGrid.folder(cell.setup)), cell.result))
        write(before, SimProfile.Fresh)
        write(after, SimProfile.Fresh)
        val cells = SimCompare.results(before)
        assertEquals(cells.size, ClassId.values.length * SimGrid.Tiers.size)
        assert(cells.contains("fresh/warrior-easy"))

        val out = Files.createTempDirectory("deepstone-compare-out")
        val file = BalanceSim.compareFolders(before, after, out).fold(e => fail(e), identity)
        val page = Files.readString(file)
        assert(page.contains("cell by cell") && page.contains("fresh/mage-hardcore"), page)
        assert(page.contains("Nothing moved beyond what luck explains"), "the same grid twice is no change")

        assert(BalanceSim.compareFolders(Paths.get("no-such-folder"), after, out).isLeft)
        assert(BalanceSim.compareFolders(Files.createTempDirectory("deepstone-nothing"), after, out).isLeft)

  test("a grid that gained or lost cells says so"):
    SimWorld.load().map:
      world =>
        val cell   = SimGrid.run(world, options(4), List(SimProfile.Fresh)).head
        val before = Map("fresh/a" -> cell.result, "fresh/b" -> cell.result)
        val after  = Map("fresh/a" -> cell.result, "fresh/c" -> cell.result)
        val page   = SimCompare.renderFolders(before, after, ("x", "y"))
        assert(page.contains("only before: fresh/b") && page.contains("only after: fresh/c"), page)

  test("--compare is read from the command line"):
    val parsed = SimOptions.parse(List("--compare", "target/before", "target/after", "--out", "target/diff"))
    assertEquals(parsed.map(_.compare.map((a, b) => (a.toString.replace('\\', '/'), b.toString.replace('\\', '/')))),
                 Right(Some(("target/before", "target/after")))
    )
    assert(SimOptions.parse(List("--compare", "only-one")).isLeft)
