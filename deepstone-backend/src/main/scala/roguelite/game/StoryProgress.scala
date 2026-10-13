package roguelite.game

/** How a run ended, as far as the story is concerned. */
enum RunOutcome:
  case Won, Lost

/** Where an NPC's vow stands. A vow is offered in a scene, then either refused (`Declined`) or
  * taken (`Active`, tracked until the next Rest or the end of the run), then judged (`Kept` or
  * `Failed`).
  */
enum VowState:
  case Declined, Active, Kept, Failed

/** One NPC's vow.
  *
  * @param reported
  *   False from the moment the vow is judged until the NPC has commented on it at a Rest, so an NPC
  *   waiting to comment can be met first. Always true for a vow that is not judged.
  */
case class VowRecord(state: VowState, reported: Boolean = true)

/** What the story remembers between runs, loaded from SQLite at session start and kept in a
  * `Ref[IO, StoryProgress]` inside [[roguelite.engine.GameSession]], the story counterpart of
  * [[AchievementProgress]]. Persisted across dedicated tables (`story_state`, `story_scene_seen`,
  * `story_vow`), see [[roguelite.db.Database]].
  *
  * @param chapter
  *   The last chapter reached, 0 before the first one. Scenes ask for a minimum chapter.
  * @param lastRun
  *   How the last finished run ended, `None` on a save that has not finished one. Lets an NPC
  *   comment on it.
  * @param scenesSeen
  *   Ids of the scenes played to their last line, never removed.
  * @param vows
  *   The vow of each NPC that has offered one, keyed by NPC id.
  */
case class StoryProgress(
    chapter: Int = 0,
    lastRun: Option[RunOutcome] = None,
    scenesSeen: Set[String] = Set.empty,
    vows: Map[String, VowRecord] = Map.empty
)

object StoryProgress:
  val empty: StoryProgress = StoryProgress()
