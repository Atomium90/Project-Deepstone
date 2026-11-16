package roguelite.game

import cats.effect.IO
import munit.CatsEffectSuite

/** Tests for [[StoryLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/story.json` - only the "real catalog" section at the bottom still touches the
  * production file, and only for a size-independent smoke check.
  */
class StoryLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"npc.1.s1","npc":"npc.1","minChapter":1},
      |  {"id":"npc.1.s2","npc":"npc.1","minChapter":2,"requires":"npc.1.s1","offersVow":{"type":"NoPotion"}},
      |  {"id":"npc.1.s3","npc":"npc.1","minChapter":5,"requires":"npc.1.s2","requiresVowKept":true},
      |  {"id":"npc.2.s1","npc":"npc.2","minChapter":1},
      |  {"id":"npc.3.s1","npc":"npc.3","minChapter":4,"requiresRelicRead":4}
      |]""".stripMargin

  private def assertFails(json: String): IO[Unit] =
    StoryLoader.loadAllFromJson(json).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("loadAllFromJson keys each scene by its id"):
    for scenes <- StoryLoader.loadAllFromJson(fixture)
    yield assertEquals(scenes.keySet, Set("npc.1.s1", "npc.1.s2", "npc.1.s3", "npc.2.s1", "npc.3.s1"))

  test("a plain scene has no requirement, no vow and no kept-vow condition"):
    for scenes <- StoryLoader.loadAllFromJson(fixture)
    yield assertEquals(scenes("npc.1.s1"), SceneDef("npc.1.s1", "npc.1", 1))

  test("a scene decodes the scene it requires and the vow it offers"):
    for scenes <- StoryLoader.loadAllFromJson(fixture)
    yield assertEquals(scenes("npc.1.s2"),
                       SceneDef("npc.1.s2", "npc.1", 2, requires = Some("npc.1.s1"), offersVow = Some(VowType.NoPotion))
    )

  test("a scene decodes its kept-vow condition"):
    for scenes <- StoryLoader.loadAllFromJson(fixture)
    yield
      assert(scenes("npc.1.s3").requiresVowKept)
      assertEquals(scenes("npc.1.s3").minChapter, 5)

  test("a scene decodes the relic it asks to have been read, and most scenes ask for none"):
    for scenes <- StoryLoader.loadAllFromJson(fixture)
    yield
      assertEquals(scenes("npc.3.s1").requiresRelicRead, Some(4))
      assertEquals(scenes("npc.1.s1").requiresRelicRead, None)

  test("a scene asking for the relic of Devoted 0 fails to parse"):
    assertFails("""[{"id":"npc.1.s1","npc":"npc.1","minChapter":1,"requiresRelicRead":0}]""")

  test("every kind of vow decodes from its name"):
    val names = List("NoPotion", "NoOrdinaryKill", "DefeatElite", "GainTwoLevels", "FindRareItem")
    val json  = names.zipWithIndex.map((name, i) => s"""{"id":"npc.$i.s1","npc":"npc.$i","minChapter":1,"offersVow":{"type":"$name"}}""")
    val decoded = StoryLoader.loadAllFromJson("[" + json.mkString(",") + "]")
    decoded.map(scenes => assertEquals(scenes.values.toList.sortBy(_.id).flatMap(_.offersVow), VowType.values.toList))

  test("an unknown vow type fails to parse"):
    assertFails("""[{"id":"npc.1.s1","npc":"npc.1","minChapter":1,"offersVow":{"type":"Nonsense"}}]""")

  test("a scene id that does not start with its npc id fails to parse"):
    assertFails("""[{"id":"s1","npc":"npc.1","minChapter":1}]""")

  test("a scene asking for chapter 0 fails to parse"):
    assertFails("""[{"id":"npc.1.s1","npc":"npc.1","minChapter":0}]""")

  test("a scene missing a field fails to parse"):
    assertFails("""[{"id":"npc.1.s1","npc":"npc.1"}]""")

  test("a scene id defined twice fails to parse"):
    assertFails(
      """[{"id":"npc.1.s1","npc":"npc.1","minChapter":1},{"id":"npc.1.s1","npc":"npc.1","minChapter":2}]"""
    )

  test("a scene requiring an unknown scene fails to parse"):
    assertFails("""[{"id":"npc.1.s2","npc":"npc.1","minChapter":1,"requires":"npc.1.s1"}]""")

  test("a scene requiring a scene of another npc fails to parse"):
    assertFails(
      """[{"id":"npc.1.s1","npc":"npc.1","minChapter":1},
        | {"id":"npc.2.s1","npc":"npc.2","minChapter":1,"requires":"npc.1.s1"}]""".stripMargin
    )

  test("a scene requiring itself fails to parse"):
    assertFails("""[{"id":"npc.1.s1","npc":"npc.1","minChapter":1,"requires":"npc.1.s1"}]""")

  test("scenes requiring each other in a loop fail to parse"):
    assertFails(
      """[{"id":"npc.1.s1","npc":"npc.1","minChapter":1,"requires":"npc.1.s2"},
        | {"id":"npc.1.s2","npc":"npc.1","minChapter":1,"requires":"npc.1.s1"}]""".stripMargin
    )

  test("a kept-vow condition on an npc that offers no vow fails to parse"):
    assertFails("""[{"id":"npc.1.s1","npc":"npc.1","minChapter":1,"requiresVowKept":true}]""")

  test("malformed top-level JSON fails to parse"):
    assertFails("not valid json")

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/story.json resource loads successfully"):
    for scenes <- StoryLoader.loadAll()
    yield assert(scenes.nonEmpty)
