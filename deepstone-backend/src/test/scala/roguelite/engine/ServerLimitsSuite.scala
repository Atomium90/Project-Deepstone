package roguelite.engine

import munit.FunSuite

class ServerLimitsSuite extends FunSuite:

  /** One of each kind of action, written the way the client sends them, with long ids. */
  private val realActions = List(
    """{"type":"MOVE","direction":"UP"}""",
    """{"type":"INTERACT","targetId":"darkDungeon_combat_003#2_enemy_01"}""",
    """{"type":"COMBAT_ACTION","action":"ITEM","abilityId":"arcane_blast","itemId":"a1b2c3d4"}""",
    """{"type":"HUB_ACTION","action":"STARTRUN","classId":"warrior","upgradeId":"extra_potion_capacity","difficulty":"normal","perkId":"efficient_casting"}""",
    """{"type":"EQUIP_CHOICE","targetSlot":"ACCESSORY_0"}""",
    """{"type":"REWARD_CHOICE","itemId":"a1b2c3d4"}"""
  )

  test("the sample actions are real ones the server accepts"):
    realActions.foreach:
      json => assert(MessageProtocol.decodeAction(json).isRight, json)

  test("the message ceiling leaves at least twenty times the room a real action needs"):
    realActions.foreach:
      json => assert(json.length * 20 < ServerLimits.MaxWebSocketMessageBytes, json)

  test("the ceilings are positive"):
    assert(ServerLimits.MaxWebSocketMessageBytes > 0)
    assert(ServerLimits.MaxConnections > 0)
    assert(ServerLimits.OutgoingQueueSize > 0)
