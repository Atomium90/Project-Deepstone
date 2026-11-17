package roguelite.game.core

import munit.FunSuite
import roguelite.engine.{ ClassId, GamePhase, MessageProtocol, PlayerView, StateUpdate }

class LogLineSuite extends FunSuite:

  test("of builds a keyed line, ids as text and figures as numbers"):
    val line = LogLine.of("log.combat.strike", "enemy" -> "goblin", "damage" -> 12)
    assertEquals(line.key, "log.combat.strike")
    assertEquals(line.params, Map("enemy" -> LogValue.Text("goblin"), "damage" -> LogValue.Num(12)))

  test("a line with no values has no params"):
    assertEquals(LogLine.of("log.combat.defend").params, Map.empty)

  test("raw carries its text under the raw key, and only a raw line has one"):
    val raw = LogLine.raw("hello")
    assertEquals(raw.key, LogLine.RawKey)
    assertEquals(raw.rawText, Some("hello"))
    assertEquals(LogLine.of("log.combat.defend").rawText, None)

  test("valueOf tells a text from a figure"):
    assertEquals(LogLine.valueOf("a"), LogValue.Text("a"))
    assertEquals(LogLine.valueOf(3), LogValue.Num(3))

  test("a StateUpdate sends its log as keys and params, texts as strings and figures as numbers"):
    val update = StateUpdate(
      phase = GamePhase.Exploration,
      player = PlayerView(ClassId.Warrior, hp = 1, maxHp = 1, resourceCurrent = 0, resourceMax = 0, level = 1, xp = 0, metaCurrency = 0),
      log = List(LogLine.of("log.combat.strike", "enemy" -> "goblin", "damage" -> 12), LogLine.of("log.combat.defend"))
    )
    val json = io.circe.parser.parse(MessageProtocol.encodeUpdate(update)).toOption.get.hcursor
    assertEquals(json.downField("log").downN(0).get[String]("key"), Right("log.combat.strike"))
    assertEquals(json.downField("log").downN(0).downField("params").get[String]("enemy"), Right("goblin"))
    assertEquals(json.downField("log").downN(0).downField("params").get[Int]("damage"), Right(12))
    assertEquals(json.downField("log").downN(1).get[String]("key"), Right("log.combat.defend"))
    assert(json.downField("log").downN(1).downField("params").focus.exists(_.asObject.exists(_.isEmpty)))
