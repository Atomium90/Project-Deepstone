package roguelite.game

import munit.CatsEffectSuite

/** Tests for [[NpcDialogueLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/npcs.json` - only the "real catalog" section at the bottom still touches the
  * production file, and only for size-independent checks.
  */
class NpcDialogueLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"test_npc","name":"Test Npc","dialogue":["Line one.","Line two."],"fallbackDialogue":["Fallback one.","Fallback two."]},
      |  {"id":"test_npc_no_fallback","name":"Test Npc 2","dialogue":["Only line."]}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its own id"):
    for defs <- NpcDialogueLoader.loadAllFromJson(fixture)
    yield defs.foreach { case (id, d) => assertEquals(d.id, id) }

  test("dialogue and fallbackDialogue round-trip"):
    for defs <- NpcDialogueLoader.loadAllFromJson(fixture)
    yield
      val npc = defs("test_npc")
      assertEquals(npc.name, "Test Npc")
      assertEquals(npc.dialogue, List("Line one.", "Line two."))
      assertEquals(npc.fallbackDialogue, List("Fallback one.", "Fallback two."))

  test("an omitted fallbackDialogue decodes to an empty list"):
    for defs <- NpcDialogueLoader.loadAllFromJson(fixture)
    yield assertEquals(defs("test_npc_no_fallback").fallbackDialogue, Nil)

  test("an empty dialogue list fails to parse"):
    val bad = """[{"id":"x","name":"X","dialogue":[]}]"""
    NpcDialogueLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent checks only
  // ---------------------------------------------

  test("the real data/npcs.json resource loads successfully"):
    for defs <- NpcDialogueLoader.loadAll()
    yield assert(defs.nonEmpty)

  test("every real npc dialogue entry has a non-empty name and non-empty dialogue lines"):
    NpcDialogueLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            d =>
              assert(d.name.nonEmpty, s"${d.id} has an empty name")
              assert(d.dialogue.nonEmpty, s"${d.id} has an empty dialogue list")
              assert(d.dialogue.forall(_.nonEmpty), s"${d.id} has a blank dialogue line")
              assert(d.fallbackDialogue.forall(_.nonEmpty), s"${d.id} has a blank fallback line")
