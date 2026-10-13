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
    assertEquals(VowRules.onRestEntered(running, vows).vows("npc.1"), VowRecord(VowState.Kept, reported = false))

  test("walking into a Rest leaves the vows that are not running alone"):
    val others = progressWith("npc.1" -> VowRecord(VowState.Declined), "npc.2" -> VowRecord(VowState.Failed))
    assertEquals(VowRules.onRestEntered(others, vows), others)

  test("winning the run keeps the running vow, losing it fails it"):
    assertEquals(VowRules.onRunEnded(running, RunOutcome.Won, vows).vows("npc.1"), VowRecord(VowState.Kept, reported = false))
    assertEquals(VowRules.onRunEnded(running, RunOutcome.Lost, vows).vows("npc.1"), VowRecord(VowState.Failed, reported = false))

  // --- the vows to achieve and the one about ordinary enemies -----------------------------

  private val ordinaryKill = GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = false, wasElite = false, typeId = "goblin")
  private val bossKill     = GameEvent.EnemyDefeated(isBoss = true, tookNoDamage = false, wasElite = false, typeId = "wraith")
  private val eliteKill    = GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = false, wasElite = true, typeId = "goblin")

  private def pickedUp(rarity: Rarity) = GameEvent.ItemPickedUp(false, rarity, false, false, false)

  private def runningVow(vow: VowType) = (progressWith("npc.1" -> VowRecord(VowState.Active)), Map("npc.1" -> vow))

  test("each kind of vow says whether it is held to or achieved, and how many times"):
    assertEquals(VowType.values.toList.filterNot(_.achievement), List(VowType.NoPotion, VowType.NoOrdinaryKill))
    assertEquals(VowType.GainTwoLevels.goal, 2)
    assertEquals(List(VowType.DefeatElite, VowType.FindRareItem).map(_.goal), List(1, 1))

  test("killing an ordinary enemy, a MiniBoss or an Elite goes against the vow of no ordinary kill, a Boss does not"):
    assert(VowRules.breaks(VowType.NoOrdinaryKill, ordinaryKill))
    assert(VowRules.breaks(VowType.NoOrdinaryKill, eliteKill))
    assert(!VowRules.breaks(VowType.NoOrdinaryKill, bossKill))
    assert(!VowRules.breaks(VowType.NoOrdinaryKill, GameEvent.ConsumableUsed("health_potion")))

  test("a vow of no ordinary kill fails at once on such a kill, and a Boss leaves it running"):
    val (progress, vows) = runningVow(VowType.NoOrdinaryKill)
    assertEquals(VowRules.onEvents(progress, vows, List(ordinaryKill)).vows("npc.1"), VowRecord(VowState.Failed, reported = false))
    assertEquals(VowRules.onEvents(progress, vows, List(bossKill)), progress)

  test("only an Elite kill is a step towards defeating an Elite"):
    assert(VowRules.advances(VowType.DefeatElite, eliteKill))
    assert(!VowRules.advances(VowType.DefeatElite, ordinaryKill))
    assert(!VowRules.advances(VowType.DefeatElite, bossKill))

  test("only an item of Rare quality or better is a step towards finding one"):
    assert(!VowRules.advances(VowType.FindRareItem, pickedUp(Rarity.Common)))
    assert(!VowRules.advances(VowType.FindRareItem, pickedUp(Rarity.Uncommon)))
    assert(VowRules.advances(VowType.FindRareItem, pickedUp(Rarity.Rare)))
    assert(VowRules.advances(VowType.FindRareItem, pickedUp(Rarity.Epic)))

  test("every level gained is a step towards gaining two"):
    assert(VowRules.advances(VowType.GainTwoLevels, GameEvent.LeveledUp(3)))
    assert(!VowRules.advances(VowType.GainTwoLevels, eliteKill))

  test("defeating an Elite keeps the vow at once, waiting for its report"):
    val (progress, vows) = runningVow(VowType.DefeatElite)
    assertEquals(VowRules.onEvents(progress, vows, List(ordinaryKill)), progress)
    assertEquals(VowRules.onEvents(progress, vows, List(ordinaryKill, eliteKill)).vows("npc.1"), VowRecord(VowState.Kept, reported = false))

  test("finding an item of Rare quality keeps the vow at once, a lesser one does not"):
    val (progress, vows) = runningVow(VowType.FindRareItem)
    assertEquals(VowRules.onEvents(progress, vows, List(pickedUp(Rarity.Uncommon))), progress)
    assertEquals(VowRules.onEvents(progress, vows, List(pickedUp(Rarity.Rare))).vows("npc.1"), VowRecord(VowState.Kept, reported = false))

  test("gaining two levels takes two steps, across updates or in one"):
    val (progress, vows) = runningVow(VowType.GainTwoLevels)
    val oneLevel = VowRules.onEvents(progress, vows, List(GameEvent.LeveledUp(2)))
    assertEquals(oneLevel.vows("npc.1"), VowRecord(VowState.Active))
    assertEquals(oneLevel.vowProgress, 1)
    assertEquals(VowRules.onEvents(oneLevel, vows, List(GameEvent.LeveledUp(3))).vows("npc.1"), VowRecord(VowState.Kept, reported = false))
    assertEquals(VowRules.onEvents(progress, vows, List(GameEvent.LeveledUp(2), GameEvent.LeveledUp(3))).vows("npc.1"),
                 VowRecord(VowState.Kept, reported = false)
    )

  test("a vow that is kept starts the next one from no steps"):
    val (progress, vows) = runningVow(VowType.GainTwoLevels)
    val kept = VowRules.onEvents(progress, vows, List(GameEvent.LeveledUp(2), GameEvent.LeveledUp(3)))
    assertEquals(kept.vowProgress, 0)

  test("a vow to achieve that was not achieved before the next Rest failed"):
    val (progress, vows) = runningVow(VowType.DefeatElite)
    assertEquals(VowRules.onRestEntered(progress, vows).vows("npc.1"), VowRecord(VowState.Failed, reported = false))

  test("a vow to achieve that was not achieved when the run ends failed, even in a won run"):
    val (progress, vows) = runningVow(VowType.FindRareItem)
    assertEquals(VowRules.onRunEnded(progress, RunOutcome.Won, vows).vows("npc.1"), VowRecord(VowState.Failed, reported = false))
    assertEquals(VowRules.onRunEnded(progress, RunOutcome.Lost, vows).vows("npc.1"), VowRecord(VowState.Failed, reported = false))

  test("a vow held to is kept at the next Rest, and by a won run only"):
    val (progress, vows) = runningVow(VowType.NoOrdinaryKill)
    assertEquals(VowRules.onRestEntered(progress, vows).vows("npc.1"), VowRecord(VowState.Kept, reported = false))
    assertEquals(VowRules.onRunEnded(progress, RunOutcome.Won, vows).vows("npc.1"), VowRecord(VowState.Kept, reported = false))
    assertEquals(VowRules.onRunEnded(progress, RunOutcome.Lost, vows).vows("npc.1"), VowRecord(VowState.Failed, reported = false))

  test("the steps made towards a vow are forgotten when it is judged"):
    val (progress, vows) = runningVow(VowType.GainTwoLevels)
    val oneLevel = VowRules.onEvents(progress, vows, List(GameEvent.LeveledUp(2)))
    assertEquals(VowRules.onRestEntered(oneLevel, vows).vowProgress, 0)
    assertEquals(VowRules.onRunEnded(oneLevel, RunOutcome.Lost, vows).vowProgress, 0)
    assertEquals(VowRules.onSessionStart(oneLevel).vowProgress, 0)

  test("a vow running for an NPC the catalog no longer knows failed when its stretch ends"):
    assertEquals(VowRules.onRestEntered(runningVow(VowType.NoPotion)._1, Map.empty).vows("npc.1"), VowRecord(VowState.Failed, reported = false))

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
