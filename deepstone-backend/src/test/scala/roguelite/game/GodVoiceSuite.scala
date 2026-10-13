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
                 List("start", "death.first", "death.streak", "death.boss", "death.depth", "death.ordinary")
    )

  // --- speaksAtRunStart ---------------------------------------------------------

  test("the voice greets about one run in three"):
    val rng      = Random(42L)
    val greeted  = (1 to 3000).count(_ => GodVoice.speaksAtRunStart(rng))
    assert(greeted > 900 && greeted < 1100, s"expected about 1000 greetings out of 3000, got $greeted")
