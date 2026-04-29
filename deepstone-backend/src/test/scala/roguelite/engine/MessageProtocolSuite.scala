package roguelite.engine

import munit.FunSuite

class MessageProtocolSuite extends FunSuite:

  // -- decodeAction ----------------------------------------------------------

  test("decode MOVE action with valid direction"):
    val json   = """{"type":"MOVE","direction":"UP"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(Move(Direction.Up)))

  test("decode MOVE action is case-insensitive for direction"):
    val json   = """{"type":"move","direction":"down"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(Move(Direction.Down)))

  test("decode INTERACT action"):
    val json   = """{"type":"INTERACT","targetId":"enemy_01"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(Interact("enemy_01")))

  test("decode COMBAT_ACTION with attack"):
    val json   = """{"type":"COMBAT_ACTION","action":"ATTACK"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(CombatAction(CombatActionType.Attack)))

  test("decode COMBAT_ACTION with ability and abilityId"):
    val json   = """{"type":"COMBAT_ACTION","action":"ABILITY","abilityId":"fireball"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result,
                 Right(CombatAction(CombatActionType.Ability, abilityId = Some("fireball")))
    )

  test("decode HUB_ACTION start run"):
    val json   = """{"type":"HUB_ACTION","action":"STARTRUN","classId":"warrior"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior))))

  test("decode HUB_ACTION buy upgrade"):
    val json   = """{"type":"HUB_ACTION","action":"BUYUPGRADE","upgradeId":"hp_boost_1"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1"))))

  test("decode unknown action type returns Left"):
    val json   = """{"type":"EXPLODE"}"""
    val result = MessageProtocol.decodeAction(json)
    assert(result.isLeft, s"Expected Left but got $result")

  test("decode malformed JSON returns Left"):
    val result = MessageProtocol.decodeAction("not json at all")
    assert(result.isLeft)

  test("decode missing type field returns Left"):
    val result = MessageProtocol.decodeAction("""{"direction":"UP"}""")
    assert(result.isLeft)

  // -- encodeUpdate ----------------------------------------------------------

  test("encodeUpdate produces valid JSON string"):
    val update = StateUpdate(
      phase = GamePhase.Hub,
      player = PlayerView(ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = 0
      ),
      log = List("Welcome to the hub.")
    )
    val json = MessageProtocol.encodeUpdate(update)
    assert(json.contains("\"hub\""), s"Expected 'hub' phase in JSON: $json")
    assert(json.contains("Welcome to the hub."), s"Expected log message in JSON: $json")

  test("encodeUpdate with optional fields absent produces null or omitted fields"):
    val update = StateUpdate(
      phase = GamePhase.Hub,
      player = PlayerView(ClassId.Mage,
                          hp = 70,
                          maxHp = 70,
                          resourceCurrent = 80,
                          resourceMax = 80,
                          level = 1,
                          xp = 0,
                          metaCurrency = 5
      )
    )
    val json = MessageProtocol.encodeUpdate(update)
    // room, combat should be null when absent (Circe default for Option)
    assert(json.contains("\"room\":null") || !json.contains("\"room\""))
