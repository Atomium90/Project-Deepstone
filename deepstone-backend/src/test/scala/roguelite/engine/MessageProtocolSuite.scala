package roguelite.engine

import munit.FunSuite
import roguelite.game.EquipSlot

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

  test("decode HUB_ACTION start run with difficulty"):
    val json   = """{"type":"HUB_ACTION","action":"STARTRUN","classId":"mage","difficulty":"hard"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(
      result,
      Right(
        HubAction(HubActionType.StartRun,
                  classId = Some(ClassId.Mage),
                  difficulty = Some(Difficulty.Hard)
        )
      )
    )

  test("decode HUB_ACTION start run without difficulty leaves it None"):
    val json   = """{"type":"HUB_ACTION","action":"STARTRUN","classId":"warrior"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result.map(_.asInstanceOf[HubAction].difficulty), Right(None))

  test("decode HUB_ACTION with unknown difficulty returns Left"):
    val json   = """{"type":"HUB_ACTION","action":"STARTRUN","classId":"warrior","difficulty":"nightmare"}"""
    val result = MessageProtocol.decodeAction(json)
    assert(result.isLeft, s"Expected Left but got $result")

  test("decode HUB_ACTION buy upgrade"):
    val json   = """{"type":"HUB_ACTION","action":"BUYUPGRADE","upgradeId":"hp_boost_1"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1"))))

  test("decode EQUIP_CHOICE with a weapon slot target"):
    val json   = """{"type":"EQUIP_CHOICE","targetSlot":"WEAPON"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(EquipChoice(targetSlot = Some(EquipSlot.WeaponSlot))))

  test("decode EQUIP_CHOICE without a targetSlot means keep the current item"):
    val json   = """{"type":"EQUIP_CHOICE"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(EquipChoice(targetSlot = None)))

  test("decode EQUIP_CHOICE with an accessory slot index"):
    val json   = """{"type":"EQUIP_CHOICE","targetSlot":"ACCESSORY_1"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(EquipChoice(targetSlot = Some(EquipSlot.AccessorySlot(1)))))

  test("decode EQUIP_CHOICE with a potion belt slot index"):
    val json   = """{"type":"EQUIP_CHOICE","targetSlot":"POTION_2"}"""
    val result = MessageProtocol.decodeAction(json)
    assertEquals(result, Right(EquipChoice(targetSlot = Some(EquipSlot.PotionSlot(2)))))

  test("decode EQUIP_CHOICE with an unknown slot string returns Left"):
    val json   = """{"type":"EQUIP_CHOICE","targetSlot":"HELMET"}"""
    val result = MessageProtocol.decodeAction(json)
    assert(result.isLeft, s"Expected Left but got $result")

  test("EquipSlot encode/decode round-trips for every case"):
    import MessageProtocol.given
    import io.circe.syntax.*
    val slots = List(
      EquipSlot.WeaponSlot,
      EquipSlot.ArmorSlot,
      EquipSlot.AccessorySlot(0),
      EquipSlot.AccessorySlot(1),
      EquipSlot.PotionSlot(0),
      EquipSlot.PotionSlot(1),
      EquipSlot.PotionSlot(2)
    )
    slots.foreach:
      slot =>
        val encoded = slot.asJson
        assertEquals(encoded.as[EquipSlot], Right(slot), s"round-trip failed for $slot")

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

  test("encodeUpdate includes soundEvents tags"):
    val update = StateUpdate(
      phase = GamePhase.Exploration,
      player = PlayerView(ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = 0
      ),
      soundEvents = List("door_open", "pickup")
    )
    val json = MessageProtocol.encodeUpdate(update)
    assert(json.contains("\"soundEvents\""), s"Expected soundEvents field in JSON: $json")
    assert(json.contains("door_open"), s"Expected door_open tag in JSON: $json")

  test("encodeUpdate includes pendingEquipChoice with the slot encoded as a string"):
    val update = StateUpdate(
      phase = GamePhase.Exploration,
      player = PlayerView(ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = 0
      ),
      pendingEquipChoice = Some(
        PendingEquipChoiceView(
          newItem = ItemView("i1", "iron_sword", "Iron Sword", "weapon", "common", "+3 ATK"),
          options = List(
            EquipChoiceOptionView(EquipSlot.WeaponSlot,
                                  ItemView("i0", "hunters_bow", "Hunter's Bow", "weapon", "common", "+5 ATK")
            )
          )
        )
      )
    )
    val json = MessageProtocol.encodeUpdate(update)
    assert(json.contains("\"pendingEquipChoice\""), s"expected pendingEquipChoice field: $json")
    assert(json.contains("\"WEAPON\""), s"expected the slot encoded as WEAPON: $json")

  test("encodeUpdate omits pendingEquipChoice when absent"):
    val update = StateUpdate(
      phase = GamePhase.Exploration,
      player = PlayerView(ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = 0
      )
    )
    val json = MessageProtocol.encodeUpdate(update)
    assert(json.contains("\"pendingEquipChoice\":null") || !json.contains("\"pendingEquipChoice\":{"))

  test("encodeUpdate includes isBoss on CombatView"):
    val update = StateUpdate(
      phase = GamePhase.Combat,
      player = PlayerView(ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = 0
      ),
      combat = Some(
        CombatView(enemyId = "e1",
                   enemyLabel = "Dungeon Boss",
                   enemyHp = 50,
                   enemyMaxHp = 50,
                   isPlayerTurn = true,
                   isBoss = true
        )
      )
    )
    val json = MessageProtocol.encodeUpdate(update)
    assert(json.contains("\"isBoss\":true"), s"Expected isBoss:true in JSON: $json")
