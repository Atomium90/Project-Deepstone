package roguelite.sim

import munit.CatsEffectSuite
import roguelite.engine.{ ClassId, Difficulty }

import java.nio.file.Files

class BalanceSimSuite extends CatsEffectSuite:

  private def options(classId: ClassId = ClassId.Warrior, difficulty: Difficulty = Difficulty.Easy, runs: Int = 12) =
    SimOptions(runs = runs, seed = 7L, classId = classId, difficulty = difficulty)

  test("the same options always give the same tables"):
    SimWorld.load().map:
      world =>
        val first  = BalanceSim.simulate(world, options())
        val second = BalanceSim.simulate(world, options())
        assertEquals(first, second)

  test("the bot finishes every run of every class, none left going round in circles"):
    SimWorld.load().map:
      world =>
        ClassId.values.foreach:
          classId =>
            val result = BalanceSim.simulate(world, options(classId, Difficulty.Normal))
            val unfinished = result.runs.filter(r => r.outcome == "stuck" || r.outcome == "cap")
            assert(unfinished.isEmpty, s"$classId: runs ${unfinished.map(_.run)} did not end")

  test("a run is tallied in all three tables and the milestones are ordered"):
    SimWorld.load().map:
      world =>
        val result = BalanceSim.simulate(world, options())
        assertEquals(result.runs.size, 12)
        assertEquals(result.runs.map(_.combats).sum, result.combats.size)
        assertEquals(result.runs.map(_.drops).sum, result.drops.size)
        result.runs.foreach:
          r =>
            // A bonus needs pieces worn, and a worn piece was found first.
            r.milestones.get("set2pc").foreach(_ => assert(r.milestones.contains("ownSetPiece"), s"run ${r.run}"))
            r.milestones.get("epic").foreach(_ => assert(r.milestones.contains("rare"), s"run ${r.run}"))

  test("the report names every part of what it measures"):
    SimWorld.load().map:
      world =>
        val result = BalanceSim.simulate(world, options())
        val report = SimReport.render(result, List("header line"))
        List("# Balance simulation", "header line", "## Runs", "## What kills the bot", "## Progress by section",
             "## Fights", "## Loot", "## Milestones").foreach:
          part => assert(report.contains(part), s"missing: $part")

  test("the tables are written as CSV with one line per row and the report next to them"):
    SimWorld.load().map:
      world =>
        val result = BalanceSim.simulate(world, options(runs = 3))
        val dir    = Files.createTempDirectory("deepstone-sim")
        SimCsv.write(dir, result)
        assertEquals(Files.readAllLines(dir.resolve("runs.csv")).size, 1 + result.runs.size)
        assertEquals(Files.readAllLines(dir.resolve("combats.csv")).size, 1 + result.combats.size)
        assertEquals(Files.readAllLines(dir.resolve("drops.csv")).size, 1 + result.drops.size)

  test("the options are read from the command line"):
    val parsed = SimOptions.parse(
      List("--runs", "5", "--seed", "9", "--class", "Mage", "--difficulty", "hard", "--heal", "55", "--out", "somewhere")
    )
    assertEquals(parsed.map(_.runs), Right(5))
    assertEquals(parsed.map(_.seed), Right(9L))
    assertEquals(parsed.map(_.classId), Right(ClassId.Mage))
    assertEquals(parsed.map(_.difficulty), Right(Difficulty.Hard))
    assertEquals(parsed.map(_.policy.healBelowPercent), Right(55))
    assertEquals(parsed.map(_.out.toString), Right("somewhere"))

  test("no option at all gives the spike's defaults"):
    assertEquals(SimOptions.parse(Nil), Right(SimOptions()))

  test("a bad option is refused with a message rather than ignored"):
    assert(SimOptions.parse(List("--runs", "many")).isLeft)
    assert(SimOptions.parse(List("--class", "paladin")).isLeft)
    assert(SimOptions.parse(List("--difficulty", "nightmare")).isLeft)
    assert(SimOptions.parse(List("--bogus")).isLeft)
    assert(SimOptions.parse(List("--runs")).isLeft)
