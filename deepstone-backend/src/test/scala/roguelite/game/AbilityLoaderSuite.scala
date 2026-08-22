package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[AbilityLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/abilities.json` - only the "real catalog" section at the bottom still touches
  * the production file, and only for a size-independent smoke check.
  */
class AbilityLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"classId":"warrior","id":"test_slash","name":"Test Slash","cost":10,"resourceName":"Rage","description":"desc","effect":{"type":"DoubleNextAttack"}},
      |  {"classId":"archer","id":"test_shot","name":"Test Shot","cost":15,"resourceName":"Focus","description":"desc","effect":{"type":"IgnoreDefenseNextAttack"}},
      |  {"classId":"mage","id":"test_blast","name":"Test Blast","cost":20,"resourceName":"Mana","description":"desc","effect":{"type":"FlatDamage","amount":7}}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its classId"):
    for defs <- AbilityLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs.keySet, Set(ClassId.Warrior, ClassId.Archer, ClassId.Mage))
      assertEquals(defs(ClassId.Warrior).id, "test_slash")
      assertEquals(defs(ClassId.Warrior).cost, 10)
      assertEquals(defs(ClassId.Warrior).resourceName, "Rage")

  test("DoubleNextAttack and IgnoreDefenseNextAttack decode with no amount"):
    for defs <- AbilityLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs(ClassId.Warrior).effect, AbilityEffect.DoubleNextAttack)
      assertEquals(defs(ClassId.Archer).effect, AbilityEffect.IgnoreDefenseNextAttack)

  test("FlatDamage decodes with its amount"):
    for defs <- AbilityLoader.loadAllFromJson(fixture)
    yield assertEquals(defs(ClassId.Mage).effect, AbilityEffect.FlatDamage(7))

  test("FlatDamage missing its 'amount' field fails to parse"):
    val bad =
      """[{"classId":"mage","id":"x","name":"X","cost":1,"resourceName":"Mana","description":"d","effect":{"type":"FlatDamage"}}]"""
    AbilityLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown effect type fails to parse"):
    val bad =
      """[{"classId":"mage","id":"x","name":"X","cost":1,"resourceName":"Mana","description":"d","effect":{"type":"NotARealEffect"}}]"""
    AbilityLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown classId fails to parse"):
    val bad =
      """[{"classId":"paladin","id":"x","name":"X","cost":1,"resourceName":"Mana","description":"d","effect":{"type":"DoubleNextAttack"}}]"""
    AbilityLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/abilities.json resource loads successfully"):
    for defs <- AbilityLoader.loadAll()
    yield assert(defs.nonEmpty)
