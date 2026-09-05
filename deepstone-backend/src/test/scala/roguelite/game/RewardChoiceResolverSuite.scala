package roguelite.game

import munit.FunSuite
import roguelite.engine.{ ClassId, ExplorationState, Player, PlayerFixtures }

/** Tests for [[RewardChoiceResolver]]: resolving a follow-up `RewardChoice` action against a
  * [[PendingRewardChoice]] - walking away, an invalid pick, and each [[PickupOutcome]] the chosen
  * item can produce via [[EquipmentResolver.resolvePickup]].
  */
class RewardChoiceResolverSuite extends FunSuite:

  private def player: Player = PlayerFixtures.startingPlayer(ClassId.Warrior)

  private def testDungeon: Dungeon =
    Dungeon(rooms = Map("r1" -> Room("r1", RoomType.Combat, 4, 4, Vector.fill(4)(Vector.fill(4)(Tile.Floor)), Nil)),
            currentRoomId = "r1"
    )

  private def explorationState(p: Player = player, pending: Option[PendingRewardChoice] = None): ExplorationState =
    ExplorationState(p, testDungeon, playerX = 1, playerY = 1, pendingRewardChoice = pending)

  private val sword          = Weapon("w1", "iron_sword", "Iron Sword", Rarity.Common, attackBonus = 3)
  private val sword2         = Weapon("w2", "steel_sword", "Steel Sword", Rarity.Uncommon, attackBonus = 7)
  private val swordDuplicate = Weapon("w1b", "iron_sword", "Iron Sword", Rarity.Common, attackBonus = 3)
  private val armor          = Armor("a1", "leather_armor", "Leather Armor", Rarity.Common, defenseBonus = 2)
  private val key            = Key("k1", "rusty_key", "Rusty Key", Rarity.Common, KeyKind.Generic)

  test("resolve with no pending choice logs an error and leaves state unchanged"):
    val exp = explorationState()
    val (next, log, events) = RewardChoiceResolver.resolve(exp, Some(sword.id))
    assertEquals(next, exp)
    assert(log.exists(_.toLowerCase.contains("no reward choice")), s"expected a no-pending-choice message: $log")
    assertEquals(events, Nil)

  test("resolve(None) walks away and clears the pending choice, player unchanged"):
    val pending = PendingRewardChoice(List(sword, armor, key))
    val exp     = explorationState(pending = Some(pending))
    val (next, log, events) = RewardChoiceResolver.resolve(exp, None)
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingRewardChoice, None)
    assertEquals(nextExp.player, exp.player)
    assert(log.exists(_.toLowerCase.contains("leave")), s"expected a walk-away message: $log")
    assertEquals(events, Nil)

  test("resolve with an id that wasn't offered is rejected, pending choice stays set"):
    val pending = PendingRewardChoice(List(sword, armor, key))
    val exp     = explorationState(pending = Some(pending))
    val (next, log, events) = RewardChoiceResolver.resolve(exp, Some("not-a-real-id"))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingRewardChoice, Some(pending))
    assert(log.exists(_.toLowerCase.contains("invalid")), s"expected an invalid-choice message: $log")
    assertEquals(events, Nil)

  test("resolve picking an item for an empty slot equips it and clears the pending choice"):
    val pending = PendingRewardChoice(List(sword, armor, key))
    val exp     = explorationState(pending = Some(pending))
    val (next, log, events) = RewardChoiceResolver.resolve(exp, Some(sword.id))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingRewardChoice, None)
    assertEquals(nextExp.player.equippedWeapon, Some(sword))
    assert(log.exists(_.contains(sword.name)), s"expected the item's name in the log: $log")
    assert(events.exists(_.isInstanceOf[GameEvent.ItemPickedUp]), s"expected ItemPickedUp: $events")

  test("resolve picking a Key increments the key count"):
    val pending = PendingRewardChoice(List(sword, armor, key))
    val exp     = explorationState(pending = Some(pending))
    val (next, _, _) = RewardChoiceResolver.resolve(exp, Some(key.id))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.player.keyCounts.getOrElse(KeyKind.Generic, 0), 1)

  test("resolve picking an item for a full slot offers an equip choice instead"):
    val basePlayer = player.copy(equippedWeapon = Some(sword))
    val pending    = PendingRewardChoice(List(sword2, armor, key))
    val exp        = explorationState(basePlayer, pending = Some(pending))
    val (next, log, events) = RewardChoiceResolver.resolve(exp, Some(sword2.id))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingRewardChoice, None)
    assertEquals(nextExp.pendingEquipChoice.map(_.newItem), Some(sword2))
    assertEquals(nextExp.player.equippedWeapon, Some(sword), "weapon should not swap until the equip choice resolves")
    assert(log.exists(_.contains(sword2.name)), s"expected the item's name in the log: $log")
    assertEquals(events, Nil)

  test("resolve picking a same-typeId, non-strictly-better duplicate discards it"):
    val basePlayer = player.copy(equippedWeapon = Some(sword)) // already holds the Common copy
    val pending    = PendingRewardChoice(List(swordDuplicate, armor, key)) // same typeId, same rarity
    val exp        = explorationState(basePlayer, pending = Some(pending))
    val (next, log, events) = RewardChoiceResolver.resolve(exp, Some(swordDuplicate.id))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingRewardChoice, None)
    assertEquals(nextExp.player.equippedWeapon, Some(sword), "should keep the copy already equipped")
    assert(log.exists(_.toLowerCase.contains("already have a better one")), s"expected a discard message: $log")
    assertEquals(events, Nil)
