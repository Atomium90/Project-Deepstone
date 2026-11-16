package roguelite.game

import cats.effect.IO
import munit.CatsEffectSuite
import roguelite.engine.Difficulty

/** Tests for [[ChapterLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/chapters.json` - only the "real catalog" section at the bottom still touches
  * the production file, and only for a size-independent smoke check.
  */
class ChapterLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":1,"minDifficulty":"easy"},
      |  {"id":2,"minDifficulty":"normal"},
      |  {"id":3,"minDifficulty":"hard"}
      |]""".stripMargin

  private def assertFails(json: String): IO[Unit] =
    ChapterLoader.loadAllFromJson(json).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("loadAllFromJson keys each chapter by its id"):
    for chapters <- ChapterLoader.loadAllFromJson(fixture)
    yield assertEquals(chapters.keySet, Set(1, 2, 3))

  test("each chapter decodes the difficulty it asks for"):
    for chapters <- ChapterLoader.loadAllFromJson(fixture)
    yield
      assertEquals(chapters(1).minDifficulty, Difficulty.Easy)
      assertEquals(chapters(2).minDifficulty, Difficulty.Normal)
      assertEquals(chapters(3).minDifficulty, Difficulty.Hard)

  test("the ids may come in any order"):
    val shuffled = """[{"id":2,"minDifficulty":"easy"},{"id":1,"minDifficulty":"easy"}]"""
    for chapters <- ChapterLoader.loadAllFromJson(shuffled)
    yield assertEquals(chapters.keySet, Set(1, 2))

  test("an unknown difficulty fails to parse"):
    assertFails("""[{"id":1,"minDifficulty":"nightmare"}]""")

  test("a gap in the ids fails to parse"):
    assertFails("""[{"id":1,"minDifficulty":"easy"},{"id":3,"minDifficulty":"easy"}]""")

  test("ids that do not start at 1 fail to parse"):
    assertFails("""[{"id":2,"minDifficulty":"easy"}]""")

  test("a repeated id fails to parse"):
    assertFails("""[{"id":1,"minDifficulty":"easy"},{"id":1,"minDifficulty":"hard"}]""")

  test("a chapter missing a field fails to parse"):
    assertFails("""[{"id":1}]""")

  test("malformed top-level JSON fails to parse"):
    assertFails("not valid json")

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/chapters.json resource loads successfully"):
    for chapters <- ChapterLoader.loadAll()
    yield assert(chapters.nonEmpty)
