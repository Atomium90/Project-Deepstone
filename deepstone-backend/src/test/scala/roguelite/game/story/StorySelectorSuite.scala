package roguelite.game

import munit.FunSuite

import scala.util.Random

/** Tests for [[StorySelector]], against a catalog of two NPCs of its own: npc.1 follows Wren's shape
  * (a chain of scenes, a vow offered by the second, a last scene that needs it kept), npc.2 has two
  * scenes and a vow too.
  */
class StorySelectorSuite extends FunSuite:

  private val scenes: Map[String, SceneDef] = List(
    SceneDef("npc.1.s1", "npc.1", 1),
    SceneDef("npc.1.s2", "npc.1", 2, requires = Some("npc.1.s1"), offersVow = Some(VowType.NoPotion)),
    SceneDef("npc.1.s3", "npc.1", 3, requires = Some("npc.1.s2")),
    SceneDef("npc.1.s4", "npc.1", 5, requires = Some("npc.1.s3"), requiresVowKept = true),
    SceneDef("npc.2.s1", "npc.2", 1),
    SceneDef("npc.2.s2", "npc.2", 2, requires = Some("npc.2.s1"), offersVow = Some(VowType.NoPotion))
  ).map(scene => scene.id -> scene).toMap

  private def progress(chapter: Int = 0,
                       seen: Set[String] = Set.empty,
                       vows: Map[String, VowRecord] = Map.empty,
                       lastRun: Option[RunOutcome] = None,
                       relicsRead: Set[Int] = Set.empty,
                       ending: Option[Ending] = None
  ): StoryProgress = StoryProgress(chapter, lastRun, seen, vows, relicsRead = relicsRead, ending = ending)

  private def ids(list: List[SceneDef]): List[String] = list.map(_.id)

  // --- eligibleScenes -------------------------------------------------------

  test("nothing can be played before the first chapter"):
    assertEquals(StorySelector.eligibleScenes(scenes, progress()), Nil)

  test("the first scene of every NPC can be played once chapter 1 is reached"):
    assertEquals(ids(StorySelector.eligibleScenes(scenes, progress(chapter = 1))), List("npc.1.s1", "npc.2.s1"))

  test("a scene played is not offered again"):
    val seen = progress(chapter = 1, seen = Set("npc.1.s1"))
    assertEquals(ids(StorySelector.eligibleScenes(scenes, seen)), List("npc.2.s1"))

  test("a scene waits for the scene it follows"):
    assert(!ids(StorySelector.eligibleScenes(scenes, progress(chapter = 3))).contains("npc.1.s3"))

  test("a scene waits for its chapter, however many scenes were played"):
    val atChapterOne = progress(chapter = 1, seen = Set("npc.1.s1"))
    assert(!ids(StorySelector.eligibleScenes(scenes, atChapterOne)).contains("npc.1.s2"))

  test("a scene that needs a kept vow waits for the NPC to have commented on it"):
    val base = progress(chapter = 5, seen = Set("npc.1.s1", "npc.1.s2", "npc.1.s3"))
    def withVow(record: VowRecord) = base.copy(vows = Map("npc.1" -> record))
    assert(!ids(StorySelector.eligibleScenes(scenes, withVow(VowRecord(VowState.Kept, reported = false)))).contains("npc.1.s4"))
    assert(!ids(StorySelector.eligibleScenes(scenes, withVow(VowRecord(VowState.Failed)))).contains("npc.1.s4"))
    assert(ids(StorySelector.eligibleScenes(scenes, withVow(VowRecord(VowState.Kept)))).contains("npc.1.s4"))

  test("a scene that offers a vow waits while another vow runs"):
    val running = progress(chapter = 2, seen = Set("npc.1.s1", "npc.2.s1"), vows = Map("npc.3" -> VowRecord(VowState.Active)))
    assertEquals(ids(StorySelector.eligibleScenes(scenes, running)), Nil)

  // --- chooseOccupant -------------------------------------------------------

  test("with nothing to play the Rest keeps the NPC its room was authored with"):
    assertEquals(StorySelector.chooseOccupant(scenes, progress(), "npc.1", Set.empty, Random(0L)), "npc.1")

  test("an NPC with a scene to play is chosen over the default"):
    val occupant = StorySelector.chooseOccupant(scenes, progress(chapter = 1, seen = Set("npc.1.s1")), "npc.1", Set.empty, Random(0L))
    assertEquals(occupant, "npc.2")

  test("an NPC waiting to comment on a vow comes before the ones with a scene"):
    val waiting = progress(chapter = 1, vows = Map("npc.2" -> VowRecord(VowState.Kept, reported = false)))
    assertEquals(StorySelector.chooseOccupant(scenes, waiting, "npc.1", Set.empty, Random(0L)), "npc.2")

  test("an NPC already met in this run gives way to one with a scene who was not"):
    // both have a first scene to play, the one met already is left out: a guaranteed outcome
    assertEquals(StorySelector.chooseOccupant(scenes, progress(chapter = 1), "npc.1", Set("npc.1"), Random(0L)), "npc.2")

  test("an NPC already met in this run is chosen again when nobody else has a scene"):
    val onlyFirst = progress(chapter = 1, seen = Set("npc.2.s1"))
    assertEquals(StorySelector.chooseOccupant(scenes, onlyFirst, "npc.2", Set("npc.1"), Random(0L)), "npc.1")
    assertEquals(StorySelector.chooseOccupant(scenes, onlyFirst, "npc.2", Set("npc.1", "npc.2"), Random(0L)), "npc.1")

  test("every NPC with a scene can be chosen"):
    // successive draws of one generator: consecutive seeds give nearly the same first draw
    val rng    = Random(42L)
    val chosen = (1 to 20).map(_ => StorySelector.chooseOccupant(scenes, progress(chapter = 1), "npc.1", Set.empty, rng)).toSet
    assertEquals(chosen, Set("npc.1", "npc.2"))

  // --- the relic a scene waits for --------------------------------------------------

  private val waitingForARelic = Map(
    "npc.3.s1" -> SceneDef("npc.3.s1", "npc.3", 1, requiresRelicRead = Some(4))
  )

  test("a scene that asks for a relic waits until it has been read"):
    assertEquals(ids(StorySelector.eligibleScenes(waitingForARelic, progress(chapter = 3))), Nil)
    assertEquals(ids(StorySelector.eligibleScenes(waitingForARelic, progress(chapter = 3, relicsRead = Set(1, 2)))), Nil)
    assertEquals(ids(StorySelector.eligibleScenes(waitingForARelic, progress(chapter = 3, relicsRead = Set(4)))), List("npc.3.s1"))

  test("a relic read does not make a scene play before its chapter"):
    assertEquals(ids(StorySelector.eligibleScenes(Map("npc.3.s1" -> SceneDef("npc.3.s1", "npc.3", 4, requiresRelicRead = Some(4))),
                                                  progress(chapter = 3, relicsRead = Set(4)))), Nil)

  // --- beforeFight ------------------------------------------------------------

  test("a Devoted gives its first line before chapter 4 and its second from chapter 4 on"):
    assertEquals(StorySelector.beforeFight(2, "e1", progress(chapter = 0)), Encounter.DevotedBefore(2, late = false, enemyId = "e1"))
    assertEquals(StorySelector.beforeFight(2, "e1", progress(chapter = 3)), Encounter.DevotedBefore(2, late = false, enemyId = "e1"))
    assertEquals(StorySelector.beforeFight(2, "e1", progress(chapter = 4)), Encounter.DevotedBefore(2, late = true, enemyId = "e1"))
    assertEquals(StorySelector.beforeFight(2, "e1", progress(chapter = 6)), Encounter.DevotedBefore(2, late = true, enemyId = "e1"))

  // --- offerScene -----------------------------------------------------------

  test("a scene that offers a vow carries the texts of its own choice"):
    assertEquals(StorySelector.offerScene(scenes, Encounter.Scene(scenes("npc.1.s2"))), Some("npc.1.s2"))

  test("a scene that offers no vow has no scene for a choice"):
    assertEquals(StorySelector.offerScene(scenes, Encounter.Scene(scenes("npc.1.s1"))), None)

  test("the line of a Devoted offers no vow"):
    assertEquals(StorySelector.offerScene(scenes, Encounter.DevotedBefore(1, late = false, enemyId = "e1")), None)
    assertEquals(StorySelector.offerScene(scenes, Encounter.DevotedAfter(1)), None)

  test("a comment on a failed vow, or a short line, reuse the texts of the NPC's offering scene"):
    val report = Encounter.VowReport("npc.2", kept = false, offer = Some(VowType.NoPotion))
    val line   = Encounter.Idle("npc.1", IdleContext.General, Some(VowType.NoPotion))
    assertEquals(StorySelector.offerScene(scenes, report), Some("npc.2.s2"))
    assertEquals(StorySelector.offerScene(scenes, line), Some("npc.1.s2"))

  test("a conversation that offers no vow has no scene for a choice"):
    assertEquals(StorySelector.offerScene(scenes, Encounter.Idle("npc.1", IdleContext.General, None)), None)

  // --- chooseEncounter ------------------------------------------------------

  test("an NPC plays its next scene"):
    assertEquals(StorySelector.chooseEncounter(scenes, progress(chapter = 1), "npc.1"), Encounter.Scene(scenes("npc.1.s1")))

  test("an NPC with no scene to play says a short line, drawn from the pool of the last run"):
    def idle(lastRun: Option[RunOutcome]) = StorySelector.chooseEncounter(scenes, progress(lastRun = lastRun), "npc.1")
    assertEquals(idle(None), Encounter.Idle("npc.1", IdleContext.General, None))
    assertEquals(idle(Some(RunOutcome.Lost)), Encounter.Idle("npc.1", IdleContext.AfterDeath, None))
    assertEquals(idle(Some(RunOutcome.Won)), Encounter.Idle("npc.1", IdleContext.AfterVictory, None))

  test("a kept vow is commented on before anything else, and not offered again"):
    val kept = progress(chapter = 1, vows = Map("npc.1" -> VowRecord(VowState.Kept, reported = false)))
    assertEquals(StorySelector.chooseEncounter(scenes, kept, "npc.1"), Encounter.VowReport("npc.1", kept = true, offer = None))

  test("a failed vow is commented on and offered again"):
    val failed = progress(vows = Map("npc.1" -> VowRecord(VowState.Failed, reported = false)))
    assertEquals(StorySelector.chooseEncounter(scenes, failed, "npc.1"),
                 Encounter.VowReport("npc.1", kept = false, offer = Some(VowType.NoPotion))
    )

  test("a failed vow is not offered again while another vow runs"):
    val busy = progress(vows = Map("npc.1" -> VowRecord(VowState.Failed, reported = false), "npc.2" -> VowRecord(VowState.Active)))
    assertEquals(StorySelector.chooseEncounter(scenes, busy, "npc.1"), Encounter.VowReport("npc.1", kept = false, offer = None))

  test("a vow turned down is offered again with a short line"):
    val declined = progress(vows = Map("npc.1" -> VowRecord(VowState.Declined)))
    assertEquals(StorySelector.chooseEncounter(scenes, declined, "npc.1"),
                 Encounter.Idle("npc.1", IdleContext.General, Some(VowType.NoPotion))
    )

  test("a failed vow already commented on is offered again with a short line"):
    val failed = progress(vows = Map("npc.1" -> VowRecord(VowState.Failed)))
    assertEquals(StorySelector.chooseEncounter(scenes, failed, "npc.1").offeredVow, Some(VowType.NoPotion))

  test("a vow is not offered again once kept, while it runs, or when another one runs"):
    def offerFor(vows: Map[String, VowRecord]) = StorySelector.chooseEncounter(scenes, progress(vows = vows), "npc.1").offeredVow
    assertEquals(offerFor(Map("npc.1" -> VowRecord(VowState.Kept))), None)
    assertEquals(offerFor(Map("npc.1" -> VowRecord(VowState.Active))), None)
    assertEquals(offerFor(Map("npc.1" -> VowRecord(VowState.Declined), "npc.2" -> VowRecord(VowState.Active))), None)
    assertEquals(offerFor(Map.empty), None)

  // --- after the end -------------------------------------------------------------

  private val withAdventurer: Map[String, SceneDef] = scenes + ("npc.4.s1" -> SceneDef("npc.4.s1", "npc.4", 1))

  private val npcs: Map[String, NpcDef] = List(
    NpcDef("npc.1", "a"),
    NpcDef("npc.2", "b", candidate = true),
    NpcDef("npc.4", "d", epilogue = true)
  ).map(npc => npc.id -> npc).toMap

  private val allSeen = Set("npc.1.s1", "npc.1.s2", "npc.1.s3", "npc.1.s4", "npc.2.s1", "npc.2.s2", "npc.4.s1")

  test("an NPC who took the player's place has no scene left to play"):
    val gone = progress(chapter = 1, ending = Some(Ending.Replaced("npc.2")))
    assertEquals(ids(StorySelector.eligibleScenes(scenes, gone)), List("npc.1.s1"))

  test("an NPC who took the player's place is not waited for to comment on a vow"):
    val gone = progress(chapter = 1, vows = Map("npc.2" -> VowRecord(VowState.Kept, reported = false)), ending = Some(Ending.Replaced("npc.2")))
    assertEquals(StorySelector.chooseOccupant(scenes, gone, "npc.9", Set.empty, Random(0L)), "npc.1")

  test("of two NPCs waiting to comment on a vow, the one who is still there is met, in id order"):
    val both = progress(
      chapter = 1,
      vows = Map("npc.1" -> VowRecord(VowState.Kept, reported = false), "npc.2" -> VowRecord(VowState.Kept, reported = false)),
      ending = Some(Ending.Replaced("npc.2"))
    )
    assertEquals(StorySelector.chooseOccupant(scenes, both, "npc.9", Set.empty, Random(0L)), "npc.1")
    assertEquals(StorySelector.chooseOccupant(scenes, both.copy(ending = None), "npc.9", Set.empty, Random(0L)), "npc.1")

  test("an NPC whose story is over has something to say about each ending, if the catalog gives them an epilogue"):
    for ending <- List(Ending.Entered, Ending.Replaced("npc.2"), Ending.Refused) do
      val done = progress(chapter = 6, seen = allSeen, ending = Some(ending))
      assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, done, "npc.4"), Some(ending))

  test("nothing is said about an ending before the light was answered, before the NPC's story is over, or by an NPC with no epilogue"):
    assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, progress(chapter = 6, seen = allSeen), "npc.4"), None)
    assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, progress(chapter = 6, ending = Some(Ending.Entered)), "npc.4"), None)
    assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, progress(chapter = 6, seen = allSeen, ending = Some(Ending.Entered)), "npc.1"), None)

  test("after entering the light or letting someone take the place, the epilogue is said at every meeting"):
    for ending <- List(Ending.Entered, Ending.Replaced("npc.2")) do
      val said = progress(chapter = 6, seen = allSeen + EndingRules.epilogueMarker("npc.4", ending), ending = Some(ending))
      assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, said, "npc.4"), Some(ending))

  test("after refusing the light, the epilogue is said once and the NPC is gone"):
    val before = progress(chapter = 6, seen = allSeen, ending = Some(Ending.Refused))
    val after  = before.copy(scenesSeen = allSeen + EndingRules.epilogueMarker("npc.4", Ending.Refused))
    assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, before, "npc.4"), Some(Ending.Refused))
    assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, after, "npc.4"), None)

  test("what was said about entering the light does not count for refusing it afterwards"):
    val entered = progress(chapter = 6, seen = allSeen + EndingRules.epilogueMarker("npc.4", Ending.Entered), ending = Some(Ending.Refused))
    assertEquals(StorySelector.epilogueDue(npcs, withAdventurer, entered, "npc.4"), Some(Ending.Refused))

  test("an NPC with the epilogue due says it instead of a short line, and their scenes still come first"):
    val done = progress(chapter = 6, seen = allSeen, ending = Some(Ending.Entered))
    assertEquals(StorySelector.chooseEncounter(withAdventurer, done, "npc.4", npcs), Encounter.Epilogue("npc.4", Ending.Entered))
    val unfinished = progress(chapter = 6, seen = allSeen - "npc.4.s1", ending = Some(Ending.Entered))
    assert(StorySelector.chooseEncounter(withAdventurer, unfinished, "npc.4", npcs).isInstanceOf[Encounter.Scene])

  test("a short line stays what an NPC says when there is no epilogue to say"):
    val noEnding = progress(chapter = 6, seen = allSeen)
    assert(StorySelector.chooseEncounter(withAdventurer, noEnding, "npc.4", npcs).isInstanceOf[Encounter.Idle])
    val wren = progress(chapter = 6, seen = allSeen, ending = Some(Ending.Entered))
    assert(StorySelector.chooseEncounter(withAdventurer, wren, "npc.1", npcs).isInstanceOf[Encounter.Idle])

  test("a Rest seats the NPC whose epilogue is due when nobody has a scene to play, and not once they are gone"):
    val due  = progress(chapter = 6, seen = allSeen, ending = Some(Ending.Refused))
    val gone = due.copy(scenesSeen = allSeen + EndingRules.epilogueMarker("npc.4", Ending.Refused))
    assertEquals(StorySelector.chooseOccupant(withAdventurer, due, "npc.1", Set.empty, Random(0L), npcs), "npc.4")
    assertEquals(StorySelector.chooseOccupant(withAdventurer, gone, "npc.1", Set.empty, Random(0L), npcs), "npc.1")
