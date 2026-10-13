package roguelite.game

/** Which pool of short lines an NPC with nothing new to say draws from. */
enum IdleContext:
  /** The save has not finished a run. */
  case General
  /** The last finished run was lost. */
  case AfterDeath
  /** The last finished run was won. */
  case AfterVictory

/** What happens when the player speaks to the NPC of a Rest room, chosen by [[StorySelector]] from
  * what the story remembers. Whatever is chosen is kept as the state of the conversation until the
  * player has read it to the end (see [[StoryRules.complete]]).
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

  /** The NPC the player is speaking to. */
  def speaker: String = this match
    case Scene(scene)         => scene.npc
    case VowReport(npc, _, _) => npc
    case Idle(npc, _, _)      => npc

  /** The vow this conversation ends by offering, if any. Its presence is what makes the player
    * answer yes or no before the conversation counts as over.
    */
  def offeredVow: Option[VowType] = this match
    case Scene(scene)           => scene.offersVow
    case VowReport(_, _, offer) => offer
    case Idle(_, _, offer)      => offer
