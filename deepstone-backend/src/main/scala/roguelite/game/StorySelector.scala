package roguelite.game

import scala.util.Random

/** Decides who is in a Rest room and what they say, from the scene catalog and what the story
  * remembers. Pure: the caller keeps the progress and applies the outcome (see [[StoryRules]]).
  */
object StorySelector:

  /** Who stands in a Rest room the player has just walked into.
    *
    * An NPC waiting to comment on a judged vow comes first, so the vow is answered at the very next
    * Rest. Otherwise one of the NPCs that have a scene to play, preferring one the player has not
    * met earlier in this run so two Rests do not show the same face when another has something to
    * say. With nothing to play, the Rest keeps `default`, the NPC its room was authored with.
    *
    * @param metThisRun
    *   The NPCs already met at a Rest of this run.
    */
  def chooseOccupant(scenes: Map[String, SceneDef],
                     progress: StoryProgress,
                     default: String,
                     metThisRun: Set[String],
                     rng: Random
  ): String =
    awaitingReport(progress).getOrElse {
      val withAScene = eligibleScenes(scenes, progress).map(_.npc).distinct.sorted
      val fresh      = withAScene.filterNot(metThisRun.contains)
      val pool       = if fresh.nonEmpty then fresh else withAScene
      if pool.isEmpty then default else pool(rng.nextInt(pool.size))
    }

  /** What `npc` does when the player speaks to them: comment on a judged vow, else play the next
    * scene, else say a short line. The vow is offered again after a comment on a failed one, and
    * after a short line when the player turned it down or failed it.
    */
  def chooseEncounter(scenes: Map[String, SceneDef], progress: StoryProgress, npc: String): Encounter =
    val vow     = VowRules.vowTypes(scenes).get(npc)
    val canOffer = VowRules.canOffer(progress, npc)
    progress.vows.get(npc) match
      case Some(record) if VowRules.awaitsReport(record) =>
        val failed = record.state == VowState.Failed
        Encounter.VowReport(npc, kept = !failed, offer = if failed then vow.filter(_ => !VowRules.hasActive(progress)) else None)
      case _ =>
        eligibleScenes(scenes, progress).filter(_.npc == npc).sortBy(scene => (scene.minChapter, scene.id)).headOption match
          case Some(scene) => Encounter.Scene(scene)
          case None        => Encounter.Idle(npc, idleContext(progress), offer = vow.filter(_ => canOffer))

  /** From this chapter on a Devoted says its second line before the fight: the first is a warning,
    * the second admits that it guards the way out.
    */
  val DevotedLateFromChapter = 4

  /** What the Devoted `devoted` says before the player fights the enemy `enemyId`. */
  def beforeFight(devoted: Int, enemyId: String, progress: StoryProgress): Encounter =
    Encounter.DevotedBefore(devoted, late = progress.chapter >= DevotedLateFromChapter, enemyId)

  /** The scene whose choice and reply texts belong to the vow `encounter` ends by offering, or `None`
    * when it offers none. A scene that offers the vow carries its own texts, any other conversation
    * (a comment on a failed vow, a short line) reuses the texts of the NPC's offering scene.
    */
  def offerScene(scenes: Map[String, SceneDef], encounter: Encounter): Option[String] =
    encounter match
      case _ if encounter.offeredVow.isEmpty => None
      case Encounter.Scene(scene)            => Some(scene.id)
      case other =>
        scenes.values.toList
          .filter(scene => scene.npc == other.speaker && scene.offersVow.isDefined)
          .sortBy(_.id)
          .headOption
          .map(_.id)

  /** The scenes the story can play now: not played yet, asked for a chapter already reached, after the
    * scene they follow, and, for the ones that offer a vow, while no other vow is running. A scene
    * that needs a kept vow waits for the NPC to have commented on it, and one that needs a relic
    * waits for the player to have read it.
    */
  def eligibleScenes(scenes: Map[String, SceneDef], progress: StoryProgress): List[SceneDef] =
    val vowRunning = VowRules.hasActive(progress)
    scenes.values.toList
      .filter { scene =>
        !progress.scenesSeen.contains(scene.id) &&
        scene.minChapter <= progress.chapter &&
        scene.requires.forall(progress.scenesSeen.contains) &&
        (!scene.requiresVowKept || vowKept(progress, scene.npc)) &&
        scene.requiresRelicRead.forall(progress.relicsRead.contains) &&
        (scene.offersVow.isEmpty || !vowRunning)
      }
      .sortBy(_.id)

  private def vowKept(progress: StoryProgress, npc: String): Boolean =
    progress.vows.get(npc).exists(record => record.state == VowState.Kept && record.reported)

  private def awaitingReport(progress: StoryProgress): Option[String] =
    progress.vows.toList.sortBy(_._1).collectFirst { case (npc, record) if VowRules.awaitsReport(record) => npc }

  private def idleContext(progress: StoryProgress): IdleContext = progress.lastRun match
    case Some(RunOutcome.Lost) => IdleContext.AfterDeath
    case Some(RunOutcome.Won)  => IdleContext.AfterVictory
    case None                  => IdleContext.General
