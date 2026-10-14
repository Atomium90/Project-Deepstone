package roguelite.game

import munit.FunSuite
import roguelite.engine.Difficulty

class EndingRulesSuite extends FunSuite:

  private def story(chapter: Int, ending: Option[Ending] = None): StoryProgress =
    StoryProgress.empty.copy(chapter = chapter, ending = ending)

  // --- when the question is asked --------------------------------------------

  test("a victory asks the question once the chapter before the last is reached, on Hard"):
    assert(EndingRules.isOffered(story(5), Difficulty.Hard))
    assert(EndingRules.isOffered(story(6), Difficulty.Hard))

  test("a victory in an earlier chapter does not ask it"):
    assertEquals(EndingRules.isOffered(story(4), Difficulty.Hard), false)
    assertEquals(EndingRules.isOffered(story(0), Difficulty.Hard), false)

  test("a victory on an easier difficulty than Hard does not ask it, however far the story went"):
    assertEquals(EndingRules.isOffered(story(6), Difficulty.Normal), false)
    assertEquals(EndingRules.isOffered(story(6), Difficulty.Easy), false)

  test("entering the light does not close the question, it is asked again"):
    assert(EndingRules.isOffered(story(6, Some(Ending.Entered)), Difficulty.Hard))

  test("a final answer closes the question for good"):
    assertEquals(EndingRules.isOffered(story(6, Some(Ending.Replaced("npc.2"))), Difficulty.Hard), false)
    assertEquals(EndingRules.isOffered(story(6, Some(Ending.Refused)), Difficulty.Hard), false)

  // --- who could take the place ------------------------------------------------

  private val npcs: Map[String, NpcDef] = List(
    NpcDef("npc.1", "a"),
    NpcDef("npc.2", "b", candidate = true),
    NpcDef("npc.3", "c", candidate = true),
    NpcDef("npc.4", "d", candidate = true)
  ).map(npc => npc.id -> npc).toMap

  private val scenes: Map[String, SceneDef] = List(
    SceneDef("npc.1.s1", "npc.1", 1),
    SceneDef("npc.2.s1", "npc.2", 1),
    SceneDef("npc.2.s2", "npc.2", 2),
    SceneDef("npc.3.s1", "npc.3", 1)
  ).map(scene => scene.id -> scene).toMap

  private def seen(ids: String*): StoryProgress = StoryProgress.empty.copy(scenesSeen = ids.toSet)

  test("a candidate whose scenes have all been played is ready"):
    assertEquals(EndingRules.candidates(npcs, scenes, seen("npc.2.s1", "npc.2.s2")), List("npc.2"))

  test("a candidate with a scene left to play is not ready"):
    assertEquals(EndingRules.candidates(npcs, scenes, seen("npc.2.s1")), Nil)

  test("an NPC that is not a candidate is never ready, whatever it has played"):
    assertEquals(EndingRules.candidates(npcs, scenes, seen("npc.1.s1")), Nil)

  test("a candidate with no scene at all is never ready"):
    assertEquals(EndingRules.candidates(npcs, scenes, seen()), Nil)

  test("several ready candidates come in id order"):
    assertEquals(EndingRules.candidates(npcs, scenes, seen("npc.3.s1", "npc.2.s1", "npc.2.s2")), List("npc.2", "npc.3"))
