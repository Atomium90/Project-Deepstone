package roguelite.game

import scala.util.Random

/** The tone of the god's voice, which follows how far the story has gone: a grateful host (`A`, from
  * the prologue to chapter 2), then a possessive one (`B`, chapters 3 and 4), then one afraid of
  * being forgotten (`C`, chapters 5 and 6).
  */
enum VoicePhase:
  case A, B, C

  /** How the client names the phase in its text keys. */
  def id: String = toString.toLowerCase

/** The moments the voice speaks apart from the chapter cards. `id` is how the client names the
  * moment in its text keys (`story.god.<id>.<phase>.<n>`).
  */
enum GodMoment(val id: String):
  case RunStart      extends GodMoment("start")
  case FirstDeath    extends GodMoment("death.first")
  case DeathStreak   extends GodMoment("death.streak")
  case BossDeath     extends GodMoment("death.boss")
  case DepthDeath    extends GodMoment("death.depth")
  case OrdinaryDeath extends GodMoment("death.ordinary")

/** What the voice reacts to when the player dies.
  *
  * @param firstDeath
  *   Whether this is the first death of the whole save.
  * @param streak
  *   How many runs in a row ended in death, this one included.
  * @param killedByBoss
  *   Whether the player fell in a Boss room. A MiniBoss counts as an ordinary enemy.
  * @param section
  *   The section the player had reached, counted from 0.
  */
case class DeathContext(firstDeath: Boolean, streak: Int, killedByBoss: Boolean, section: Int)

/** When the god speaks and which kind of line it says, as pure functions. Which of the lines of that
  * kind is read is for the client to pick, so it can avoid saying the last one again.
  */
object GodVoice:

  /** A streak of deaths this long, or longer, is worth a remark. */
  val StreakFrom = 3

  /** The voice greets a run one time in this many, so it does not wear thin. */
  val RunStartOneIn = 3

  /** The phase of the voice once `chapter` has been reached. */
  def phase(chapter: Int): VoicePhase =
    if chapter <= 2 then VoicePhase.A else if chapter <= 4 then VoicePhase.B else VoicePhase.C

  /** The line for a death. When several apply the first of these wins: the first death of the save
    * (a line only the first phase has), a streak, a Boss, a section further down, any other enemy.
    */
  def deathMoment(context: DeathContext, phase: VoicePhase): GodMoment =
    if context.firstDeath && phase == VoicePhase.A then GodMoment.FirstDeath
    else if context.streak >= StreakFrom then GodMoment.DeathStreak
    else if context.killedByBoss then GodMoment.BossDeath
    else if context.section >= 1 then GodMoment.DepthDeath
    else GodMoment.OrdinaryDeath

  /** Whether the voice greets the run that is starting. */
  def speaksAtRunStart(rng: Random): Boolean = rng.nextInt(RunStartOneIn) == 0
