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
                       lastRun: Option[RunOutcome] = None
  ): StoryProgress = StoryProgress(chapter, lastRun, seen, vows)

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
