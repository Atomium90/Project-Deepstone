package roguelite.game

import munit.FunSuite

class VowRulesSuite extends FunSuite:

  private val vows: Map[String, VowType] = Map("npc.1" -> VowType.NoPotion)

  private def progressWith(vows: (String, VowRecord)*): StoryProgress = StoryProgress(vows = vows.toMap)

  private val running = progressWith("npc.1" -> VowRecord(VowState.Active))

  test("the vow of each NPC comes from the scene that offers it"):
    val scenes = List(SceneDef("npc.1.s1", "npc.1", 1), SceneDef("npc.1.s2", "npc.1", 2, offersVow = Some(VowType.NoPotion)))
    assertEquals(VowRules.vowTypes(scenes.map(s => s.id -> s).toMap), Map("npc.1" -> VowType.NoPotion))

  test("drinking a potion goes against the no-potion vow, nothing else does"):
    assert(VowRules.breaks(VowType.NoPotion, GameEvent.ConsumableUsed("health_potion")))
    assert(!VowRules.breaks(VowType.NoPotion, GameEvent.LeveledUp(2)))
    assert(!VowRules.breaks(VowType.NoPotion, GameEvent.DoorOpened))

  test("a fact that goes against the running vow fails it at once, waiting for its report"):
    val after = VowRules.onEvents(running, vows, List(GameEvent.DoorOpened, GameEvent.ConsumableUsed("health_potion")))
    assertEquals(after.vows("npc.1"), VowRecord(VowState.Failed, reported = false))

  test("facts that do not go against the running vow leave it running"):
    assertEquals(VowRules.onEvents(running, vows, List(GameEvent.DoorOpened, GameEvent.LeveledUp(2))), running)

  test("a potion drunk with no vow running changes nothing"):
    val declined = progressWith("npc.1" -> VowRecord(VowState.Declined))
    assertEquals(VowRules.onEvents(declined, vows, List(GameEvent.ConsumableUsed("health_potion"))), declined)
    assertEquals(VowRules.onEvents(StoryProgress.empty, vows, List(GameEvent.ConsumableUsed("health_potion"))), StoryProgress.empty)

  test("walking into a Rest keeps the running vow, waiting for its report"):
    assertEquals(VowRules.onRestEntered(running).vows("npc.1"), VowRecord(VowState.Kept, reported = false))

  test("walking into a Rest leaves the vows that are not running alone"):
    val others = progressWith("npc.1" -> VowRecord(VowState.Declined), "npc.2" -> VowRecord(VowState.Failed))
    assertEquals(VowRules.onRestEntered(others), others)

  test("winning the run keeps the running vow, losing it fails it"):
    assertEquals(VowRules.onRunEnded(running, RunOutcome.Won).vows("npc.1"), VowRecord(VowState.Kept, reported = false))
    assertEquals(VowRules.onRunEnded(running, RunOutcome.Lost).vows("npc.1"), VowRecord(VowState.Failed, reported = false))

  test("a vow still running when a session starts failed, its run being gone"):
    assertEquals(VowRules.onSessionStart(running).vows("npc.1"), VowRecord(VowState.Failed, reported = false))

  test("commenting on a judged vow marks it as reported"):
    val judged = progressWith("npc.1" -> VowRecord(VowState.Kept, reported = false))
    assertEquals(VowRules.reported(judged, "npc.1").vows("npc.1"), VowRecord(VowState.Kept))

  test("commenting on the vow of an NPC that has none changes nothing"):
    assertEquals(VowRules.reported(StoryProgress.empty, "npc.1"), StoryProgress.empty)

  test("only a judged vow that was not commented on yet waits for its report"):
    assert(VowRules.awaitsReport(VowRecord(VowState.Kept, reported = false)))
    assert(VowRules.awaitsReport(VowRecord(VowState.Failed, reported = false)))
    assert(!VowRules.awaitsReport(VowRecord(VowState.Kept)))
    assert(!VowRules.awaitsReport(VowRecord(VowState.Active)))
    assert(!VowRules.awaitsReport(VowRecord(VowState.Declined)))

  test("a vow can be offered again after being turned down or failed, never while one runs"):
    assert(VowRules.canOffer(progressWith("npc.1" -> VowRecord(VowState.Declined)), "npc.1"))
    assert(VowRules.canOffer(progressWith("npc.1" -> VowRecord(VowState.Failed)), "npc.1"))
    assert(!VowRules.canOffer(progressWith("npc.1" -> VowRecord(VowState.Kept)), "npc.1"))
    assert(!VowRules.canOffer(StoryProgress.empty, "npc.1"))
    assert(!VowRules.canOffer(progressWith("npc.1" -> VowRecord(VowState.Declined), "npc.2" -> VowRecord(VowState.Active)), "npc.1"))
