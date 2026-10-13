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

/** A card the player has earned, shown full screen until they have seen it.
  *
  * @param id
  *   Which card, e.g. "prologue" or "ch1".
  * @param seen
  *   False from the moment the card is earned until the player has read it, so a card that was
  *   never read is shown again the next time.
  */
case class CardRecord(id: String, seen: Boolean = false)

/** What the story remembers between runs, loaded from SQLite at session start and kept in a
  * `Ref[IO, StoryProgress]` inside [[roguelite.engine.GameSession]], the story counterpart of
  * [[AchievementProgress]]. Persisted across dedicated tables (`story_state`, `story_scene_seen`,
  * `story_vow`, `story_card`, `story_death_streak`, `story_relic_read`), see [[roguelite.db.Database]].
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
  * @param cards
  *   The cards earned, in the order they were earned, never removed.
  * @param deathStreak
  *   How many runs in a row ended in death, 0 after a win. The god's voice remarks on a long one.
  * @param relicsRead
  *   The Devoted whose relic has been read: they fell while the inscription could be read. A later
  *   scene can ask for it, never removed.
  */
case class StoryProgress(
    chapter: Int = 0,
    lastRun: Option[RunOutcome] = None,
    scenesSeen: Set[String] = Set.empty,
    vows: Map[String, VowRecord] = Map.empty,
    cards: List[CardRecord] = Nil,
    deathStreak: Int = 0,
    relicsRead: Set[Int] = Set.empty
):

  /** The relic of the Devoted `devoted` has been read. */
  def withRelicRead(devoted: Int): StoryProgress = copy(relicsRead = relicsRead + devoted)

  /** The card to show next: the oldest one the player has not seen yet. */
  def pendingCard: Option[String] = cards.find(!_.seen).map(_.id)

  /** A card is earned. A card the save already has, seen or not, is left as it is. */
  def withCardEarned(id: String): StoryProgress =
    if cards.exists(_.id == id) then this else copy(cards = cards :+ CardRecord(id))

  /** The player has seen a card. A card that was never earned stays unknown. */
  def withCardSeen(id: String): StoryProgress =
    copy(cards = cards.map(card => if card.id == id then card.copy(seen = true) else card))

object StoryProgress:
  val empty: StoryProgress = StoryProgress()
