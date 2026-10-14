package roguelite.game

import munit.FunSuite

import scala.util.Random

class GodVoiceSuite extends FunSuite:

  private val calm = DeathContext(firstDeath = false, streak = 1, killedByBoss = false, section = 0)

  // --- phase ----------------------------------------------------------------

  test("the voice is a grateful host until chapter 2, possessive for 3 and 4, then pleading"):
    assertEquals(List(0, 1, 2).map(GodVoice.phase), List(VoicePhase.A, VoicePhase.A, VoicePhase.A))
    assertEquals(List(3, 4).map(GodVoice.phase), List(VoicePhase.B, VoicePhase.B))
    assertEquals(List(5, 6).map(GodVoice.phase), List(VoicePhase.C, VoicePhase.C))

  test("a phase is named by its lowercase letter in the text keys"):
    assertEquals(VoicePhase.values.toList.map(_.id), List("a", "b", "c"))

  // --- deathMoment ------------------------------------------------------------

  test("an ordinary enemy in the first section is the plain case"):
    assertEquals(GodVoice.deathMoment(calm, VoicePhase.A), GodMoment.OrdinaryDeath)

  test("a section further down has its own line when no Boss is involved"):
    assertEquals(GodVoice.deathMoment(calm.copy(section = 1), VoicePhase.B), GodMoment.DepthDeath)
    assertEquals(GodVoice.deathMoment(calm.copy(section = 2), VoicePhase.C), GodMoment.DepthDeath)

  test("a Boss comes before depth"):
    assertEquals(GodVoice.deathMoment(calm.copy(killedByBoss = true, section = 2), VoicePhase.A), GodMoment.BossDeath)

  test("a streak of three deaths comes before a Boss, two do not make one"):
    assertEquals(GodVoice.deathMoment(calm.copy(streak = 3, killedByBoss = true), VoicePhase.A), GodMoment.DeathStreak)
    assertEquals(GodVoice.deathMoment(calm.copy(streak = 2, killedByBoss = true), VoicePhase.A), GodMoment.BossDeath)

  test("the first death of a save comes before everything else"):
    val first = calm.copy(firstDeath = true, streak = 5, killedByBoss = true, section = 2)
    assertEquals(GodVoice.deathMoment(first, VoicePhase.A), GodMoment.FirstDeath)

  test("only the first phase has a line for the first death, later phases fall to the next case"):
    val first = calm.copy(firstDeath = true, killedByBoss = true)
    assertEquals(GodVoice.deathMoment(first, VoicePhase.B), GodMoment.BossDeath)
    assertEquals(GodVoice.deathMoment(first, VoicePhase.C), GodMoment.BossDeath)

  test("the moments are named as the client's text keys expect"):
    assertEquals(GodMoment.values.toList.map(_.id),
                 List("start", "death.first", "death.streak", "death.boss", "death.depth", "death.ordinary",
                      "death.end1", "death.end2", "death.end3"
                 )
    )

  // --- speaksAtRunStart ---------------------------------------------------------

  test("the voice greets about one run in three"):
    val rng      = Random(42L)
    val greeted  = (1 to 3000).count(_ => GodVoice.speaksAtRunStart(rng))
    assert(greeted > 900 && greeted < 1100, s"expected about 1000 greetings out of 3000, got $greeted")

  // --- after the end ----------------------------------------------------------------

  private def ended(ending: Option[Ending], said: Set[String] = Set.empty): StoryProgress =
    StoryProgress.empty.copy(ending = ending, scenesSeen = said)

  test("no ending, no line about one"):
    assertEquals(GodVoice.afterEnd(ended(None)), None)

  test("each ending has its own line for the first death after it"):
    assertEquals(GodVoice.afterEnd(ended(Some(Ending.Entered))), Some(GodMoment.EndEntered))
    assertEquals(GodVoice.afterEnd(ended(Some(Ending.Replaced("npc.2")))), Some(GodMoment.EndReplaced))
    assertEquals(GodVoice.afterEnd(ended(Some(Ending.Refused))), Some(GodMoment.EndRefused))
    assertEquals(List(GodMoment.EndEntered, GodMoment.EndReplaced, GodMoment.EndRefused).map(_.id), List("death.end1", "death.end2", "death.end3"))

  test("the line of an ending is said once, and remembered"):
    val before = ended(Some(Ending.Entered))
    val after  = GodVoice.said(before, GodMoment.EndEntered)
    assertEquals(after.scenesSeen, Set("god.end1"))
    assertEquals(GodVoice.afterEnd(after), None)

  test("saying any other line remembers nothing"):
    val progress = ended(Some(Ending.Entered))
    assertEquals(GodVoice.said(progress, GodMoment.OrdinaryDeath), progress)
    assertEquals(GodVoice.said(progress, GodMoment.RunStart), progress)

  test("what the voice said about entering the light does not count for a later refusal"):
    val entered = ended(Some(Ending.Refused), said = Set("god.end1"))
    assertEquals(GodVoice.afterEnd(entered), Some(GodMoment.EndRefused))

  test("the voice greets runs and speaks at deaths, until the light is refused"):
    for ending <- List(None, Some(Ending.Entered), Some(Ending.Replaced("npc.2"))) do
      assert(GodVoice.greetsRuns(ended(ending)))
      assert(GodVoice.speaksAtDeath(ended(ending)))

  test("once the light is refused the voice no longer greets runs, and speaks at one last death"):
    val refused = ended(Some(Ending.Refused))
    assertEquals(GodVoice.greetsRuns(refused), false)
    assertEquals(GodVoice.speaksAtDeath(refused), true)

  test("after its last line the voice is silent for good"):
    val silent = ended(Some(Ending.Refused), said = Set("god.end3"))
    assertEquals(GodVoice.greetsRuns(silent), false)
    assertEquals(GodVoice.speaksAtDeath(silent), false)
