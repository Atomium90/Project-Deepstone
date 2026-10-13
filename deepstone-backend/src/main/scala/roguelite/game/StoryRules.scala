package roguelite.game

import roguelite.engine.Difficulty

/** How the story moves forward, as pure transitions of [[StoryProgress]]. */
object StoryRules:

  /** The card the first purchase at the hub earns. */
  val PrologueCard = "prologue"

  /** The card a victory earns by opening `chapter`. */
  def chapterCard(chapter: Int): String = s"ch$chapter"

  /** The chapter reached after a victory on `difficulty`: the next chapter when the victory is on
    * its difficulty or a harder one, never more than one chapter at a time, and the same chapter
    * otherwise.
    */
  def advanceChapter(chapters: Map[Int, ChapterDef], chapter: Int, difficulty: Difficulty): Int =
    chapters.get(chapter + 1) match
      case Some(next) if difficulty.ordinal >= next.minDifficulty.ordinal => chapter + 1
      case _                                                              => chapter

  /** A run is over. A won one advances the chapter, and earns the card of the chapter it opens, any
    * one is remembered for the NPCs to comment on, a death lengthens the streak of deaths and a win
    * ends it, and the vow still running is judged.
    */
  def onRunEnded(chapters: Map[Int, ChapterDef],
                 progress: StoryProgress,
                 outcome: RunOutcome,
                 difficulty: Difficulty
  ): StoryProgress =
    val settled = VowRules.onRunEnded(progress, outcome)
    val reached = if outcome == RunOutcome.Won then advanceChapter(chapters, settled.chapter, difficulty) else settled.chapter
    val streak  = if outcome == RunOutcome.Won then 0 else settled.deathStreak + 1
    val ended   = settled.copy(chapter = reached, lastRun = Some(outcome), deathStreak = streak)
    if reached > settled.chapter then ended.withCardEarned(chapterCard(reached)) else ended

  /** The player has read `encounter` to its last line. Marks a scene as played, the NPC as having
    * commented on its vow, and records the answer to the vow the conversation offered.
    *
    * @param accept
    *   The player's answer to the offered vow, `Some(true)` to take it. It must be given exactly when
    *   the conversation offers one, since the client only knows what it was told: anything else is
    *   refused, and so is taking a vow while another one runs.
    */
  def complete(progress: StoryProgress, encounter: Encounter, accept: Option[Boolean]): Either[String, StoryProgress] =
    (encounter.offeredVow, accept) match
      case (Some(_), None)    => Left("This conversation ends with a vow, which needs an answer")
      case (None, Some(_))    => Left("This conversation offers no vow")
      case (Some(_), Some(true)) if VowRules.hasActive(progress) => Left("Another vow is already running")
      case _ =>
        val answered = accept match
          case Some(taken) =>
            val state = if taken then VowState.Active else VowState.Declined
            progress.copy(vows = progress.vows.updated(encounter.speaker, VowRecord(state)))
          case None => progress
        Right(encounter match
          case Encounter.Scene(scene)         => answered.copy(scenesSeen = answered.scenesSeen + scene.id)
          case Encounter.VowReport(npc, _, _) => if accept.isDefined then answered else VowRules.reported(answered, npc)
          case Encounter.Idle(_, _, _)        => answered
          case Encounter.DevotedBefore(_, _, _) => answered
          case Encounter.DevotedAfter(_)        => answered
        )
