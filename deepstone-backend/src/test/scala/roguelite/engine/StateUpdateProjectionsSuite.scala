package roguelite.engine

import munit.FunSuite
import roguelite.game.combat.{ AbilityDef, AbilityEffect }
import roguelite.game.core.GameEvent
import roguelite.game.dungeon.{ Dungeon, Room, RoomType, Tile }
import roguelite.game.items.{ Rarity, Weapon }
import roguelite.game.items.{ SetBonus, SetBonusEffect, SetDef }
import roguelite.game.progression.{ AchievementCondition, AchievementDef, AchievementProgress, AchievementStats }
import roguelite.game.story.StoryProgress

/** The pure enrichment of a [[StateUpdate]] that used to live inside GameSession: the catalogs, the
  * achievement list, the hub progress and the story's card. GameSessionSuite covers them through a
  * whole session, this suite checks each projection on its own.
  */
class StateUpdateProjectionsSuite extends FunSuite:

  private def achievement(id: String, order: Int, hidden: Boolean = false): AchievementDef =
    AchievementDef(id, s"label $id", s"description $id", order, AchievementCondition.FirstKill, hidden)

  private val ability =
    AbilityDef(ClassId.Warrior, "berserker_slash", "Berserker Slash", 40, "Rage", "Hit twice", AbilityEffect.DoubleNextAttack)

  private val fireSet = SetDef(
    "fire_mage",
    "Fire Mage",
    ClassId.Mage,
    SetBonus(SetBonusEffect.FlatAttack(2), "+2 ATK"),
    SetBonus(SetBonusEffect.CritChancePercent(5), "+5% crit")
  )

  private def projections(achievements: List[AchievementDef] = Nil): StateUpdateProjections =
    StateUpdateProjections(
      abilityCatalog = List(StateUpdateProjections.toAbilityView(ability)),
      setCatalog = List(StateUpdateProjections.toSetView(fireSet)),
      achievementDefs = achievements.map(a => a.id -> a).toMap,
      abilityDefs = Map(ClassId.Warrior -> ability),
      setDefs = Map(fireSet.id -> fireSet),
      perkDefs = Map.empty,
      difficultyDefs = Map.empty,
      sceneDefs = Map.empty,
      npcDefs = Map.empty
    )

  private val hubUpdate: StateUpdate = HubState(PlayerFixtures.startingPlayer(ClassId.Warrior)).toStateUpdate()

  test("a damage fact becomes a damage view that keeps the crit flag"):
    assertEquals(
      StateUpdateProjections.toDamageEventViews(List(GameEvent.DamageDealt(targetIsPlayer = false, amount = 12, crit = true))),
      List(DamageEventView(targetIsPlayer = false, amount = 12, kind = "damage", crit = true))
    )

  test("a heal fact becomes a heal view aimed at the player"):
    assertEquals(
      StateUpdateProjections.toDamageEventViews(List(GameEvent.Healed(7))),
      List(DamageEventView(targetIsPlayer = true, amount = 7, kind = "heal"))
    )

  test("facts that carry no damage are left out of the damage views"):
    assertEquals(StateUpdateProjections.toDamageEventViews(List(GameEvent.DoorOpened, GameEvent.LeveledUp(2))), Nil)

  test("level ups and doors map to their sound tags, in the order they happened"):
    val events = List(GameEvent.DoorOpened, GameEvent.LeveledUp(3), GameEvent.DoorUnlockedWithKey, GameEvent.Healed(1))
    assertEquals(StateUpdateProjections.toSoundEventTags(events), List("door_open", "level_up", "door_unlock"))

  test("an ability def projects to the view the client reads"):
    assertEquals(
      StateUpdateProjections.toAbilityView(ability),
      AbilityView(ClassId.Warrior, "berserker_slash", "Berserker Slash", 40, "Rage", "Hit twice")
    )

  test("a set def projects to its two bonus labels and its two bonuses as data"):
    assertEquals(
      StateUpdateProjections.toSetView(fireSet),
      SetView(
        "fire_mage",
        "Fire Mage",
        ClassId.Mage,
        "+2 ATK",
        "+5% crit",
        SetBonusView("FlatAttack", 2),
        SetBonusView("CritChancePercent", 5)
      )
    )

  test("every update carries the ability and set catalogs"):
    val update = projections().withCatalog(hubUpdate)
    assertEquals(update.abilities.map(_.id), List("berserker_slash"))
    assertEquals(update.sets.map(_.id), List("fire_mage"))

  test("the achievement catalog is sorted by display order and marks what is unlocked"):
    val defs     = List(achievement("b", 2), achievement("a", 1))
    val progress = AchievementProgress(Set("b"), AchievementStats.empty)
    assertEquals(
      projections(defs).achievementCatalog(progress),
      List(AchievementView("a", "label a", "description a", unlocked = false),
           AchievementView("b", "label b", "description b", unlocked = true)
      )
    )

  test("a hidden achievement only shows once it is unlocked"):
    val defs = List(achievement("open", 1), achievement("secret", 2, hidden = true))
    assertEquals(projections(defs).achievementCatalog(AchievementProgress.empty).map(_.id), List("open"))
    assertEquals(
      projections(defs).achievementCatalog(AchievementProgress(Set("secret"), AchievementStats.empty)).map(_.id),
      List("open", "secret")
    )

  test("the hub view gets the runs the player has finished and whether the shop is closed"):
    val progress = AchievementProgress(Set.empty, AchievementStats.empty.copy(runsCompleted = 4))
    val hub      = projections().withHubProgress(hubUpdate, progress, StoryProgress()).hub.get
    assertEquals(hub.runsCompleted, 4)
    assertEquals(hub.shopClosed, false)

  test("hub progress changes nothing outside the hub"):
    val noHub = hubUpdate.copy(hub = None)
    assertEquals(projections().withHubProgress(noHub, AchievementProgress.empty, StoryProgress()), noHub)

  test("the ability cost is only resolved during a fight"):
    val update = projections().withAbilityCost(hubUpdate, HubState(PlayerFixtures.startingPlayer(ClassId.Warrior)))
    assertEquals(update, hubUpdate)

  test("the story's oldest unseen card is attached to the update"):
    val story = StoryProgress().withCardEarned("prologue")
    assertEquals(projections().withStory(hubUpdate, story).pendingCard, Some("prologue"))

  private def exploring(player: Player): ExplorationState =
    val room = Room("r", RoomType.Combat, "dungeon", 3, 3, Vector.fill(3)(Vector.fill(3)(Tile.Floor)), Nil)
    ExplorationState(player, Dungeon.fromRooms(List(room)).getOrElse(throw IllegalStateException("test dungeon")), 1, 1)

  test("stats are attached while exploring, with the terms worth something and camelCase sources"):
    val sword  = Weapon("w", "sword", "Sword", Rarity.Common, 6, Some("heavy"))
    val player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(equippedWeapon = Some(sword))
    val state  = exploring(player)
    val stats  = projections().withStats(state.toStateUpdate(), state).player.stats.get
    assertEquals(stats.attack, 5 + 12 + 12)
    assertEquals(stats.attackTerms, List(StatTermView("level", 5), StatTermView("maxHp", 12), StatTermView("weapon", 12, affinity = true)))
    assertEquals(stats.defense, 2)
    assertEquals(stats.defenseTerms, List(StatTermView("level", 2)))
    assertEquals(stats.critChance, 0)
    assertEquals(stats.critTerms, Nil)
    assertEquals(stats.critMultiplier, 1.5)

  test("the ability cost in the stats is the discounted one, absent without an ability def"):
    val state = exploring(PlayerFixtures.startingPlayer(ClassId.Warrior))
    assertEquals(projections().withStats(state.toStateUpdate(), state).player.stats.flatMap(_.abilityCost), Some(40))
    val archer = exploring(PlayerFixtures.startingPlayer(ClassId.Archer))
    assertEquals(projections().withStats(archer.toStateUpdate(), archer).player.stats.map(_.abilityCost), Some(None))

  test("stats are left out of the hub and the game over screen"):
    assertEquals(projections().withStats(hubUpdate, HubState(PlayerFixtures.startingPlayer(ClassId.Warrior))).player.stats, None)
    val over = GameOverState(PlayerFixtures.startingPlayer(ClassId.Warrior))
    assertEquals(projections().withStats(over.toStateUpdate(), over).player.stats, None)

  test("enrich applies the catalogs, the achievements and the story in one go"):
    val defs     = List(achievement("a", 1))
    val progress = AchievementProgress(Set.empty, AchievementStats.empty.copy(runsCompleted = 2))
    val story    = StoryProgress().withCardEarned("prologue")
    val state    = HubState(PlayerFixtures.startingPlayer(ClassId.Warrior))
    val update   = projections(defs).enrich(state.toStateUpdate(), state, progress, story)
    assertEquals(update.abilities.map(_.id), List("berserker_slash"))
    assertEquals(update.achievements.map(_.id), List("a"))
    assertEquals(update.hub.map(_.runsCompleted), Some(2))
    assertEquals(update.pendingCard, Some("prologue"))
