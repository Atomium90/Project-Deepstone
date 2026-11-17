package roguelite.engine

import roguelite.game.combat.{ AbilityDef, StatCalculator, StatTerm }
import roguelite.game.core.GameEvent
import roguelite.game.items.SetDef
import roguelite.game.progression.{
  AchievementDef,
  AchievementProgress,
  AchievementStats,
  DifficultyTierDef,
  DifficultyUnlocks,
  PerkDef
}
import roguelite.game.story.{ EndingRules, NpcDef, SceneDef, StoryProgress, StoryRules }

/** Everything that completes a [[StateUpdate]] after the pure game state has produced it: the
  * catalogs, the achievements, what the hub says about progress, the resolved ability cost and what
  * only the story knows. Pure, no IO: the session reads its refs and hands the values in.
  *
  * The catalogs are the ones the session was created with.
  */
final class StateUpdateProjections(abilityCatalog: List[AbilityView],
                                   setCatalog: List[SetView],
                                   achievementDefs: Map[String, AchievementDef],
                                   abilityDefs: Map[ClassId, AbilityDef],
                                   setDefs: Map[String, SetDef],
                                   perkDefs: Map[String, PerkDef],
                                   difficultyDefs: Map[Difficulty, DifficultyTierDef],
                                   sceneDefs: Map[String, SceneDef],
                                   npcDefs: Map[String, NpcDef]
):

  private val statCalculator = StatCalculator(setDefs, perkDefs)

  /** The whole enrichment, in the order the session has always applied it. */
  def enrich(update: StateUpdate, state: GameState, progress: AchievementProgress, story: StoryProgress): StateUpdate =
    withStory(
      withStats(
        withAbilityCost(withHubProgress(withAchievements(withCatalog(update), progress), progress, story), state),
        state
      ),
      story
    )

  /** Attach the player's effective stats (see [[StatsView]]) while exploring or fighting, and nothing
    * in the hub or on the game over screen: a hub upgrade's flat bonus only applies once a run begins,
    * so a hub figure would be wrong.
    */
  def withStats(update: StateUpdate, state: GameState): StateUpdate =
    state match
      case exploring: ExplorationState => update.copy(player = update.player.copy(stats = Some(statsView(exploring.player))))
      case fighting: CombatState       => update.copy(player = update.player.copy(stats = Some(statsView(fighting.player))))
      case _                           => update

  private def statsView(player: Player): StatsView =
    def termViews(terms: List[StatTerm]): List[StatTermView] =
      terms.filter(_.value != 0).map: term =>
        val name = term.source.toString
        StatTermView(name.head.toLower + name.tail, term.value, term.affinity)
    StatsView(
      attack = statCalculator.attack(player),
      defense = statCalculator.defense(player),
      critChance = statCalculator.critChance(player),
      critMultiplier = StatCalculator.CritMultiplier,
      abilityCost = abilityDefs.get(player.classId).map(AbilityDef.effectiveCost(player, _, setDefs, perkDefs)),
      attackTerms = termViews(statCalculator.attackTerms(player)),
      defenseTerms = termViews(statCalculator.defenseTerms(player)),
      critTerms = termViews(statCalculator.critTerms(player))
    )

  /** Add what only the story knows: the card still to be shown, if any (see `StateUpdate.pendingCard`),
    * which is read from the story and not from the game state so it is the same in every phase and
    * survives a reconnection, whether the inscription of a relic on offer can be read yet, and what
    * the NPC catalog says of the NPCs on screen: their sprite, and whether the one speaking has given
    * its real name away.
    */
  def withStory(update: StateUpdate, story: StoryProgress): StateUpdate =
    val readable = story.chapter >= StoryRules.RelicReadableFromChapter
    def withSprite(entity: EntityView): EntityView =
      if entity.kind == "npc" then entity.copy(spriteId = npcDefs.get(entity.id).map(_.spriteId)) else entity
    def withName(scene: PendingSceneView): PendingSceneView =
      scene.copy(revealed = npcDefs.get(scene.npc).flatMap(_.revealedBy).forall(story.scenesSeen.contains))
    update.copy(
      room = update.room.map(room => room.copy(entities = room.entities.map(withSprite))),
      pendingScene = update.pendingScene.map(withName),
      pendingEndingChoice =
        update.pendingEndingChoice.map(_.copy(candidates = EndingRules.candidates(npcDefs, sceneDefs, story))),
      pendingCard = story.pendingCard,
      pendingRewardChoice =
        update.pendingRewardChoice.map(choice => choice.copy(relic = choice.relic.map(_.copy(readable = readable))))
    )

  /** Attach how many runs the player has finished, and which difficulty tiers are unlocked, to the
    * hub view (see [[HubView]]), the same "enrich the update with live meta outside the pure state"
    * step as [[withAchievements]]. A no-op outside the hub.
    */
  def withHubProgress(update: StateUpdate, progress: AchievementProgress, story: StoryProgress): StateUpdate =
    update.copy(hub =
      update.hub.map(
        _.copy(runsCompleted = progress.stats.runsCompleted,
               difficulties = difficultyViews(progress.stats, story),
               shopClosed = story.shopClosed
        )
      )
    )

  /** The tiers the hub shows. A tier only the story opens is left out until the story has opened it. */
  def difficultyViews(stats: AchievementStats, story: StoryProgress): List[DifficultyView] =
    Difficulty.values.toList.filter(DifficultyUnlocks.isShown(difficultyDefs, _, story.openedTiers)).map:
      difficulty =>
        DifficultyView(
          id = difficulty,
          unlocked = DifficultyUnlocks.isUnlocked(difficultyDefs, difficulty, stats, story.openedTiers),
          requirement = DifficultyUnlocks.progress(difficultyDefs, difficulty, stats).map:
            p =>
              TierRequirementView(winsAt = p.requirement.winsAt,
                                  wins = p.requirement.wins,
                                  winsDone = p.winsDone,
                                  shardsSpent = p.requirement.shardsSpent,
                                  shardsSpentDone = p.shardsSpentDone
              )
        )

  /** Resolve `CombatView.abilityCost` against the player's live set/perk discounts (see
    * [[roguelite.game.combat.AbilityDef.effectiveCost]]) - a no-op outside combat, or if the player's
    * class has no loaded ability def.
    */
  def withAbilityCost(update: StateUpdate, state: GameState): StateUpdate =
    state match
      case combat: CombatState =>
        abilityDefs.get(combat.player.classId) match
          case None => update
          case Some(ability) =>
            val cost = AbilityDef.effectiveCost(combat.player, ability, setDefs, perkDefs)
            update.copy(combat = update.combat.map(_.copy(abilityCost = Some(cost))))
      case _ => update

  /** Attach the static per-class ability catalog and the equipment set catalog to every outgoing
    * update, so the client never has to hardcode ability names/costs/resource labels or set
    * names/bonus text. See [[AbilityView]] and [[SetView]].
    */
  def withCatalog(update: StateUpdate): StateUpdate =
    update.copy(abilities = abilityCatalog, sets = setCatalog)

  /** Attach the full achievement catalog (locked and unlocked) to every outgoing update, so the
    * client never has to hardcode achievement labels or descriptions. See [[AchievementView]].
    */
  def withAchievements(update: StateUpdate, progress: AchievementProgress): StateUpdate =
    update.copy(achievements = achievementCatalog(progress))

  /** Every achievement, except a hidden one that has not been unlocked yet. */
  def achievementCatalog(progress: AchievementProgress): List[AchievementView] =
    achievementDefs.values.toList
      .filter(d => !d.hidden || progress.isUnlocked(d.id))
      .sortBy(_.displayOrder)
      .map(d => toAchievementView(d, progress.isUnlocked(d.id)))

  def toAchievementView(d: AchievementDef, unlocked: Boolean): AchievementView =
    AchievementView(id = d.id, unlocked = unlocked)

object StateUpdateProjections:

  /** Pure projection of the damage/heal facts in a transition's events - no DB/IO needed, unlike
    * achievements, since nothing here is persisted.
    */
  def toDamageEventViews(events: List[GameEvent]): List[DamageEventView] =
    events.collect {
      case GameEvent.DamageDealt(targetIsPlayer, amount, crit) =>
        DamageEventView(targetIsPlayer = targetIsPlayer, amount = amount, kind = "damage", crit = crit)
      case GameEvent.Healed(amount) =>
        DamageEventView(targetIsPlayer = true, amount = amount, kind = "heal")
    }

  /** Pure projection of the facts worth a client-side sound cue - same rationale as
    * [[toDamageEventViews]]. Plain string tags: the client owns the tag-to-file mapping.
    */
  def toSoundEventTags(events: List[GameEvent]): List[String] =
    events.collect {
      case GameEvent.ItemPickedUp(_, _, _, _, _) => "pickup"
      case GameEvent.LeveledUp(_)                => "level_up"
      case GameEvent.DoorUnlockedWithKey         => "door_unlock"
      case GameEvent.DoorOpened                  => "door_open"
    }

  def toAbilityView(a: AbilityDef): AbilityView =
    AbilityView(
      classId = a.classId,
      id = a.id,
      cost = a.cost
    )

  def toSetView(s: SetDef): SetView =
    SetView(id = s.id,
            classId = s.classId,
            bonus2pc = SetBonusView(s.twoPiece.effect.id, s.twoPiece.effect.value),
            bonus4pc = SetBonusView(s.fourPiece.effect.id, s.fourPiece.effect.value)
    )
