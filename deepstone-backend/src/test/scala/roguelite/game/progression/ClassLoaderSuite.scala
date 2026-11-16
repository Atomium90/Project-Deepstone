package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[ClassLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/classes.json` - only the "real catalog" section at the bottom still touches the
  * production file, for a size-independent smoke check plus a couple of standing design rules that
  * are deliberately pinned to production content (see each test's comment).
  */
class ClassLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"classId":"warrior","hp":100,"resourceMax":50,"resourceStart":0,"affinityTags":["heavy"],"startingKit":["a","b"]},
      |  {"classId":"archer","hp":80,"resourceMax":40,"resourceStart":20,"affinityTags":["ranged"],"startingKit":["c"]},
      |  {"classId":"mage","hp":60,"resourceMax":60,"resourceStart":60,"affinityTags":["magic"],"startingKit":[]}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its classId"):
    for defs <- ClassLoader.loadAllFromJson(fixture)
    yield assertEquals(defs.keySet, Set(ClassId.Warrior, ClassId.Archer, ClassId.Mage))

  test("every field round-trips through decoding"):
    for defs <- ClassLoader.loadAllFromJson(fixture)
    yield
      val w = defs(ClassId.Warrior)
      assertEquals(w.hp, 100)
      assertEquals(w.resourceMax, 50)
      assertEquals(w.resourceStart, 0)
      assertEquals(w.affinityTags, Set("heavy"))
      assertEquals(w.startingKit, List("a", "b"))

  test("an omitted startingKit decodes to an empty list"):
    for defs <- ClassLoader.loadAllFromJson(fixture)
    yield assertEquals(defs(ClassId.Mage).startingKit, Nil)

  test("an unknown classId fails to parse"):
    val bad = """[{"classId":"paladin","hp":1,"resourceMax":1,"resourceStart":0,"affinityTags":[],"startingKit":[]}]"""
    ClassLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a missing required field fails to parse"):
    val bad = """[{"classId":"warrior","resourceMax":1,"resourceStart":0,"affinityTags":[],"startingKit":[]}]"""
    ClassLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent checks, plus standing design rules deliberately pinned to
  // production content
  // ---------------------------------------------

  test("the real data/classes.json resource loads successfully"):
    for defs <- ClassLoader.loadAll()
    yield assertEquals(defs.size, 3)

  test("every class has a non-empty starting kit"):
    ClassLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            cd => assert(cd.startingKit.nonEmpty, s"${cd.classId} has an empty startingKit")

  // Regression: no class's starting kit grants a potion anymore - the only sources of a starting
  // potion are the potion_start hub upgrade and the Well Stocked perk, so at most 2 can stack
  // instead of 3 (see the potion belt stacking rework). This is a deliberate content rule, not a
  // decode-mechanism check, so it stays pinned to the real catalog.
  test("no starting kit includes a potion"):
    ClassLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            cd => assert(!cd.startingKit.contains("health_potion"), s"${cd.classId} still starts with a potion")
