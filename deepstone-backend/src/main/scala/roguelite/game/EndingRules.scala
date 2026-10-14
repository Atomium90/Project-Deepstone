package roguelite.game

import roguelite.engine.{ Difficulty, EndingAnswer }

/** When the player is asked what to do with the light of the Sanctuary, and who could take their
  * place. Pure functions of what the story remembers.
  */
object EndingRules:

  /** The chapter that must already be reached for a victory to ask the question: the victory that
    * opens the last chapter, and every one after it, ask it.
    */
  val OfferedFromChapter = 5

  /** The lowest difficulty a victory asks the question on, the one the last chapters belong to. */
  val OfferedFromDifficulty: Difficulty = Difficulty.Hard

  /** Whether the Sanctuary of a won run asks the player to answer the light, instead of ending the
    * run. It does from the last chapters on, on the hardest difficulties, for as long as the
    * player has not given a final answer (entering the light is asked again).
    */
  def isOffered(progress: StoryProgress, difficulty: Difficulty): Boolean =
    progress.chapter >= OfferedFromChapter &&
    difficulty.ordinal >= OfferedFromDifficulty.ordinal &&
    !progress.endingIsFinal

  /** The card each answer brings, shown full screen once the answer is given (once, for an answer that
    * is given again).
    */
  def endCard(ending: Ending): String = ending match
    case Ending.Entered     => "end1"
    case Ending.Replaced(_) => "end2"
    case Ending.Refused     => "end3"

  /** What the player's answer means, or why it is refused. Only letting someone take the player's
    * place names an NPC, and it must be one of the `candidates` the question listed.
    */
  def ending(answer: EndingAnswer, npc: Option[String], candidates: List[String]): Either[String, Ending] =
    (answer, npc) match
      case (EndingAnswer.Enter, None)                                    => Right(Ending.Entered)
      case (EndingAnswer.Refuse, None)                                   => Right(Ending.Refused)
      case (EndingAnswer.Replace, Some(who)) if candidates.contains(who) => Right(Ending.Replaced(who))
      case (EndingAnswer.Replace, Some(_))                               => Left("That one cannot take your place.")
      case (EndingAnswer.Replace, None)                                  => Left("Say who takes your place.")
      case (_, Some(_))                                                  => Left("Only letting someone take your place names who.")

  /** The story once the player has given `ending` as their answer. Entering the light and refusing it
    * bring their card at once, and refusing also opens the Hardcore tier, which has a card of its own
    * after the one of the answer. Letting someone take the place brings its card only when that NPC
    * has said their last scene (see [[farewellOver]]).
    */
  def answered(progress: StoryProgress, ending: Ending): StoryProgress =
    val remembered = progress.copy(ending = Some(ending))
    ending match
      case Ending.Replaced(_) => remembered
      case Ending.Entered     => remembered.withCardEarned(endCard(ending))
      case Ending.Refused     => StoryRules.unlockHardcore(remembered.withCardEarned(endCard(ending)))

  /** The NPC who took the player's place has said their last scene: the card of that answer is earned. */
  def farewellOver(progress: StoryProgress): StoryProgress =
    progress.ending match
      case Some(ending @ Ending.Replaced(_)) => progress.withCardEarned(endCard(ending))
      case _                                 => progress

  /** The NPCs who could take the player's place: the ones the catalog marks as candidates whose story
    * is over, that is every scene of theirs played. An NPC with no scene is never ready. In id order,
    * which is the order the player is shown them in.
    */
  def candidates(npcs: Map[String, NpcDef], scenes: Map[String, SceneDef], progress: StoryProgress): List[String] =
    npcs.values.toList
      .filter(_.candidate)
      .map(_.id)
      .sorted
      .filter:
        id =>
          val own = scenes.values.filter(_.npc == id)
          own.nonEmpty && own.forall(scene => progress.scenesSeen.contains(scene.id))
