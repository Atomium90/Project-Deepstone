package roguelite.game

import munit.CatsEffectSuite

/** Tests for [[NpcDialogueLoader]]: JSON parsing and npcs.json content shape. */
class NpcDialogueLoaderSuite extends CatsEffectSuite:

  test("NpcDialogueLoader loads npcs.json and keys each entry by its own id") {
    NpcDialogueLoader
      .loadAll()
      .map:
        defs =>
          defs.foreach { case (id, d) => assertEquals(d.id, id) }
  }

  test("Wren has 3 main lines and a non-empty fallback pool") {
    NpcDialogueLoader
      .loadAll()
      .map:
        defs =>
          val wren = defs("wren")
          assertEquals(wren.name, "Wren")
          assertEquals(wren.dialogue.length, 3)
          assert(wren.fallbackDialogue.nonEmpty)
  }

  test("every npc dialogue entry has a non-empty name and non-empty dialogue lines") {
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
  }
