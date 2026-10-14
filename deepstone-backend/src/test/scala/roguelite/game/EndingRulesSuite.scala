package roguelite.game

import munit.FunSuite
import roguelite.engine.{ Difficulty, EndingAnswer }

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

  // --- what an answer means -------------------------------------------------------

  test("each answer has its own card"):
    assertEquals(EndingRules.endCard(Ending.Entered), "end1")
    assertEquals(EndingRules.endCard(Ending.Replaced("npc.2")), "end2")
    assertEquals(EndingRules.endCard(Ending.Refused), "end3")

  test("entering and refusing name nobody, and are always allowed"):
    assertEquals(EndingRules.ending(EndingAnswer.Enter, None, Nil), Right(Ending.Entered))
    assertEquals(EndingRules.ending(EndingAnswer.Refuse, None, List("npc.2")), Right(Ending.Refused))

  test("letting someone take the place needs one of the candidates the question listed"):
    assertEquals(EndingRules.ending(EndingAnswer.Replace, Some("npc.2"), List("npc.2", "npc.3")), Right(Ending.Replaced("npc.2")))
    assert(EndingRules.ending(EndingAnswer.Replace, Some("npc.5"), List("npc.2")).isLeft)
    assert(EndingRules.ending(EndingAnswer.Replace, Some("npc.2"), Nil).isLeft)
    assert(EndingRules.ending(EndingAnswer.Replace, None, List("npc.2")).isLeft)

  test("entering or refusing while naming an NPC is refused"):
    assert(EndingRules.ending(EndingAnswer.Enter, Some("npc.2"), List("npc.2")).isLeft)
    assert(EndingRules.ending(EndingAnswer.Refuse, Some("npc.2"), List("npc.2")).isLeft)

  // --- what an answer changes ---------------------------------------------------------

  test("entering the light is remembered and brings its card, and nothing else"):
    val after = EndingRules.answered(StoryProgress.empty, Ending.Entered)
    assertEquals(after.ending, Some(Ending.Entered))
    assertEquals(after.cards, List(CardRecord("end1")))
    assertEquals(after.hardcoreUnlocked, false)

  test("refusing the light is remembered, brings its card then the one of Hardcore, and opens Hardcore"):
    val after = EndingRules.answered(StoryProgress.empty, Ending.Refused)
    assertEquals(after.ending, Some(Ending.Refused))
    assertEquals(after.cards, List(CardRecord("end3"), CardRecord("hardcore")))
    assertEquals(after.hardcoreUnlocked, true)
    assertEquals(after.shopClosed, true)

  test("letting someone take the place is remembered at once, but its card waits for their last scene"):
    val after = EndingRules.answered(StoryProgress.empty, Ending.Replaced("npc.3"))
    assertEquals(after.ending, Some(Ending.Replaced("npc.3")))
    assertEquals(after.cards, Nil)
    assertEquals(EndingRules.farewellOver(after).cards, List(CardRecord("end2")))

  test("the last scene of an NPC brings no card unless it was a replacement that was chosen"):
    assertEquals(EndingRules.farewellOver(StoryProgress.empty), StoryProgress.empty)
    val refused = EndingRules.answered(StoryProgress.empty, Ending.Refused)
    assertEquals(EndingRules.farewellOver(refused), refused)

  test("entering the light again does not bring its card back once it was seen"):
    val seen  = EndingRules.answered(StoryProgress.empty, Ending.Entered).withCardSeen("end1")
    val again = EndingRules.answered(seen, Ending.Entered)
    assertEquals(again.pendingCard, None)
    assertEquals(again.cards, List(CardRecord("end1", seen = true)))

  test("a final answer after entering the light replaces the earlier answer"):
    val entered = EndingRules.answered(StoryProgress.empty, Ending.Entered)
    assertEquals(EndingRules.answered(entered, Ending.Refused).ending, Some(Ending.Refused))
