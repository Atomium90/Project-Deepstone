package roguelite.game

import roguelite.engine.Difficulty

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
