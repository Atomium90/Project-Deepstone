package roguelite.game

/** The life of a vow, as pure transitions of [[StoryProgress]].
  *
  * A vow is offered at the end of a scene. Turned down, it stays `Declined` and can be offered
  * again at a later visit. Taken, it is `Active` for a stretch of the run: from the Rest where it was
  * taken to the next Rest, or to the end of the run when there is none. A fact of the run that goes
  * against it fails it at once, otherwise it is kept when the stretch ends. Either way the NPC
  * comments on it at the next visit (`reported = false` until then), and a failed vow is offered
  * again.
  */
object VowRules:

  /** The vow each NPC offers, from the scenes that offer one. */
  def vowTypes(scenes: Map[String, SceneDef]): Map[String, VowType] =
    scenes.values.flatMap(scene => scene.offersVow.map(scene.npc -> _)).toMap

  /** Only one vow runs at a time. */
  def hasActive(progress: StoryProgress): Boolean =
    progress.vows.values.exists(_.state == VowState.Active)

  /** Whether `event` goes against `vow`. */
  def breaks(vow: VowType, event: GameEvent): Boolean = (vow, event) match
    case (VowType.NoPotion, GameEvent.ConsumableUsed(_)) => true
    case _                                               => false

  /** Fails the running vow when one of the `events` of the run goes against it. */
  def onEvents(progress: StoryProgress, vows: Map[String, VowType], events: List[GameEvent]): StoryProgress =
    progress.vows
      .collectFirst { case (npc, record) if record.state == VowState.Active => npc }
      .filter(npc => vows.get(npc).exists(vow => events.exists(breaks(vow, _))))
      .fold(progress)(npc => progress.copy(vows = progress.vows.updated(npc, VowRecord(VowState.Failed, reported = false))))

  /** The player walked into a Rest: the stretch of the running vow is over, and it was kept. */
  def onRestEntered(progress: StoryProgress): StoryProgress = settleActive(progress, kept = true)

  /** The run is over: a running vow is kept by winning the run, and failed by losing it. */
  def onRunEnded(progress: StoryProgress, outcome: RunOutcome): StoryProgress =
    settleActive(progress, kept = outcome == RunOutcome.Won)

  /** A vow still running when a session starts belongs to a run that is gone, so it failed. */
  def onSessionStart(progress: StoryProgress): StoryProgress = settleActive(progress, kept = false)

  /** The NPC has commented on its judged vow. */
  def reported(progress: StoryProgress, npc: String): StoryProgress =
    progress.vows.get(npc) match
      case Some(record) => progress.copy(vows = progress.vows.updated(npc, record.copy(reported = true)))
      case None         => progress

  /** Whether the NPC's judged vow has not been commented on yet. */
  def awaitsReport(record: VowRecord): Boolean =
    !record.reported && (record.state == VowState.Kept || record.state == VowState.Failed)

  /** Whether the NPC can offer its vow (again): the player turned it down, or failed it, and no other
    * vow is running.
    */
  def canOffer(progress: StoryProgress, npc: String): Boolean =
    !hasActive(progress) &&
      progress.vows.get(npc).exists(record => record.state == VowState.Declined || record.state == VowState.Failed)

  private def settleActive(progress: StoryProgress, kept: Boolean): StoryProgress =
    val settled = if kept then VowState.Kept else VowState.Failed
    progress.copy(vows = progress.vows.map {
      case (npc, record) if record.state == VowState.Active => npc -> VowRecord(settled, reported = false)
      case other                                            => other
    })
