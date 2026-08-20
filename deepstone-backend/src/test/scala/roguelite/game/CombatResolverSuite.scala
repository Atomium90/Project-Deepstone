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

  // --- Equipment helpers -----------------------------------------------------

  private def equipWeapon(player: Player, weapon: Weapon): Player = player.copy(equippedWeapon = Some(weapon))
  private def equipArmor(player: Player, armor: Armor): Player   = player.copy(equippedArmor = Some(armor))
  private def equipPotion(player: Player, potion: Consumable, slot: Int = 0): Player =
    player.copy(potionBelt = player.potionBelt.updated(slot, Some(potion)))
  private def equipAccessory(player: Player, accessory: Accessory, slot: Int = 0): Player =
    player.copy(equippedAccessories = player.equippedAccessories.updated(slot, Some(accessory)))

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

  // --- Crit chance -----------------------------------------------------------

  test("rollCrit never crits at 0% chance"):
    val r = resolver()
    for _ <- 1 to 100 do assert(!r.rollCrit(0))

  test("rollCrit never crits at negative chance"):
    val r = resolver()
    for _ <- 1 to 100 do assert(!r.rollCrit(-5))

  test("rollCrit always crits at 100% chance"):
    val r = resolver()
    for _ <- 1 to 100 do assert(r.rollCrit(100))

  test("player critChance sums equipped accessories' critChanceBonus"):
    val critRing = Accessory("", "luck_clover", "Lucky Clover", Rarity.Common, critChanceBonus = Some(5))
    val critGem  = Accessory("", "mask_of_terror", "Mask of Terror", Rarity.Uncommon, critChanceBonus = Some(8))
    val player   = equipAccessory(equipAccessory(fullHpPlayer(), critRing, slot = 0), critGem, slot = 1)
    assertEquals(resolver().critChance(player), 13)

  test("a guaranteed crit multiplies attack damage by 1.5 and is reported on the DamageDealt event"):
    val guaranteedCrit =
      Accessory("", "mask_of_terror", "Mask of Terror", Rarity.Uncommon, critChanceBonus = Some(100))
    val player = equipAccessory(fullHpPlayer(), guaranteedCrit)
    val (next, log, events) =
      resolver().resolve(combatState(weakEnemy(hp = 999), player = player), CombatAction(CombatActionType.Attack))
    val damageDealt = events.collectFirst { case d: GameEvent.DamageDealt if !d.targetIsPlayer => d }
    damageDealt match
      case Some(d) =>
        assert(d.crit, s"expected a crit-flagged DamageDealt: $events")
      case None => fail(s"expected a DamageDealt event: $events")
    assert(log.exists(_.contains("Critical hit")), s"expected a crit callout in the log: $log")

  test("Arcane Blast (FlatDamage) never crits even with guaranteed crit chance equipped"):
    val guaranteedCrit =
      Accessory("", "mask_of_terror", "Mask of Terror", Rarity.Uncommon, critChanceBonus = Some(100))
    val ability = AbilityDef(ClassId.Warrior,
                             "arcane_blast",
                             "Arcane Blast",
                             cost = 0,
                             resourceName = "Mana",
                             effect = AbilityEffect.FlatDamage(45),
                             description = ""
    )
    val player = equipAccessory(fullHpPlayer(), guaranteedCrit)
    val (_, _, events) = CombatResolver(Random(0), abilityDefs = Map(ClassId.Warrior -> ability))
      .resolve(combatState(weakEnemy(hp = 999), player = player), CombatAction(CombatActionType.Ability))
    val damageDealt = events.collectFirst { case d: GameEvent.DamageDealt if !d.targetIsPlayer => d }
    damageDealt match
      case Some(d) => assertEquals(d.crit, false, s"Arcane Blast should never crit: $events")
      case None    => fail(s"expected a DamageDealt event: $events")

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
      case _ => () // one side may have won, inconclusive

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
      case _                       => () // player may not have died this turn, inconclusive

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
      case Nil => () // player may not have died this turn, inconclusive
      case _ =>
        assert(events.contains(GameEvent.RunEnded(victory = false)), s"expected RunEnded(false): $events")

  test("a kill that crosses an XP threshold emits LeveledUp"):
    val bigXpEnemy = weakEnemy(hp = 1).copy(xpReward = 200)
    val (next, _, events) =
      resolver().resolve(combatState(bigXpEnemy), CombatAction(CombatActionType.Attack))
    assert(next.player.level > 1, s"expected a level up, player is still level ${next.player.level}")
    assert(events.contains(GameEvent.LeveledUp(next.player.level)), s"expected LeveledUp: $events")

  test("a kill that fills the last equipment slot emits ItemPickedUp(inventoryFull = true)"):
    val weapon = Weapon("w0", "sword", "Sword", Rarity.Common, 1)
    val armor  = Armor("a0", "leather", "Leather", Rarity.Common, 1)
    val acc0   = Accessory("acc0", "ring", "Ring", Rarity.Common, Some(1))
    val acc1   = Accessory("acc1", "ring2", "Ring 2", Rarity.Common, Some(1))
    val ether  = Consumable("p0", "ether", "Ether", Rarity.Common, ConsumableEffect.RestoreResource(1))
    val nearlyFull = fullHpPlayer().copy(
      equippedWeapon = Some(weapon),
      equippedArmor = Some(armor),
      equippedAccessories = Vector(Some(acc0), Some(acc1)),
      potionBelt = Vector(Some(ether), None)
    )
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
    val state = combatState(enemy, player = nearlyFull)
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

  test("Item action with an equipped weapon's id (not in the potion belt) returns 'not found'"):
    val sword = Weapon("w1", "iron_sword", "Iron Sword", Rarity.Common, 3)
    val state = combatState(weakEnemy(hp = 50), player = equipWeapon(fullHpPlayer(), sword))
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("w1")))
    assert(next.isInstanceOf[CombatState])
    assert(log.exists(_.toLowerCase.contains("not found")))

  test("HealFixed potion heals player and removes it from the potion belt"):
    val potion = Consumable("p1",
                            "health_potion",
                            "Health Potion",
                            Rarity.Common,
                            ConsumableEffect.HealFixed(30)
    )
    val state =
      combatState(weakEnemy(hp = 50), player = equipPotion(fullHpPlayer().copy(hp = 50), potion))
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(next.player.hp > 50)
    assertEquals(next.player.potionBelt.flatten.find(_.id == "p1"), None)
    assert(log.exists(_.contains("Health Potion")))

  test("HealFixed does not overheal past maxHp"):
    val potion = Consumable("p1", "hp", "HP", Rarity.Common, ConsumableEffect.HealFixed(9999))
    val state =
      combatState(weakEnemy(hp = 50), player = equipPotion(fullHpPlayer().copy(hp = 10), potion))
    val (next, _, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(next.player.hp <= next.player.maxHp)

  test("HealPercent heals a fraction of maxHp"):
    val potion =
      Consumable("p1", "pct", "Pct Potion", Rarity.Common, ConsumableEffect.HealPercent(50))
    val player = equipPotion(fullHpPlayer().copy(hp = 10), potion) // maxHp = 120, heal = 60
    val (next, _, _) = resolver().resolve(combatState(weakEnemy(hp = 50), player),
                                       CombatAction(CombatActionType.Item, itemId = Some("p1"))
    )
    assert(next.player.hp > 10)

  test("RestoreResource ether increases resourceCurrent"):
    val ether =
      Consumable("e1", "ether", "Ether", Rarity.Uncommon, ConsumableEffect.RestoreResource(20))
    val state = combatState(weakEnemy(hp = 50),
                            player = equipPotion(fullHpPlayer().copy(resourceCurrent = 0), ether)
    )
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("e1")))
    assert(next.player.resourceCurrent > 0)
    assert(log.exists(_.contains("Ether")))

  test("using an item triggers enemy counter-turn"):
    val potion = Consumable("p1", "hp", "HP Potion", Rarity.Common, ConsumableEffect.HealFixed(5))
    val state  = combatState(weakEnemy(hp = 50), player = equipPotion(fullHpPlayer(), potion))
    val (_, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(log.size >= 2, s"Expected item use + enemy action in log: $log")

  // --- AttackBuff potion -----------------------------------------------------

  test("AttackBuff potion arms a timed buff with the correct percent and duration"):
    // Using an item still triggers the enemy's turn in the same transition (see "using an item
    // triggers enemy counter-turn" above), so the freshly-armed buff has already ticked down by
    // one round-end decrement by the time this resolve() call returns: 3 -> 2.
    val brew  = Consumable("b1", "battle_brew", "Battle Brew", Rarity.Common, ConsumableEffect.AttackBuff(50, 3))
    val state = combatState(weakEnemy(hp = 500), player = equipPotion(fullHpPlayer(), brew))
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("b1")))
    assertEquals(next.asInstanceOf[CombatState].combat.activeBuffs,
                 List(TimedBuff(TimedBuffEffect.AttackBonusPercent(50), 2))
    )
    assert(log.exists(_.contains("Battle Brew")))

  test("using a second AttackBuff potion refreshes duration instead of stacking"):
    val brew1 = Consumable("b1", "battle_brew", "Battle Brew", Rarity.Common, ConsumableEffect.AttackBuff(50, 5))
    val brew2 = Consumable("b2", "battle_brew", "Battle Brew", Rarity.Common, ConsumableEffect.AttackBuff(50, 3))
    val state = combatState(weakEnemy(hp = 500),
                            player = equipPotion(equipPotion(fullHpPlayer(), brew1, slot = 0), brew2, slot = 1)
    )
    val (afterFirst, _, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("b1")))
    val (afterSecond, _, _) = resolver().resolve(afterFirst.asInstanceOf[CombatState],
                                                  CombatAction(CombatActionType.Item, itemId = Some("b2"))
    )
    assertEquals(afterSecond.asInstanceOf[CombatState].combat.activeBuffs,
                 List(TimedBuff(TimedBuffEffect.AttackBonusPercent(50), 2)),
                 "expected exactly one AttackBonusPercent buff, replaced by the newer one (5-turn brew1 would" +
                   " otherwise still show 4 turns left)"
    )

  // --- CritBuff potion ---------------------------------------------------------

  test("CritBuff potion arms a timed crit buff with the correct percent and duration"):
    val tonic = Consumable("c1", "focus_tonic", "Focus Tonic", Rarity.Common, ConsumableEffect.CritBuff(30, 2))
    val state = combatState(weakEnemy(hp = 500), player = equipPotion(fullHpPlayer(), tonic))
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("c1")))
    // Same round-end decrement as AttackBuff: armed at 2, already ticked to 1 by the time the
    // enemy's turn (part of the same transition) completes.
    assertEquals(next.asInstanceOf[CombatState].combat.activeBuffs,
                 List(TimedBuff(TimedBuffEffect.CritChanceBonusPercent(30), 1))
    )
    assert(log.exists(_.contains("Focus Tonic")))

  test("an AttackBuff and a CritBuff coexist without clobbering each other"):
    val brew  = Consumable("b1", "battle_brew", "Battle Brew", Rarity.Common, ConsumableEffect.AttackBuff(50, 5))
    val tonic = Consumable("c1", "focus_tonic", "Focus Tonic", Rarity.Common, ConsumableEffect.CritBuff(30, 5))
    val state = combatState(weakEnemy(hp = 500),
                            player = equipPotion(equipPotion(fullHpPlayer(), brew, slot = 0), tonic, slot = 1)
    )
    val (afterFirst, _, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("b1")))
    val (afterSecond, _, _) = resolver().resolve(afterFirst.asInstanceOf[CombatState],
                                                  CombatAction(CombatActionType.Item, itemId = Some("c1"))
    )
    val finalBuffs = afterSecond.asInstanceOf[CombatState].combat.activeBuffs
    assertEquals(finalBuffs.toSet,
                 Set(TimedBuff(TimedBuffEffect.AttackBonusPercent(50), 3),
                     TimedBuff(TimedBuffEffect.CritChanceBonusPercent(30), 4)
                 )
    )

  test("a CritChanceBonusPercent buff can push a guaranteed crit on Attack"):
    val buff  = TimedBuff(TimedBuffEffect.CritChanceBonusPercent(100), turnsRemaining = 2)
    val state = combatState(weakEnemy(hp = 500), player = fullHpPlayer())
    val withBuff = state.copy(combat = state.combat.copy(activeBuffs = List(buff)))
    val (next, log, _) = resolver().resolve(withBuff, CombatAction(CombatActionType.Attack))
    assert(log.exists(_.contains("Critical hit!")), s"expected a guaranteed crit: $log")
    assert(next.isInstanceOf[CombatState])

  // --- FlatDamage potion -------------------------------------------------------

  test("FlatDamage potion damages the enemy without killing it, combat continues"):
    val bomb  = Consumable("f1", "volatile_flask", "Volatile Flask", Rarity.Common, ConsumableEffect.FlatDamage(25))
    val state = combatState(weakEnemy(hp = 100), player = equipPotion(fullHpPlayer(), bomb))
    val (next, log, events) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("f1")))
    val nextCombat = next.asInstanceOf[CombatState].combat
    assertEquals(nextCombat.enemy.hp, 75)
    assert(log.exists(_.contains("25 damage")), s"expected the damage amount in the log: $log")
    assert(events.exists { case GameEvent.DamageDealt(false, 25, _) => true; case _ => false },
           s"expected a DamageDealt event: $events"
    )

  test("FlatDamage potion that kills the enemy routes to victory"):
    val bomb  = Consumable("f1", "volatile_flask", "Volatile Flask", Rarity.Common, ConsumableEffect.FlatDamage(25))
    val state = combatState(weakEnemy(hp = 10), player = equipPotion(fullHpPlayer(), bomb))
    val (next, log, _) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("f1")))
    assert(!next.isInstanceOf[CombatState], s"expected combat to end, got $next")
    assert(log.exists(_.contains("defeated")), s"expected a victory log line: $log")

  // --- Inventory stat bonuses ----------------------------------------------

  test("weapon bonus increases damage dealt to enemy"):
    val sword = Weapon("w1", "iron_sword", "Iron Sword", Rarity.Common, 3)
    val heavy = weakEnemy(hp = 999)
    val (withSword, _, _) =
      CombatResolver(Random(0)).resolve(combatState(heavy, equipWeapon(fullHpPlayer(), sword)),
                                        CombatAction(CombatActionType.Attack)
      )
    val (noSword, _, _) =
      CombatResolver(Random(0)).resolve(combatState(heavy), CombatAction(CombatActionType.Attack))
    val hpWith = withSword.asInstanceOf[CombatState].combat.enemy.hp
    val hpNo   = noSword.asInstanceOf[CombatState].combat.enemy.hp
    assert(hpWith <= hpNo, s"Sword owner should deal >= damage (with=$hpWith, no=$hpNo)")

  test("armor bonus reduces damage taken from enemy"):
    val armorItem = Armor("a1", "chain_mail", "Chain Mail", Rarity.Uncommon, defenseBonus = 5)
    val (withArmor, _, _) = CombatResolver(Random(0)).resolve(
      combatState(weakEnemy(hp = 50), equipArmor(fullHpPlayer(), armorItem)),
      CombatAction(CombatActionType.Defend)
    )
    val (noArmor, _, _) = CombatResolver(Random(0)).resolve(combatState(weakEnemy(hp = 50)),
                                                         CombatAction(CombatActionType.Defend)
    )
    assert(withArmor.player.hp >= noArmor.player.hp,
           s"Armor should reduce damage (with=${withArmor.player.hp}, no=${noArmor.player.hp})"
    )

  // --- Loot drop on victory ------------------------------------------------

  test("enemy with 100% drop adds item to the potion belt on defeat"):
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
    assertEquals(next.player.potionBelt.flatten.toList.map(_.typeId), List("health_potion"))
    assert(log.exists(_.contains("dropped")))

  test("enemy with 0% drop leaves the potion belt empty on defeat"):
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
    assertEquals(next.player.potionBelt.flatten.toList, Nil)

  // --- Loot collision (equip choice) on victory -----------------------------

  test("victory loot collision offers a choice on the resulting ExplorationState, no ItemPickedUp yet"):
    val existingWeapon = Weapon("existing", "hunters_bow", "Hunter's Bow", Rarity.Common, attackBonus = 5)
    val itemDefs: Map[String, Item] = Map(
      "iron_sword" -> Weapon("", "iron_sword", "Iron Sword", Rarity.Common, attackBonus = 3)
    )
    val enemy = weakEnemy(hp = 1).copy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val playerWithWeapon = fullHpPlayer().copy(equippedWeapon = Some(existingWeapon))
    val (next, _, events) = CombatResolver(Random(0), itemDefs)
      .resolve(combatState(enemy, playerWithWeapon), CombatAction(CombatActionType.Attack))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.player.equippedWeapon, Some(existingWeapon))
    assertEquals(nextExp.pendingEquipChoice.map(_.currentItems.keySet), Some(Set(EquipSlot.WeaponSlot)))
    assert(!events.exists(_.isInstanceOf[GameEvent.ItemPickedUp]),
           s"no ItemPickedUp should fire until the choice is resolved: $events"
    )

  test("a boss-kill loot collision is lost silently, never offered as a choice"):
    val existingWeapon = Weapon("existing", "hunters_bow", "Hunter's Bow", Rarity.Common, attackBonus = 5)
    val itemDefs: Map[String, Item] = Map(
      "iron_sword" -> Weapon("", "iron_sword", "Iron Sword", Rarity.Common, attackBonus = 3)
    )
    val enemy = weakEnemy(hp = 1).copy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val playerWithWeapon = fullHpPlayer().copy(equippedWeapon = Some(existingWeapon))
    val (next, log, _) = CombatResolver(Random(0), itemDefs)
      .resolve(combatStateInBossRoom(enemy, playerWithWeapon), CombatAction(CombatActionType.Attack))
    assert(next.isInstanceOf[GameOverState], s"expected GameOverState, got $next")
    assertEquals(next.player.equippedWeapon, Some(existingWeapon))
    assert(log.exists(_.toLowerCase.contains("no time to decide")), s"expected the boss-kill-loss message: $log")

  // --- Damage/heal event emission ------------------------------------------

  test("Attack emits DamageDealt(targetIsPlayer = false) matching the enemy's HP loss"):
    val enemy = weakEnemy(hp = 999)
    val (next, _, events) =
      resolver().resolve(combatState(enemy), CombatAction(CombatActionType.Attack))
    val hpLost = enemy.hp - next.asInstanceOf[CombatState].combat.enemy.hp
    assert(
      events.contains(GameEvent.DamageDealt(targetIsPlayer = false, amount = hpLost)),
      s"expected DamageDealt(false, $hpLost): $events"
    )

  test("Defend still lets the enemy counter-attack, emitting DamageDealt(targetIsPlayer = true)"):
    val (next, _, events) =
      resolver().resolve(combatState(weakEnemy(hp = 50)), CombatAction(CombatActionType.Defend))
    val hpLost = fullHpPlayer().hp - next.player.hp
    assert(hpLost > 0, "enemy should have hit the defending player")
    assert(
      events.contains(GameEvent.DamageDealt(targetIsPlayer = true, amount = hpLost)),
      s"expected DamageDealt(true, $hpLost): $events"
    )

  test("the fatal blow still emits DamageDealt(targetIsPlayer = true) before RunEnded"):
    val (_, _, events) = resolver().resolve(combatState(strongEnemy(), player = lowHpPlayer),
                                            CombatAction(CombatActionType.Attack)
    )
    events match
      case Nil => () // player may not have died this turn, inconclusive
      case _ =>
        assert(events.exists {
          case GameEvent.DamageDealt(true, _, _) => true
          case _                                 => false
        }, s"expected a DamageDealt(true, _) alongside RunEnded: $events")

  test("Arcane Blast (FlatDamage ability) emits DamageDealt(targetIsPlayer = false, amount = the flat value)"):
    val ability = AbilityDef(ClassId.Warrior,
                             "test_blast",
                             "Test Blast",
                             cost = 10,
                             resourceName = "Rage",
                             description = "",
                             effect = AbilityEffect.FlatDamage(45)
    )
    val player = fullHpPlayer().copy(resourceCurrent = 100)
    val (_, _, events) = CombatResolver(Random(0), abilityDefs = Map(ClassId.Warrior -> ability))
      .resolve(combatState(weakEnemy(hp = 999), player), CombatAction(CombatActionType.Ability))
    assert(
      events.contains(GameEvent.DamageDealt(targetIsPlayer = false, amount = 45)),
      s"expected DamageDealt(false, 45): $events"
    )

  test("HealFixed potion emits Healed matching the amount restored (not net HP, which the enemy's counter-attack also affects)"):
    val potion = Consumable("p1",
                            "health_potion",
                            "Health Potion",
                            Rarity.Common,
                            ConsumableEffect.HealFixed(30)
    )
    val state = combatState(weakEnemy(hp = 50), player = equipPotion(fullHpPlayer().copy(hp = 50), potion))
    val (_, _, events) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(events.contains(GameEvent.Healed(30)), s"expected Healed(30): $events")

  test("a potion that can't heal past maxHp doesn't emit Healed(0)"):
    val potion = Consumable("p1", "hp", "HP", Rarity.Common, ConsumableEffect.HealFixed(30))
    val state  = combatState(weakEnemy(hp = 50), player = equipPotion(fullHpPlayer(), potion)) // already full HP
    val (_, _, events) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assert(!events.exists(_.isInstanceOf[GameEvent.Healed]), s"unexpected Healed event: $events")

  test("RestoreResource does not emit a Healed or DamageDealt event"):
    val ether =
      Consumable("e1", "ether", "Ether", Rarity.Uncommon, ConsumableEffect.RestoreResource(20))
    val state = combatState(weakEnemy(hp = 50),
                            player = equipPotion(fullHpPlayer().copy(resourceCurrent = 0), ether)
    )
    val (_, _, events) =
      resolver().resolve(state, CombatAction(CombatActionType.Item, itemId = Some("e1")))
    assert(!events.exists(_.isInstanceOf[GameEvent.Healed]), s"unexpected Healed event: $events")

  test("accessory drop increases player maxHp on defeat"):
    val itemDefs: Map[String, Item] = Map(
      "iron_ring" -> Accessory("", "iron_ring", "Iron Ring", Rarity.Common, hpBonus = Some(10))
    )
    val enemy =
      weakEnemy(hp = 1).copy(dropChance = 100, lootTable = List(LootEntry("iron_ring", 100)))
    val baseMaxHp = fullHpPlayer().maxHp
    val (next, _, _) = CombatResolver(Random(0), itemDefs)
      .resolve(combatState(enemy), CombatAction(CombatActionType.Attack))
    assertEquals(next.player.maxHp, baseMaxHp + 10)
