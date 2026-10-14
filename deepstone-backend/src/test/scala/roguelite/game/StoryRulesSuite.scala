package roguelite.game

import munit.FunSuite
import roguelite.engine.Difficulty

class StoryRulesSuite extends FunSuite:

  private val chapters: Map[Int, ChapterDef] = List(
    ChapterDef(1, Difficulty.Easy),
    ChapterDef(2, Difficulty.Easy),
    ChapterDef(3, Difficulty.Normal),
    ChapterDef(4, Difficulty.Hard)
  ).map(chapter => chapter.id -> chapter).toMap

  private val offering = SceneDef("npc.1.s2", "npc.1", 2, offersVow = Some(VowType.NoPotion))
  private val plain    = SceneDef("npc.1.s1", "npc.1", 1)

  // --- advanceChapter -------------------------------------------------------

  test("a victory on the difficulty of the next chapter opens it"):
    assertEquals(StoryRules.advanceChapter(chapters, 0, Difficulty.Easy), 1)
    assertEquals(StoryRules.advanceChapter(chapters, 2, Difficulty.Normal), 3)

  test("a victory on a harder difficulty than the next chapter asks for opens it too"):
    assertEquals(StoryRules.advanceChapter(chapters, 0, Difficulty.Hard), 1)

  test("a victory on an easier difficulty than the next chapter asks for opens nothing"):
    assertEquals(StoryRules.advanceChapter(chapters, 2, Difficulty.Easy), 2)
    assertEquals(StoryRules.advanceChapter(chapters, 3, Difficulty.Normal), 3)

  test("a victory opens one chapter at a time"):
    assertEquals(StoryRules.advanceChapter(chapters, 0, Difficulty.Hard), 1)
    assertEquals(StoryRules.advanceChapter(chapters, 1, Difficulty.Hard), 2)

  test("no chapter opens after the last one"):
    assertEquals(StoryRules.advanceChapter(chapters, 4, Difficulty.Hard), 4)

  // --- onRunEnded -----------------------------------------------------------

  test("a won run opens the next chapter and is remembered"):
    val after = StoryRules.onRunEnded(chapters, StoryProgress(chapter = 1), RunOutcome.Won, Difficulty.Easy)
    assertEquals(after.chapter, 2)
    assertEquals(after.lastRun, Some(RunOutcome.Won))

  test("a lost run opens nothing and is remembered"):
    val after = StoryRules.onRunEnded(chapters, StoryProgress(chapter = 1), RunOutcome.Lost, Difficulty.Hard)
    assertEquals(after.chapter, 1)
    assertEquals(after.lastRun, Some(RunOutcome.Lost))

  test("a won run that opens a chapter earns the card of that chapter"):
    val after = StoryRules.onRunEnded(chapters, StoryProgress(chapter = 1), RunOutcome.Won, Difficulty.Easy)
    assertEquals(after.cards, List(CardRecord("ch2")))

  test("a win that opens nothing and a lost run earn no card"):
    assertEquals(StoryRules.onRunEnded(chapters, StoryProgress(chapter = 2), RunOutcome.Won, Difficulty.Easy).cards, Nil)
    assertEquals(StoryRules.onRunEnded(chapters, StoryProgress(chapter = 1), RunOutcome.Lost, Difficulty.Hard).cards, Nil)

  test("a victory opens the next chapter and earns its card, without ending anything"):
    val after = StoryRules.openChapter(chapters, StoryProgress(chapter = 3), Difficulty.Hard)
    assertEquals(after.chapter, 4)
    assertEquals(after.cards, List(CardRecord("ch4")))
    assertEquals(after.lastRun, None)
    assertEquals(after.deathStreak, 0)

  test("a victory opens no chapter when the difficulty is too low, or when the last one is reached"):
    val reached = StoryProgress(chapter = 2)
    assertEquals(StoryRules.openChapter(chapters, reached, Difficulty.Easy), reached)
    assertEquals(StoryRules.openChapter(chapters, StoryProgress(chapter = 2), Difficulty.Easy).cards, Nil)
    val last = StoryProgress(chapter = 4)
    assertEquals(StoryRules.openChapter(chapters, last, Difficulty.Hard), last)

  test("the last scene of an NPC who took the player's place earns the card of that answer"):
    val replaced = StoryProgress(ending = Some(Ending.Replaced("npc.3")))
    val after    = StoryRules.complete(replaced, Encounter.Farewell("npc.3"), None).toOption.get
    assertEquals(after.cards, List(CardRecord("end2")))

  test("an epilogue said is remembered for that ending, and offers no vow"):
    val encounter = Encounter.Epilogue("npc.4", Ending.Refused)
    val after     = StoryRules.complete(StoryProgress.empty, encounter, None).toOption.get
    assertEquals(after.scenesSeen, Set("npc.4.epilogue.end3"))
    assertEquals(encounter.offeredVow, None)
    assertEquals(encounter.speaker, "npc.4")
    assert(StoryRules.complete(StoryProgress.empty, encounter, Some(true)).isLeft)

  test("a farewell offers no vow, and is refused when one is answered"):
    assertEquals(Encounter.Farewell("npc.3").offeredVow, None)
    assertEquals(Encounter.Farewell("npc.3").speaker, "npc.3")
    assert(StoryRules.complete(StoryProgress.empty, Encounter.Farewell("npc.3"), Some(true)).isLeft)

  test("a card is named after its chapter, and the first purchase has its own"):
    assertEquals(StoryRules.chapterCard(3), "ch3")
    assertEquals(StoryRules.PrologueCard, "prologue")

  test("deaths in a row are counted and a win ends the streak"):
    val first  = StoryRules.onRunEnded(chapters, StoryProgress(), RunOutcome.Lost, Difficulty.Easy)
    val second = StoryRules.onRunEnded(chapters, first, RunOutcome.Lost, Difficulty.Easy)
    assertEquals((first.deathStreak, second.deathStreak), (1, 2))
    assertEquals(StoryRules.onRunEnded(chapters, second, RunOutcome.Won, Difficulty.Easy).deathStreak, 0)

  test("the story opening Hardcore opens the flag and earns the announcement card"):
    val after = StoryRules.unlockHardcore(StoryProgress.empty)
    assert(after.hardcoreUnlocked)
    assertEquals(after.pendingCard, Some("hardcore"))
    assertEquals(after.openedTiers, Set(Difficulty.Hardcore))

  test("opening Hardcore again changes nothing, and does not bring a card already seen back"):
    val seen = StoryRules.unlockHardcore(StoryProgress.empty).withCardSeen(StoryRules.HardcoreCard)
    assertEquals(StoryRules.unlockHardcore(seen), seen)
    assertEquals(StoryRules.unlockHardcore(seen).pendingCard, None)

  test("the end of a run judges the vow that was running"):
    val running = StoryProgress(vows = Map("npc.1" -> VowRecord(VowState.Active)))
    val vows = Map("npc.1" -> VowType.NoPotion)
    assertEquals(StoryRules.onRunEnded(chapters, running, RunOutcome.Won, Difficulty.Easy, vows).vows("npc.1"),
                 VowRecord(VowState.Kept, reported = false)
    )
    assertEquals(StoryRules.onRunEnded(chapters, running, RunOutcome.Lost, Difficulty.Easy, vows).vows("npc.1"),
                 VowRecord(VowState.Failed, reported = false)
    )

  // --- complete -------------------------------------------------------------

  test("reading a scene to its last line marks it as played"):
    assertEquals(StoryRules.complete(StoryProgress.empty, Encounter.Scene(plain), None), Right(StoryProgress(scenesSeen = Set("npc.1.s1"))))

  test("a scene that offers a vow needs an answer, and taking it starts the vow"):
    assert(StoryRules.complete(StoryProgress.empty, Encounter.Scene(offering), None).isLeft)
    val taken = StoryRules.complete(StoryProgress.empty, Encounter.Scene(offering), Some(true)).toOption.get
    assertEquals(taken.vows("npc.1"), VowRecord(VowState.Active))
    assertEquals(taken.scenesSeen, Set("npc.1.s2"))

  test("taking a vow starts it from no steps, even if the last one left some behind"):
    val taken = StoryRules.complete(StoryProgress(vowProgress = 1), Encounter.Scene(offering), Some(true)).toOption.get
    assertEquals(taken.vowProgress, 0)

  test("turning a vow down records it as declined"):
    val declined = StoryRules.complete(StoryProgress.empty, Encounter.Scene(offering), Some(false)).toOption.get
    assertEquals(declined.vows("npc.1"), VowRecord(VowState.Declined))

  test("the line of a Devoted changes nothing in the story once read"):
    val progress = StoryProgress(chapter = 3, scenesSeen = Set("npc.1.s1"))
    assertEquals(StoryRules.complete(progress, Encounter.DevotedBefore(1, late = false, enemyId = "e1"), None), Right(progress))
    assertEquals(StoryRules.complete(progress, Encounter.DevotedAfter(1), None), Right(progress))

  test("a Devoted's line offers no vow, so an answer to one is refused"):
    assert(StoryRules.complete(StoryProgress.empty, Encounter.DevotedBefore(1, late = true, enemyId = "e1"), Some(true)).isLeft)
    assert(StoryRules.complete(StoryProgress.empty, Encounter.DevotedAfter(1), Some(false)).isLeft)

  test("a Devoted speaks as boss.<n>, which is what names it in the texts"):
    assertEquals(Encounter.DevotedBefore(4, late = false, enemyId = "e1").speaker, "boss.4")
    assertEquals(Encounter.DevotedAfter(6).speaker, "boss.6")

  test("an answer to a vow that was not offered is refused"):
    assert(StoryRules.complete(StoryProgress.empty, Encounter.Scene(plain), Some(true)).isLeft)
    assert(StoryRules.complete(StoryProgress.empty, Encounter.Idle("npc.1", IdleContext.General, None), Some(false)).isLeft)

  test("a vow cannot be taken while another one runs"):
    val busy = StoryProgress(vows = Map("npc.2" -> VowRecord(VowState.Active)))
    assert(StoryRules.complete(busy, Encounter.Scene(offering), Some(true)).isLeft)
    assert(StoryRules.complete(busy, Encounter.Scene(offering), Some(false)).isRight)

  test("commenting on a kept vow marks it as reported"):
    val judged = StoryProgress(vows = Map("npc.1" -> VowRecord(VowState.Kept, reported = false)))
    val after  = StoryRules.complete(judged, Encounter.VowReport("npc.1", kept = true, offer = None), None).toOption.get
    assertEquals(after.vows("npc.1"), VowRecord(VowState.Kept))

  test("commenting on a failed vow ends with the answer to its new offer"):
    val judged = StoryProgress(vows = Map("npc.1" -> VowRecord(VowState.Failed, reported = false)))
    val report = Encounter.VowReport("npc.1", kept = false, offer = Some(VowType.NoPotion))
    assert(StoryRules.complete(judged, report, None).isLeft)
    assertEquals(StoryRules.complete(judged, report, Some(true)).toOption.get.vows("npc.1"), VowRecord(VowState.Active))
    assertEquals(StoryRules.complete(judged, report, Some(false)).toOption.get.vows("npc.1"), VowRecord(VowState.Declined))

  test("a failed vow with nothing to offer is still marked as reported"):
    val judged = StoryProgress(vows = Map("npc.1" -> VowRecord(VowState.Failed, reported = false)))
    val after  = StoryRules.complete(judged, Encounter.VowReport("npc.1", kept = false, offer = None), None).toOption.get
    assertEquals(after.vows("npc.1"), VowRecord(VowState.Failed))

  test("a short line leaves the progress alone, or answers the vow it offered again"):
    val declined = StoryProgress(vows = Map("npc.1" -> VowRecord(VowState.Declined)))
    val line     = Encounter.Idle("npc.1", IdleContext.General, Some(VowType.NoPotion))
    assertEquals(StoryRules.complete(StoryProgress.empty, Encounter.Idle("npc.1", IdleContext.General, None), None), Right(StoryProgress.empty))
    assertEquals(StoryRules.complete(declined, line, Some(true)).toOption.get.vows("npc.1"), VowRecord(VowState.Active))
