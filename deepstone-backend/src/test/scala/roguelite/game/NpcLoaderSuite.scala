package roguelite.game

import cats.effect.IO
import munit.CatsEffectSuite

/** Tests for [[NpcLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/npcs.json` - only the "real catalog" section at the bottom still touches the
  * production file, and only for a size-independent smoke check.
  */
class NpcLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"npc.1","spriteId":"npc_a"},
      |  {"id":"npc.2","spriteId":"npc_b","revealedBy":"npc.2.s3"}
      |]""".stripMargin

  private def assertFails(json: String): IO[Unit] =
    NpcLoader.loadAllFromJson(json).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("loadAllFromJson keys each NPC by its id"):
    for npcs <- NpcLoader.loadAllFromJson(fixture)
    yield assertEquals(npcs.keySet, Set("npc.1", "npc.2"))

  test("the sprite of each NPC decodes"):
    for npcs <- NpcLoader.loadAllFromJson(fixture)
    yield assertEquals(npcs.values.map(npc => npc.id -> npc.spriteId).toMap, Map("npc.1" -> "npc_a", "npc.2" -> "npc_b"))

  test("an NPC that is named from the start has no scene that reveals its name"):
    for npcs <- NpcLoader.loadAllFromJson(fixture)
    yield
      assertEquals(npcs("npc.1").revealedBy, None)
      assertEquals(npcs("npc.2").revealedBy, Some("npc.2.s3"))

  test("an NPC defined twice fails to parse"):
    assertFails("""[{"id":"npc.1","spriteId":"a"},{"id":"npc.1","spriteId":"b"}]""")

  test("an NPC missing its sprite fails to parse"):
    assertFails("""[{"id":"npc.1"}]""")

  test("malformed top-level JSON fails to parse"):
    assertFails("not valid json")

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/npcs.json resource loads successfully"):
    for npcs <- NpcLoader.loadAll()
    yield assert(npcs.nonEmpty)
