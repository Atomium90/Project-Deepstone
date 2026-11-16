package roguelite.game

import cats.effect.IO
import munit.CatsEffectSuite
import roguelite.engine.Difficulty

/** Tests for [[DifficultyLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/difficulty.json` - only the "real catalog" section at the bottom still touches
  * the production file, and only for a size-independent smoke check.
  */
class DifficultyLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"difficulty":"easy","themes":["a"]},
      |  {"difficulty":"normal","requirement":{"winsAt":"easy","wins":2,"shardsSpent":0},"themes":["a","b"]},
      |  {"difficulty":"hard","requirement":{"winsAt":"normal","wins":3,"shardsSpent":50},"themes":["a","b","b"]}
      |]""".stripMargin

  private def assertFails(json: String): IO[Unit] =
    DifficultyLoader.loadAllFromJson(json).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("loadAllFromJson keys each entry by its difficulty"):
    for tiers <- DifficultyLoader.loadAllFromJson(fixture)
    yield assertEquals(tiers.keySet, Set(Difficulty.Easy, Difficulty.Normal, Difficulty.Hard))

  test("a tier without a requirement is always available"):
    for tiers <- DifficultyLoader.loadAllFromJson(fixture)
    yield assertEquals(tiers(Difficulty.Easy).requirement, None)

  test("a tier is opened by victories unless it says the story opens it"):
    for tiers <- DifficultyLoader.loadAllFromJson(fixture)
    yield assertEquals(tiers.values.map(_.storyLocked).toSet, Set(false))

  test("a tier opened by the story decodes as such, with no requirement"):
    for tiers <- DifficultyLoader.loadAllFromJson("""[{"difficulty":"hardcore","storyLocked":true,"themes":["a"]}]""")
    yield
      assert(tiers(Difficulty.Hardcore).storyLocked)
      assertEquals(tiers(Difficulty.Hardcore).requirement, None)

  test("a tier opened by the story cannot also have a requirement"):
    assertFails("""[{"difficulty":"hardcore","storyLocked":true,"requirement":{"winsAt":"hard","wins":1,"shardsSpent":0},"themes":["a"]}]""")

  test("a requirement decodes its tier, win count and Shards spent"):
    for tiers <- DifficultyLoader.loadAllFromJson(fixture)
    yield
      assertEquals(tiers(Difficulty.Normal).requirement, Some(TierRequirement(Difficulty.Easy, 2, 0)))
      assertEquals(tiers(Difficulty.Hard).requirement, Some(TierRequirement(Difficulty.Normal, 3, 50)))

  test("the themes keep their order and their repeats"):
    for tiers <- DifficultyLoader.loadAllFromJson(fixture)
    yield assertEquals(tiers(Difficulty.Hard).themes, List("a", "b", "b"))

  test("an unknown difficulty fails to parse"):
    assertFails("""[{"difficulty":"nightmare","themes":["a"]}]""")

  test("an unknown tier in a requirement fails to parse"):
    assertFails(
      """[{"difficulty":"normal","requirement":{"winsAt":"nightmare","wins":1,"shardsSpent":0},"themes":["a"]}]"""
    )

  test("a tier unlocked by itself or a higher tier fails to parse"):
    assertFails("""[{"difficulty":"normal","requirement":{"winsAt":"normal","wins":1,"shardsSpent":0},"themes":["a"]}]""") *>
      assertFails("""[{"difficulty":"normal","requirement":{"winsAt":"hard","wins":1,"shardsSpent":0},"themes":["a"]}]""")

  test("a requirement of zero wins fails to parse"):
    assertFails("""[{"difficulty":"normal","requirement":{"winsAt":"easy","wins":0,"shardsSpent":0},"themes":["a"]}]""")

  test("a negative Shards requirement fails to parse"):
    assertFails("""[{"difficulty":"normal","requirement":{"winsAt":"easy","wins":1,"shardsSpent":-1},"themes":["a"]}]""")

  test("a tier with no theme fails to parse"):
    assertFails("""[{"difficulty":"easy","themes":[]}]""")

  test("a requirement missing a field fails to parse"):
    assertFails("""[{"difficulty":"normal","requirement":{"winsAt":"easy","wins":1},"themes":["a"]}]""")

  test("a difficulty defined twice fails to parse"):
    assertFails("""[{"difficulty":"easy","themes":["a"]},{"difficulty":"easy","themes":["b"]}]""")

  test("malformed top-level JSON fails to parse"):
    assertFails("not valid json")

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/difficulty.json resource loads successfully"):
    for tiers <- DifficultyLoader.loadAll()
    yield assert(tiers.nonEmpty)
