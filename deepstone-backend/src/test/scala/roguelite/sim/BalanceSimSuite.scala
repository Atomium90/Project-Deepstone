package roguelite.sim

import munit.CatsEffectSuite
import roguelite.engine.{ ClassId, Difficulty }

import java.nio.file.Files

class BalanceSimSuite extends CatsEffectSuite:

  private def options(classId: ClassId = ClassId.Warrior, difficulty: Difficulty = Difficulty.Easy, runs: Int = 12) =
    SimOptions(runs = runs, seed = 7L, classId = classId, difficulty = Some(difficulty))

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

  test("every upgrade a profile names exists in the shop"):
    SimWorld.load().map:
      world =>
        val named = SimProfile.EarlyUpgrades ++ SimProfile.MidUpgrades
        assertEquals(named.filterNot(world.upgradeDefs.contains), Set.empty[String])
        ClassId.values.foreach:
          classId =>
            assert(world.kitUpgrade(classId).nonEmpty, s"no kit upgrade for $classId")
            assertEquals(SimProfile.Post.upgrades(world, classId), world.upgradeDefs.keySet)
            assertEquals(SimProfile.Late.upgrades(world, classId), world.upgradeDefs.filter((_, u) => u.requires.isEmpty).keySet)
            assert(SimProfile.Post.upgrades(world, classId).size > SimProfile.Late.upgrades(world, classId).size)

  test("a later stage starts with more: the upgrades it owns are applied as the server applies them"):
    SimWorld.load().map:
      world =>
        val byProfile = SimProfile.values.toList.map:
          profile =>
            val setup  = SimSetup(ClassId.Warrior, Difficulty.Easy, profile)
            val result = BalanceSim.simulate(world, options(runs = 5), setup)
            profile -> result.runs.map(_.maxHp).sum
        val maxHps = byProfile.map(_._2)
        assertEquals(maxHps, maxHps.sorted, s"max HP should not shrink from one stage to the next: $byProfile")
        assert(maxHps.last > maxHps.head, s"$byProfile")

  test("a run that takes no blessing starts without the upgrades whatever the profile"):
    SimWorld.load().map:
      world =>
        val fresh = BalanceSim.simulate(world, options(runs = 4), SimSetup(ClassId.Warrior, Difficulty.Hardcore, SimProfile.Fresh))
        val late  = BalanceSim.simulate(world, options(runs = 4), SimSetup(ClassId.Warrior, Difficulty.Hardcore, SimProfile.Late))
        // The same seeds play the very same runs: only the name of the profile differs.
        assertEquals(late.runs.map(_.copy(profile = "")), fresh.runs.map(_.copy(profile = "")))
        assertEquals(late.combats, fresh.combats)
        assertEquals(late.drops, fresh.drops)

  test("a perk is checked against the catalog before a run"):
    SimWorld.load().map:
      world =>
        val ordinary = world.perkDefs.values.find(!_.risky).get.id
        val risky    = world.perkDefs.values.find(_.risky).get.id
        def setup(difficulty: Difficulty, perk: Option[String], riskyPerk: Option[String]) =
          SimSetup(ClassId.Warrior, difficulty, SimProfile.Fresh, perk, riskyPerk).validate(world)
        assert(setup(Difficulty.Hard, Some(ordinary), Some(risky)).isRight)
        assert(setup(Difficulty.Normal, Some("nonsense"), None).isLeft)
        assert(setup(Difficulty.Normal, Some(risky), None).isLeft, "a risky perk is not an ordinary one")
        assert(setup(Difficulty.Hard, None, Some(ordinary)).isLeft, "an ordinary perk is not a risky one")
        assert(setup(Difficulty.Normal, None, Some(risky)).isLeft, "a risky perk only exists on Hard")

  test("a grid plays every class on every tier, once per stage asked for"):
    SimWorld.load().map:
      world =>
        val opts  = options(runs = 2)
        val cells = SimGrid.run(world, opts, List(SimProfile.Fresh, SimProfile.Late))
        assertEquals(cells.size, 2 * ClassId.values.length * SimGrid.Tiers.size)
        assertEquals(cells.map(_.setup).distinct.size, cells.size)
        assert(cells.forall(_.runs.size == 2))
        assertEquals(cells.map(c => SimGrid.folder(c.setup)).distinct.size, cells.size, "every cell has a folder of its own")
        val text = SimGrid.render(cells, opts)
        List("# Balance grid", "## fresh save", "## late save", "warrior", "archer", "mage", "hardcore").foreach:
          part => assert(text.contains(part), s"missing: $part")

  test("--grid and --profile all are read, and all is refused outside a grid"):
    assertEquals(SimOptions.parse(List("--grid", "--profile", "all")).map(o => (o.grid, o.gridProfiles)), Right((true, SimProfile.values.toList)))
    assertEquals(SimOptions.parse(List("--grid", "--profile", "mid")).map(_.gridProfiles), Right(List(SimProfile.Mid)))
    assert(SimOptions.parse(List("--profile", "all")).isLeft)

  test("the options are read from the command line"):
    val parsed = SimOptions.parse(
      List("--runs", "5", "--seed", "9", "--class", "Mage", "--difficulty", "hard", "--profile", "LATE", "--perk", "heavy_hand",
           "--risky", "hunted", "--heal", "55", "--out", "somewhere")
    )
    assertEquals(parsed.map(_.runs), Right(5))
    assertEquals(parsed.map(_.seed), Right(9L))
    assertEquals(parsed.map(_.classId), Right(ClassId.Mage))
    assertEquals(parsed.map(_.difficulty), Right(Some(Difficulty.Hard)))
    assertEquals(parsed.map(_.profile), Right(SimProfile.Late))
    assertEquals(parsed.map(_.perkId), Right(Some("heavy_hand")))
    assertEquals(parsed.map(_.riskyPerkId), Right(Some("hunted")))
    assertEquals(parsed.map(_.policy.healBelowPercent), Right(55))
    assertEquals(parsed.map(_.out.toString), Right("somewhere"))

  test("--policy picks when the bot casts, and the patient bot is the default"):
    assertEquals(SimOptions.parse(Nil).map(_.policy.abilityRule), Right(AbilityRule.WhenLong))
    assertEquals(SimOptions.parse(List("--policy", "asap")).map(_.policy.abilityRule), Right(AbilityRule.Asap))
    assertEquals(SimOptions.parse(List("--policy", "when-long")).map(_.policy.abilityRule), Right(AbilityRule.WhenLong))
    assert(SimOptions.parse(List("--policy", "never")).isLeft)

  test("the patient bot casts in fewer fights than the one in a hurry, and in every fight that matters"):
    SimWorld.load().map:
      world =>
        def casts(rule: AbilityRule, kinds: Set[String]) =
          val o      = options(ClassId.Warrior, Difficulty.Normal, runs = 30).copy(policy = BotPolicy(abilityRule = rule))
          val fights = BalanceSim.simulate(world, o).combats.filter(c => kinds.contains(c.kind))
          (fights.count(_.abilities > 0), fights.size)
        val all = Set("normal", "elite", "miniboss", "boss")
        val (hurried, _) = casts(AbilityRule.Asap, all)
        val (patient, _) = casts(AbilityRule.WhenLong, all)
        assert(patient < hurried, s"patient $patient, hurried $hurried")
        val (bossCasts, bossFights) = casts(AbilityRule.WhenLong, Set("boss"))
        assert(bossFights > 0 && bossCasts > 0, s"boss fights $bossFights, cast in $bossCasts")

  test("without --difficulty a save plays the tier of its stage"):
    SimProfile.values.foreach:
      profile => assertEquals(SimOptions(profile = profile).setup.difficulty, profile.defaultDifficulty)
    assertEquals(SimOptions(difficulty = Some(Difficulty.Hard), profile = SimProfile.Fresh).setup.difficulty, Difficulty.Hard)

  test("no option at all gives the spike's defaults"):
    assertEquals(SimOptions.parse(Nil), Right(SimOptions()))

  test("a bad option is refused with a message rather than ignored"):
    assert(SimOptions.parse(List("--runs", "many")).isLeft)
    assert(SimOptions.parse(List("--class", "paladin")).isLeft)
    assert(SimOptions.parse(List("--difficulty", "nightmare")).isLeft)
    assert(SimOptions.parse(List("--profile", "ancient")).isLeft)
    assert(SimOptions.parse(List("--bogus")).isLeft)
    assert(SimOptions.parse(List("--runs")).isLeft)
