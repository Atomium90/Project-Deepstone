package roguelite.game

/** Which pool of short lines an NPC with nothing new to say draws from. */
enum IdleContext:
  /** The save has not finished a run. */
  case General
  /** The last finished run was lost. */
  case AfterDeath
  /** The last finished run was won. */
  case AfterVictory

/** What happens when the player speaks to the NPC of a Rest room, or meets a Devoted, chosen by
  * [[StorySelector]] from what the story remembers. Whatever is chosen is kept as the state of the
  * conversation until the player has read it to the end (see [[StoryRules.complete]]).
  */
enum Encounter:
  /** A scene of the story. When it offers a vow, the scene ends with the player's answer. */
  case Scene(scene: SceneDef)

  /** The NPC comments on the vow that was judged since the last visit. A failed vow is offered
    * again (`offer`), a kept one is not.
    */
  case VowReport(npc: String, kept: Boolean, offer: Option[VowType])

  /** Nothing new to say: one short line. A vow the player turned down, or failed, is offered
    * again (`offer`).
    */
  case Idle(npc: String, context: IdleContext, offer: Option[VowType])

  /** A Devoted speaks before the fight, one line, the second one from chapter 4 on (`late`). The fight
    * with the enemy `enemyId` starts once the line has been read.
    */
  case DevotedBefore(devoted: Int, late: Boolean, enemyId: String)

  /** A Devoted speaks as they fall, one line. */
  case DevotedAfter(devoted: Int)

  /** The last scene of an NPC who takes the player's place in the light. The run ends once it has
    * been read.
    */
  case Farewell(npc: String)

  /** Who is speaking: an NPC id such as "npc.1", or "boss.<n>" for the Devoted number n, so that
    * `story.<speaker>.name` is the name shown whichever of the two it is.
    */
  def speaker: String = this match
    case Scene(scene)                => scene.npc
    case VowReport(npc, _, _)        => npc
    case Idle(npc, _, _)             => npc
    case DevotedBefore(devoted, _, _) => s"boss.$devoted"
    case DevotedAfter(devoted)       => s"boss.$devoted"
    case Farewell(npc)               => npc

  /** The vow this conversation ends by offering, if any. Its presence is what makes the player
    * answer yes or no before the conversation counts as over.
    */
  def offeredVow: Option[VowType] = this match
    case Scene(scene)           => scene.offersVow
    case VowReport(_, _, offer) => offer
    case Idle(_, _, offer)      => offer
    case DevotedBefore(_, _, _) => None
    case DevotedAfter(_)        => None
    case Farewell(_)            => None
