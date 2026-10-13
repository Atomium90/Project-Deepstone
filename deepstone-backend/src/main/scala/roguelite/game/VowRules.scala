package roguelite.game

/** The life of a vow, as pure transitions of [[StoryProgress]].
  *
  * A vow is offered at the end of a scene. Turned down, it stays `Declined` and can be offered
  * again at a later visit. Taken, it is `Active` for a stretch of the run: from the Rest where it was
  * taken to the next Rest, or to the end of the run when there is none. A vow held to (see
  * [[VowType.achievement]]) is failed at once by a fact of the run that goes against it, and kept when
  * the stretch ends. A vow to achieve is kept at once when the player has done what was asked, and
  * failed when the stretch ends first. Either way the NPC comments on it at the next visit
  * (`reported = false` until then), and a failed vow is offered again.
  */
object VowRules:

  /** The vow each NPC offers, from the scenes that offer one. */
  def vowTypes(scenes: Map[String, SceneDef]): Map[String, VowType] =
    scenes.values.flatMap(scene => scene.offersVow.map(scene.npc -> _)).toMap

  /** Only one vow runs at a time. */
  def hasActive(progress: StoryProgress): Boolean =
    progress.vows.values.exists(_.state == VowState.Active)

  /** Whether `event` goes against `vow`, which fails it. */
  def breaks(vow: VowType, event: GameEvent): Boolean = (vow, event) match
    case (VowType.NoPotion, GameEvent.ConsumableUsed(_))                => true
    case (VowType.NoOrdinaryKill, GameEvent.EnemyDefeated(isBoss, _, _, _)) => !isBoss
    case _                                                              => false

  /** Whether `event` is a step towards `vow`, which is kept once `vow.goal` steps are made. */
  def advances(vow: VowType, event: GameEvent): Boolean = (vow, event) match
    case (VowType.DefeatElite, GameEvent.EnemyDefeated(_, _, wasElite, _)) => wasElite
    case (VowType.GainTwoLevels, GameEvent.LeveledUp(_))                   => true
    case (VowType.FindRareItem, GameEvent.ItemPickedUp(_, rarity, _, _, _)) =>
      rarity.ordinal >= Rarity.Rare.ordinal
    case _ => false

  /** Moves the running vow on with the `events` of the run: a fact that goes against it fails it at
    * once, the steps made towards an achievement keep it as soon as there are enough of them.
    */
  def onEvents(progress: StoryProgress, vows: Map[String, VowType], events: List[GameEvent]): StoryProgress =
    val running = for
      (npc, record) <- progress.vows.find((_, record) => record.state == VowState.Active)
      vow           <- vows.get(npc)
    yield (npc, vow)
    running.fold(progress):
      (npc, vow) =>
        def judged(state: VowState) =
          progress.copy(vows = progress.vows.updated(npc, VowRecord(state, reported = false)), vowProgress = 0)
        val steps = progress.vowProgress + events.count(advances(vow, _))
        if events.exists(breaks(vow, _)) then judged(VowState.Failed)
        else if vow.achievement && steps >= vow.goal then judged(VowState.Kept)
        else progress.copy(vowProgress = steps)

  /** The player walked into a Rest: the stretch of the running vow is over. A vow held to was kept, a
    * vow to achieve was not achieved in time.
    */
  def onRestEntered(progress: StoryProgress, vows: Map[String, VowType]): StoryProgress =
    settleActive(progress, vows, vow => !vow.achievement)

  /** The run is over: a running vow held to is kept by winning the run and failed by losing it, a vow to
    * achieve that is still running was not achieved.
    */
  def onRunEnded(progress: StoryProgress, outcome: RunOutcome, vows: Map[String, VowType]): StoryProgress =
    settleActive(progress, vows, vow => !vow.achievement && outcome == RunOutcome.Won)

  /** A vow still running when a session starts belongs to a run that is gone, so it failed. */
  def onSessionStart(progress: StoryProgress): StoryProgress = settleActive(progress, Map.empty, _ => false)

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

  /** Judges the running vow at the end of its stretch: kept when `keptAtEnd` says so of its type, failed
    * otherwise, and failed too when the type is unknown.
    */
  private def settleActive(progress: StoryProgress,
                           vows: Map[String, VowType],
                           keptAtEnd: VowType => Boolean
  ): StoryProgress =
    progress.copy(
      vows = progress.vows.map {
        case (npc, record) if record.state == VowState.Active =>
          val kept = vows.get(npc).exists(keptAtEnd)
          npc -> VowRecord(if kept then VowState.Kept else VowState.Failed, reported = false)
        case other => other
      },
      vowProgress = 0
    )
