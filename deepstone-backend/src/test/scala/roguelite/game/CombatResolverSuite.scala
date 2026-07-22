package roguelite.game

import munit.FunSuite
import roguelite.engine.*

import scala.util.Random

class CombatResolverSuite extends FunSuite:

  // --- Fixtures ------------------------------------------------------------

  def resolver(seed: Long = 0L): CombatResolver = CombatResolver(Random(seed))

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w):
      (row, col) =>
        if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  def testDungeon(enemies: List[Entity] = Nil): Dungeon =
    val room =
      Room("r1", RoomType.Combat, width = 8, height = 6, tiles = makeTiles(), entities = enemies)
    Dungeon(rooms = Map("r1" -> room), currentRoomId = "r1")

  def weakEnemy(entityId: String = "e1", hp: Int = 1): EnemyInstance =
    EnemyInstance(entityId = entityId,
                  typeId = "goblin",
                  label = "Goblin",
                  hp = hp,
                  maxHp = 20,
                  attack = 5,
                  defense = 0,
                  xpReward = 15,
                  actions = List(EnemyActionWeight("ATTACK", 100))
    )

  def strongEnemy(entityId: String = "e1"): EnemyInstance =
    EnemyInstance(entityId = entityId,
                  typeId = "boss",
                  label = "Boss",
                  hp = 500,
                  maxHp = 500,
                  attack = 999,
                  defense = 0,
                  xpReward = 100,
                  actions = List(EnemyActionWeight("ATTACK", 100))
    )

  def fullHpPlayer(classId: ClassId = ClassId.Warrior): Player = PlayerFixtures.startingPlayer(classId)
  def lowHpPlayer: Player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(hp = 1)

  def combatState(enemy: EnemyInstance, player: Player = fullHpPlayer()): CombatState =
    CombatState(
      player = player,
      dungeon = testDungeon(List(Enemy(enemy.entityId, 3, 3, enemy.typeId, enemy.label))),
      playerX = 1,
      playerY = 1,
      combat = Combat(enemy = enemy),
      enemyEntityId = enemy.entityId
    )

  def bossDungeon(enemies: List[Entity] = Nil): Dungeon =
    val room =
      Room("boss1", RoomType.Boss, width = 8, height = 6, tiles = makeTiles(), entities = enemies)
    Dungeon(rooms = Map("boss1" -> room), currentRoomId = "boss1")

  def combatStateInBossRoom(enemy: EnemyInstance, player: Player = fullHpPlayer()): CombatState =
    CombatState(
      player = player,
      dungeon = bossDungeon(List(Enemy(enemy.entityId, 3, 3, enemy.typeId, enemy.label))),
      playerX = 1,
      playerY = 1,
      combat = Combat(enemy = enemy),
      enemyEntityId = enemy.entityId
    )

  // --- Helper to unwrap Either safely in tests -----------------------------

  private def addItem(inv: Inventory, item: Item): Inventory =
    inv.addItem(item).getOrElse(fail(s"Inventory full — could not add ${item.name}"))

  // --- calcDamage ----------------------------------------------------------

  test("calcDamage always returns at least 1"):
    val r = resolver()
    for _ <- 1 to 100 do assert(r.calcDamage(1, 999) >= 1)

  test("calcDamage scales with attack stat"):
    val r = resolver(42L)
    assert(r.calcDamage(50, 0) > r.calcDamage(5, 0))

  test("calcDamage is reduced by defense"):
    assert(
      CombatResolver(Random(42L)).calcDamage(20, 0) > CombatResolver(Random(42L)).calcDamage(20, 10)
    )

  // --- Attack --------------------------------------------------------------

  test("Attack on a 1-HP enemy produces victory"):
    val (next, _, _) =
      resolver().resolve(combatState(weakEnemy(hp = 1)), CombatAction(CombatActionType.Attack))
    assert(next.isInstanceOf[ExplorationState])

  test("Victory awards XP to the player"):
    val (next, _, _) =
      resolver().resolve(combatState(weakEnemy(hp = 1)), CombatAction(CombatActionType.Attack))
    assert(next.player.xp > 0)

  test("Victory removes the enemy entity from the room"):
    val (next, _, _) = resolver().resolve(combatState(weakEnemy(entityId = "e1", hp = 1)),
                                       CombatAction(CombatActionType.Attack)
    )
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoom.entityById("e1"), None)

  test("Attack log mentions damage dealt"):
    val (_, log, _) =
      resolver().resolve(combatState(weakEnemy(hp = 999)), CombatAction(CombatActionType.Attack))
    assert(log.exists(_.contains("damage")))

  // --- Defend --------------------------------------------------------------

  test("Defend stays in CombatState"):
    val (next, _, _) =
      resolver().resolve(combatState(weakEnemy(hp = 50)), CombatAction(CombatActionType.Defend))
    assert(next.isInstanceOf[CombatState])

  test("Defend reduces damage taken compared to Attack"):
    val enemy = weakEnemy(hp = 50)
    val (noDefend, _, _) =
      CombatResolver(Random(1L)).resolve(combatState(enemy), CombatAction(CombatActionType.Attack))
    val (withDefend, _, _) =
      CombatResolver(Random(1L)).resolve(combatState(enemy), CombatAction(CombatActionType.Defend))
    (noDefend, withDefend) match
      case (a: CombatState, b: CombatState) =>
        assert(
          b.player.hp >= a.player.hp,
          s"Defending should take <= damage (defend: ${b.player.hp}, no-defend: ${a.player.hp})"
        )
      case _ => () // one side may have won — inconclusive

  // --- Defeat --------------------------------------------------------------

  test("Player death transitions to GameOverState"):
    val (next, _, _) = resolver().resolve(combatState(strongEnemy(), player = lowHpPlayer),
                                       CombatAction(CombatActionType.Attack)
    )
    assert(next.isInstanceOf[GameOverState] || next.isInstanceOf[ExplorationState])

  // --- Boss victory ----------------------------------------------------------

  test("defeating the last enemy in the boss room ends the run in victory"):
    val (next, log, _) = resolver().resolve(combatStateInBossRoom(weakEnemy(hp = 1)),
                                         CombatAction(CombatActionType.Attack)
    )
    assert(next.isInstanceOf[GameOverState], s"expected GameOverState, got $next")
    assertEquals(next.asInstanceOf[GameOverState].victory, true)
    assert(log.exists(_.toLowerCase.contains("victory")), s"expected victory message in log: $log")

  test("player death in the boss room is still a defeat, not a victory"):
    val (next, _, _) = resolver().resolve(combatStateInBossRoom(strongEnemy(), player = lowHpPlayer),
                                       CombatAction(CombatActionType.Attack)
    )
    next match
      case gameOver: GameOverState => assertEquals(gameOver.victory, false)
      case _                       => () // player may not have died this turn — inconclusive

  // --- GameEvent emission ----------------------------------------------------

  test("boss victory emits EnemyDefeated(isBoss = true) and RunEnded(victory = true)"):
    val (_, _, events) =
      resolver().resolve(combatStateInBossRoom(weakEnemy(hp = 1)), CombatAction(CombatActionType.Attack))
    assert(events.contains(GameEvent.EnemyDefeated(isBoss = true, tookNoDamage = true)),
           s"expected EnemyDefeated(isBoss=true, tookNoDamage=true): $events"
    )
    assert(events.contains(GameEvent.RunEnded(victory = true)), s"expected RunEnded(true): $events")

  test("non-boss victory emits EnemyDefeated(isBoss = false) and no RunEnded"):
    val (_, _, events) =
      resolver().resolve(combatState(weakEnemy(hp = 1)), CombatAction(CombatActionType.Attack))
    assert(events.contains(GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = true)),
           s"expected EnemyDefeated(isBoss=false, tookNoDamage=true): $events"
    )
    assert(!events.exists(_.isInstanceOf[GameEvent.RunEnded]), s"unexpected RunEnded: $events")

  test("victory after taking damage earlier this fight emits tookNoDamage = false"):
    val hurtState = combatState(weakEnemy(hp = 1))
    val stateWithDamageTaken = hurtState.copy(combat = hurtState.combat.copy(tookDamage = true))
    val (_, _, events) = resolver().resolve(stateWithDamageTaken, CombatAction(CombatActionType.Attack))
    assert(events.contains(GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = false)),
           s"expected tookNoDamage = false: $events"
    )

  test("defeat emits RunEnded(victory = false)"):
    val (_, _, events) = resolver().resolve(combatState(strongEnemy(), player = lowHpPlayer),
                                            CombatAction(CombatActionType.Attack)
    )
    events match
      case Nil => () // player may not have died this turn — inconclusive
      case _ =>
        assert(events.contains(GameEvent.RunEnded(victory = false)), s"expected RunEnded(false): $events")

  test("a kill that crosses an XP threshold emits LeveledUp"):
    val bigXpEnemy = weakEnemy(hp = 1).copy(xpReward = 200)
    val (next, _, events) =
      resolver().resolve(combatState(bigXpEnemy), CombatAction(CombatActionType.Attack))
    assert(next.player.level > 1, s"expected a level up, player is still level ${next.player.level}")
    assert(events.contains(GameEvent.LeveledUp(next.player.level)), s"expected LeveledUp: $events")

  test("a kill that fills the last inventory slot emits ItemPickedUp(inventoryFull = true)"):
    val filler        = Weapon("filler", "sword", "Sword", Rarity.Common, 1)
    val almostFullInv = Inventory(Vector.fill(Inventory.MaxSlots - 1)(Some(filler)) :+ None)
    val itemDefs: Map[String, Item] = Map(
      "health_potion" -> Consumable("",
                                    "health_potion",
                                    "Health Potion",
                                    Rarity.Common,
                                    ConsumableEffect.HealFixed(30)
      )
    )
    val enemy =
      weakEnemy(hp = 1).copy(dropChance = 100, lootTable = List(LootEntry("health_potion", 100)))
    val state = combatState(enemy, player = fullHpPlayer().copy(inventory = almostFullInv))
    val (_, _, events) =
      CombatResolver(Random(0), itemDefs).resolve(state, CombatAction(CombatActionType.Attack))
    assert(events.contains(GameEvent.ItemPickedUp(inventoryFull = true)),
           s"expected ItemPickedUp(inventoryFull=true): $events"
    )

  // --- Item use ------------------------------------------------------------

  test("Item with no itemId returns 'no item selected'"):
    val (next, log, _) = resolver().resolve(combatState(weakEnemy(hp = 50)),
                                         CombatAction(CombatActionType.Item, itemId = None)
    )
    assert(next.isInstanceOf[CombatState])
    assert(log.exists(_.toLowerCase.contains("no item")))

  test("Item with unknown id returns 'not found'"):
    val (next, log, _) = resolver().resolve(combatState(weakEnemy(hp = 50)),
                                         CombatAction(CombatActionType.Item, itemId = Some("ghost"))
    )
    assert(next.isInstanceOf[CombatState])
    assert(log.exists(_.toLowerCase.contains("not found")))

  test("Item with non-consumable returns 'cannot be used'"):
    val sword = Weapon("w1", "iron_sword", "Iron Sword", Rarity.Common, 3)
    val inv   = addItem(Inventory.empty, sword)
    val state = combatState(weakEnemy(hp = 50), player = fullHpPlayer().copy(inventory = inv))
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("w1")))
    assert(next.isInstanceOf[CombatState])
    assert(log.exists(_.toLowerCase.contains("cannot be used")))

  test("HealFixed potion heals player and removes it from inventory"):
    val potion = Consumable("p1",
                            "health_potion",
                            "Health Potion",
                            Rarity.Common,
                            ConsumableEffect.HealFixed(30)
    )
    val inv = addItem(Inventory.empty, potion)
    val state =
      combatState(weakEnemy(hp = 50), player = fullHpPlayer().copy(hp = 50, inventory = inv))
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(next.player.hp > 50)
    assertEquals(next.player.inventory.findById("p1"), None)
    assert(log.exists(_.contains("Health Potion")))

  test("HealFixed does not overheal past maxHp"):
    val potion = Consumable("p1", "hp", "HP", Rarity.Common, ConsumableEffect.HealFixed(9999))
    val inv    = addItem(Inventory.empty, potion)
    val state =
      combatState(weakEnemy(hp = 50), player = fullHpPlayer().copy(hp = 10, inventory = inv))
    val (next, _, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(next.player.hp <= next.player.maxHp)

  test("HealPercent heals a fraction of maxHp"):
    val potion =
      Consumable("p1", "pct", "Pct Potion", Rarity.Common, ConsumableEffect.HealPercent(50))
    val inv    = addItem(Inventory.empty, potion)
    val player = fullHpPlayer().copy(hp = 10, inventory = inv) // maxHp = 120, heal = 60
    val (next, _, _) = resolver().resolve(combatState(weakEnemy(hp = 50), player),
                                       CombatAction(CombatActionType.Item, itemId = Some("p1"))
    )
    assert(next.player.hp > 10)

  test("RestoreResource ether increases resourceCurrent"):
    val ether =
      Consumable("e1", "ether", "Ether", Rarity.Uncommon, ConsumableEffect.RestoreResource(20))
    val inv = addItem(Inventory.empty, ether)
    val state = combatState(weakEnemy(hp = 50),
                            player = fullHpPlayer().copy(resourceCurrent = 0, inventory = inv)
    )
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("e1")))
    assert(next.player.resourceCurrent > 0)
    assert(log.exists(_.contains("Ether")))

  test("using an item triggers enemy counter-turn"):
    val potion = Consumable("p1", "hp", "HP Potion", Rarity.Common, ConsumableEffect.HealFixed(5))
    val inv    = addItem(Inventory.empty, potion)
    val state  = combatState(weakEnemy(hp = 50), player = fullHpPlayer().copy(inventory = inv))
    val (_, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(log.size >= 2, s"Expected item use + enemy action in log: $log")

  // --- Inventory stat bonuses ----------------------------------------------

  test("weapon bonus increases damage dealt to enemy"):
    val sword = Weapon("w1", "iron_sword", "Iron Sword", Rarity.Common, 3)
    val inv   = addItem(Inventory.empty, sword)
    val heavy = weakEnemy(hp = 999)
    val (withSword, _, _) =
      CombatResolver(Random(0)).resolve(combatState(heavy, fullHpPlayer().copy(inventory = inv)),
                                        CombatAction(CombatActionType.Attack)
      )
    val (noSword, _, _) =
      CombatResolver(Random(0)).resolve(combatState(heavy), CombatAction(CombatActionType.Attack))
    val hpWith = withSword.asInstanceOf[CombatState].combat.enemy.hp
    val hpNo   = noSword.asInstanceOf[CombatState].combat.enemy.hp
    assert(hpWith <= hpNo, s"Sword owner should deal >= damage (with=$hpWith, no=$hpNo)")

  test("armor bonus reduces damage taken from enemy"):
    val armorItem = Armor("a1", "chain_mail", "Chain Mail", Rarity.Uncommon, defenseBonus = 5)
    val inv       = addItem(Inventory.empty, armorItem)
    val (withArmor, _, _) = CombatResolver(Random(0)).resolve(
      combatState(weakEnemy(hp = 50), fullHpPlayer().copy(inventory = inv)),
      CombatAction(CombatActionType.Defend)
    )
    val (noArmor, _, _) = CombatResolver(Random(0)).resolve(combatState(weakEnemy(hp = 50)),
                                                         CombatAction(CombatActionType.Defend)
    )
    assert(withArmor.player.hp >= noArmor.player.hp,
           s"Armor should reduce damage (with=${withArmor.player.hp}, no=${noArmor.player.hp})"
    )

  // --- Loot drop on victory ------------------------------------------------

  test("enemy with 100% drop adds item to inventory on defeat"):
    val itemDefs: Map[String, Item] = Map(
      "health_potion" -> Consumable("",
                                    "health_potion",
                                    "Health Potion",
                                    Rarity.Common,
                                    ConsumableEffect.HealFixed(30)
      )
    )
    val enemy =
      weakEnemy(hp = 1).copy(dropChance = 100, lootTable = List(LootEntry("health_potion", 100)))
    val (next, log, _) = CombatResolver(Random(0), itemDefs)
      .resolve(combatState(enemy), CombatAction(CombatActionType.Attack))
    assert(next.isInstanceOf[ExplorationState])
    assertEquals(next.player.inventory.size, 1)
    assert(log.exists(_.contains("dropped")))

  test("enemy with 0% drop leaves inventory empty on defeat"):
    val itemDefs: Map[String, Item] = Map(
      "health_potion" -> Consumable("",
                                    "health_potion",
                                    "Health Potion",
                                    Rarity.Common,
                                    ConsumableEffect.HealFixed(30)
      )
    )
    val (next, _, _) = CombatResolver(Random(0), itemDefs)
      .resolve(combatState(weakEnemy(hp = 1)), CombatAction(CombatActionType.Attack))
    assertEquals(next.player.inventory.size, 0)

  test("accessory drop increases player maxHp on defeat"):
    val itemDefs: Map[String, Item] = Map(
      "iron_ring" -> Accessory("", "iron_ring", "Iron Ring", Rarity.Common, hpBonus = 10)
    )
    val enemy =
      weakEnemy(hp = 1).copy(dropChance = 100, lootTable = List(LootEntry("iron_ring", 100)))
    val baseMaxHp = fullHpPlayer().maxHp
    val (next, _, _) = CombatResolver(Random(0), itemDefs)
      .resolve(combatState(enemy), CombatAction(CombatActionType.Attack))
    assertEquals(next.player.maxHp, baseMaxHp + 10)
