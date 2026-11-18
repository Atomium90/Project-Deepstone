package roguelite.game.progression

import munit.CatsEffectSuite
import roguelite.engine.{ Difficulty, StateUpdateProjections, UpgradeView }
import roguelite.game.core.LogLine
import roguelite.game.story.StoryProgress

/** The late-game upgrades: bought with Shards like the others, but only once the save has won enough
  * runs on a tier, so that farming an easy tier can never buy them.
  */
class LateUpgradeSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"hp_a","label":"L","description":"d","cost":100,"displayOrder":1,"icon":"i","category":"stat","effect":{"type":"MaxHpBoost","amount":20}},
      |  {"id":"def_late","label":"L","description":"d","cost":100,"displayOrder":2,"icon":"i","category":"stat","effect":{"type":"FlatDefenseBoost","amount":3},"requires":{"winsAt":"hard","wins":3}}
      |]""".stripMargin

  private def defs = UpgradeLoader.loadAllFromJson(fixture)

  private val rich = MetaProgression(currency = 1000, unlockedUpgrades = Set.empty)

  private def stats(hardWins: Int, normalWins: Int = 0) =
    AchievementStats(runsWon = hardWins + normalWins,
                     winsByDifficulty = Map(Difficulty.Hard -> hardWins, Difficulty.Normal -> normalWins))

  // -- loading ---------------------------------------------------------------

  test("a requirement and the defense effect decode, and an upgrade without one asks for nothing"):
    for d <- defs
    yield
      assertEquals(d("def_late").effect, UpgradeEffect.FlatDefenseBoost(3))
      assertEquals(d("def_late").requires, Some(UpgradeRequirement(Difficulty.Hard, 3)))
      assertEquals(d("hp_a").requires, None)

  test("a requirement on an unknown difficulty, or on no wins, is refused when the catalog loads"):
    val unknown = fixture.replace(""""winsAt":"hard"""", """"winsAt":"nightmare"""")
    val none    = fixture.replace(""""wins":3""", """"wins":0""")
    for
      a <- UpgradeLoader.loadAllFromJson(unknown).attempt
      b <- UpgradeLoader.loadAllFromJson(none).attempt
    yield
      assert(a.isLeft, "an unknown difficulty should not load")
      assert(b.isLeft, "a requirement of zero wins should not load")

  // -- buying ----------------------------------------------------------------

  test("an upgrade that asks for Hard wins cannot be bought before them, and says how far the save is"):
    for d <- defs
    yield
      val refused = rich.purchase("def_late", d, stats(hardWins = 2))
      assertEquals(
        refused,
        Left(LogLine.of("log.shop.locked", "upgrade" -> "def_late", "difficulty" -> "hard", "wins" -> 3, "have" -> 2))
      )

  test("wins on an easier tier do not count toward it"):
    for d <- defs
    yield assert(rich.purchase("def_late", d, stats(hardWins = 0, normalWins = 50)).isLeft)

  test("once the wins are there it is bought like any other upgrade"):
    for d <- defs
    yield
      val bought = rich.purchase("def_late", d, stats(hardWins = 3))
      assertEquals(bought.map(_.currency), Right(900))
      assertEquals(bought.map(_.isUnlocked("def_late")), Right(true))

  test("an upgrade with no requirement is bought whatever the save has won"):
    for d <- defs yield assert(rich.purchase("hp_a", d, AchievementStats.empty).isRight)

  test("the requirement is checked before the price"):
    for d <- defs
    yield
      val broke = MetaProgression(currency = 0, unlockedUpgrades = Set.empty).purchase("def_late", d, stats(hardWins = 0))
      assertEquals(broke.left.map(_.key), Left("log.shop.locked"))

  // -- what the hub shows ----------------------------------------------------

  private val tiers: Map[Difficulty, DifficultyTierDef] = Map(
    Difficulty.Easy   -> DifficultyTierDef(Difficulty.Easy, None, List("dungeon")),
    Difficulty.Normal -> DifficultyTierDef(Difficulty.Normal, Some(TierRequirement(Difficulty.Easy, 1, 0)), List("dungeon")),
    Difficulty.Hard   -> DifficultyTierDef(Difficulty.Hard, Some(TierRequirement(Difficulty.Normal, 2, 0)), List("dungeon"))
  )

  private def projections(d: Map[String, UpgradeDef]) =
    StateUpdateProjections(
      abilityCatalog = Nil,
      setCatalog = Nil,
      achievementDefs = Map.empty,
      abilityDefs = Map.empty,
      setDefs = Map.empty,
      perkDefs = Map.empty,
      difficultyDefs = tiers,
      sceneDefs = Map.empty,
      npcDefs = Map.empty,
      upgradeDefs = d
    )

  private def views(owned: Set[String] = Set.empty) = List(
    UpgradeView("hp_a", 100, "i", UpgradeCategory.Stat, unlocked = owned("hp_a")),
    UpgradeView("def_late", 100, "i", UpgradeCategory.Stat, unlocked = owned("def_late"))
  )

  test("a late-game upgrade stays out of the shop until the tier it asks for is open"):
    for d <- defs
    yield
      val shown = projections(d).upgradeViews(views(), stats(hardWins = 0, normalWins = 0).copy(winsByDifficulty = Map(Difficulty.Easy -> 1)), StoryProgress.empty)
      assertEquals(shown.map(_.id), List("hp_a"))

  test("once the tier is open it is listed with how many wins the save has"):
    for d <- defs
    yield
      val shown = projections(d).upgradeViews(views(), stats(hardWins = 1, normalWins = 2), StoryProgress.empty)
      assertEquals(shown.map(_.id), List("hp_a", "def_late"))
      assertEquals(shown.flatMap(_.requirement).map(r => (r.winsDone, r.wins)), List((1, 3)))
      assertEquals(shown.find(_.id == "hp_a").flatMap(_.requirement), None)

  test("an upgrade the save already owns is always listed"):
    for d <- defs
    yield
      val shown = projections(d).upgradeViews(views(Set("def_late")), AchievementStats.empty, StoryProgress.empty)
      assertEquals(shown.map(_.id), List("hp_a", "def_late"))
