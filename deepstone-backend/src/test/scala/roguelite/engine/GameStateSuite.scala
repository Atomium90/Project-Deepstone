package roguelite.engine

import munit.FunSuite
import roguelite.game.*

/** Covers the view-projection logic in GameState.scala (`toStateUpdate` on each of the 4 states,
  * plus the private helpers it delegates to - `itemToView`/`keyKindLabel`/`pendingChoiceToView`/
  * `equipmentToView`). These are only ever exercised indirectly elsewhere (StateMachineSuite,
  * GameSessionSuite), so this suite targets the projection itself: every key-kind label, the
  * durable pendingEquipChoice projection, and the potion-belt stack count field.
  */
class GameStateSuite extends FunSuite:

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w):
      (row, col) =>
        if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  def makeRoom(id: String, entities: List[Entity] = Nil): Room =
    Room(id, RoomType.Combat, 8, 6, makeTiles(), entities)

  def dungeon: Dungeon = Dungeon(Map("r1" -> makeRoom("r1")), "r1")

  val weapon: Weapon = Weapon("w1", "practice_sword", "Practice Sword", Rarity.Common, attackBonus = 3, setId = Some("light_soldier"))
  val armor: Armor   = Armor("a1", "steel_plate", "Steel Plate", Rarity.Uncommon, defenseBonus = 2, typeTag = Some("heavy"))

  // --- HubState --------------------------------------------------------------

  test("HubState.toStateUpdate has HUB phase and forwards the upgrade/perk catalogs"):
    val upgrade = UpgradeDef("hp_boost_1", "Vitality", "More HP", cost = 30, displayOrder = 0, icon = "+",
                             category = UpgradeCategory.Stat, effect = UpgradeEffect.MaxHpBoost(20)
    )
    val perk = PerkDef("heavy_hand", "Heavy Hand", "+1 damage", icon = "*", effect = PerkEffect.FlatDamageBonus(1))
    val hub = HubState(PlayerFixtures.startingPlayer(ClassId.Warrior),
                       upgradeDefs = Map("hp_boost_1" -> upgrade),
                       perkOptions = List(perk)
    )
    val update = hub.toStateUpdate()
    assertEquals(update.phase, GamePhase.Hub)
    assertEquals(update.hub.map(_.upgrades.map(_.id)), Some(List("hp_boost_1")))
    assertEquals(update.hub.map(_.perks.map(_.id)), Some(List("heavy_hand")))

  test("HubState.toStateUpdate resolves each upgrade's unlocked flag from meta"):
    val locked   = UpgradeDef("a", "A", "d", cost = 1, displayOrder = 0, icon = "*", category = UpgradeCategory.Stat, effect = UpgradeEffect.MaxHpBoost(1))
    val unlocked = UpgradeDef("b", "B", "d", cost = 1, displayOrder = 1, icon = "*", category = UpgradeCategory.Stat, effect = UpgradeEffect.MaxHpBoost(1))
    val hub = HubState(PlayerFixtures.startingPlayer(ClassId.Warrior),
                       upgradeDefs = Map("a" -> locked, "b" -> unlocked),
                       meta = MetaProgression(currency = 0, unlockedUpgrades = Set("b"))
    )
    val views = hub.toStateUpdate().hub.get.upgrades.map(u => u.id -> u.unlocked).toMap
    assertEquals(views, Map("a" -> false, "b" -> true))

  // --- ExplorationState --------------------------------------------------------------

  test("ExplorationState.toStateUpdate has EXPLORATION phase and carries the room/dialogue"):
    val state  = ExplorationState(PlayerFixtures.startingPlayer(ClassId.Warrior), dungeon, 1, 1)
    val update = state.toStateUpdate(dialogue = Some(DialogueView("Wren", "Hello.")))
    assertEquals(update.phase, GamePhase.Exploration)
    assert(update.room.isDefined)
    assertEquals(update.dialogue, Some(DialogueView("Wren", "Hello.")))

  test("ExplorationState.toStateUpdate resolves a pending equip choice into a view, with the same affinity-doubled statLine as the equipped items"):
    val player  = PlayerFixtures.startingPlayer(ClassId.Warrior) // affinityTags = Set("heavy")
    val pending = PendingEquipChoice(weapon, Map(EquipSlot.WeaponSlot -> weapon))
    val state   = ExplorationState(player, dungeon, 1, 1, pendingEquipChoice = Some(pending))
    val view    = state.toStateUpdate().pendingEquipChoice.getOrElse(fail("expected a pendingEquipChoice view"))
    assertEquals(view.newItem.typeId, "practice_sword")
    assertEquals(view.options.map(_.slot), List(EquipSlot.WeaponSlot))
    // weapon.typeTag is None, so affinity doesn't double it - just confirms the resolved (not raw) statLine flows through.
    assertEquals(view.newItem.statLine, weapon.effectiveStatLine(player.affinityTags))

  test("ExplorationState.toStateUpdate has no pendingEquipChoice when none is set"):
    val state = ExplorationState(PlayerFixtures.startingPlayer(ClassId.Warrior), dungeon, 1, 1)
    assertEquals(state.toStateUpdate().pendingEquipChoice, None)

  // --- equipmentToView: key kind labels ---------------------------------------------

  test("equipmentToView resolves every KeyKind to its coarse display label"):
    val player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(
      keyCounts = Map(
        KeyKind.Generic          -> 1,
        KeyKind.Specific("door") -> 2,
        KeyKind.Typed("vault")   -> 3,
        KeyKind.Universal        -> 4
      )
    )
    val state = ExplorationState(player, dungeon, 1, 1)
    val labels = state.toStateUpdate().equipment.keys.map(k => k.keyKind -> k.count).toMap
    assertEquals(labels, Map("generic" -> 1, "specific" -> 2, "typed" -> 3, "universal" -> 4))

  test("equipmentToView omits keys with a zero count"):
    val player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(keyCounts = Map(KeyKind.Generic -> 0))
    val state  = ExplorationState(player, dungeon, 1, 1)
    assertEquals(state.toStateUpdate().equipment.keys, Nil)

  test("equipmentToView carries a potion stack's charge count, and leaves it unset for non-stacking items"):
    val potion = Consumable("p1", "health_potion", "Health Potion", Rarity.Common, ConsumableEffect.HealFixed(30))
    val player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(
      equippedWeapon = Some(weapon),
      potionBelt = Vector(Some(PotionStack(potion, 2)), None)
    )
    val state = ExplorationState(player, dungeon, 1, 1)
    val view  = state.toStateUpdate().equipment
    assertEquals(view.weapon.flatMap(_.count), None)
    assertEquals(view.potionBelt.head.flatMap(_.count), Some(2))

  test("equipmentToView resolves setId/typeTag onto the weapon and armor slots"):
    val player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(equippedWeapon = Some(weapon), equippedArmor = Some(armor))
    val state  = ExplorationState(player, dungeon, 1, 1)
    val view   = state.toStateUpdate().equipment
    assertEquals(view.weapon.flatMap(_.setId), Some("light_soldier"))
    assertEquals(view.armor.flatMap(_.typeTag), Some("heavy"))

  // --- CombatState --------------------------------------------------------------

  test("CombatState.toStateUpdate resolves the current enemy's spriteId from enemyStats"):
    val stats = EnemyStats(typeId = "goblin", label = "Goblin", spriteId = "mob_goblin_idle", maxHp = 20, attack = 5, defense = 0, xpReward = 10, actions = List(EnemyActionWeight("ATTACK", 100)))
    val enemy = EnemyInstance.fromStats("e1", stats, Difficulty.Normal, isElite = false)
    val combat = Combat(enemy = enemy)
    val state = CombatState(PlayerFixtures.startingPlayer(ClassId.Warrior), dungeon, 1, 1, combat, "e1", enemyStats = Map("goblin" -> stats))
    assertEquals(state.toStateUpdate().combat.flatMap(_.spriteId), Some("mob_goblin_idle"))

  test("CombatState.toStateUpdate has no spriteId when the enemy's typeId is missing from enemyStats"):
    val stats = EnemyStats(typeId = "goblin", label = "Goblin", spriteId = "mob_goblin_idle", maxHp = 20, attack = 5, defense = 0, xpReward = 10, actions = List(EnemyActionWeight("ATTACK", 100)))
    val enemy = EnemyInstance.fromStats("e1", stats, Difficulty.Normal, isElite = false)
    val combat = Combat(enemy = enemy)
    val state = CombatState(PlayerFixtures.startingPlayer(ClassId.Warrior), dungeon, 1, 1, combat, "e1", enemyStats = Map.empty)
    assertEquals(state.toStateUpdate().combat.flatMap(_.spriteId), None)

  // --- GameOverState --------------------------------------------------------------

  test("GameOverState.toStateUpdate carries the player's final equipment loadout"):
    val player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(equippedWeapon = Some(weapon))
    val state  = GameOverState(player, victory = true)
    assertEquals(state.toStateUpdate().equipment.weapon.map(_.typeId), Some("practice_sword"))
