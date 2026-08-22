package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[SetLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) covering each [[SetBonusEffect]] variant once, isolated
  * from and never needing to change alongside `data/sets.json` - only the "real catalog" section
  * at the bottom still touches the production file, and only for a size-independent smoke check.
  */
class SetLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"test_set_a","name":"Test Set A","classId":"warrior","twoPiece":{"type":"MaxHpPercent","label":"L1","value":5},"fourPiece":{"type":"FlatDefense","label":"L2","value":2}},
      |  {"id":"test_set_b","name":"Test Set B","classId":"archer","twoPiece":{"type":"FlatAttack","label":"L3","value":3},"fourPiece":{"type":"CritChancePercent","label":"L4","value":5}},
      |  {"id":"test_set_c","name":"Test Set C","classId":"mage","twoPiece":{"type":"AttackDamagePercent","label":"L5","value":10},"fourPiece":{"type":"AbilityCostReductionPercent","label":"L6","value":15}},
      |  {"id":"test_set_d","name":"Test Set D","classId":"warrior","twoPiece":{"type":"FirstAttackAlwaysCrit","label":"L7"},"fourPiece":{"type":"HealOnKillPercent","label":"L8","value":10}}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its id"):
    for defs <- SetLoader.loadAllFromJson(fixture)
    yield assertEquals(defs.size, 4)

  test("every SetBonusEffect variant decodes to its own case class"):
    for defs <- SetLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs("test_set_a").twoPiece.effect, SetBonusEffect.MaxHpPercent(5))
      assertEquals(defs("test_set_a").fourPiece.effect, SetBonusEffect.FlatDefense(2))
      assertEquals(defs("test_set_b").twoPiece.effect, SetBonusEffect.FlatAttack(3))
      assertEquals(defs("test_set_b").fourPiece.effect, SetBonusEffect.CritChancePercent(5))
      assertEquals(defs("test_set_c").twoPiece.effect, SetBonusEffect.AttackDamagePercent(10))
      assertEquals(defs("test_set_c").fourPiece.effect, SetBonusEffect.AbilityCostReductionPercent(15))
      assertEquals(defs("test_set_d").twoPiece.effect, SetBonusEffect.FirstAttackAlwaysCrit)
      assertEquals(defs("test_set_d").fourPiece.effect, SetBonusEffect.HealOnKillPercent(10))

  test("every set is assigned to its decoded class"):
    for defs <- SetLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs("test_set_a").classId, ClassId.Warrior)
      assertEquals(defs("test_set_b").classId, ClassId.Archer)
      assertEquals(defs("test_set_c").classId, ClassId.Mage)

  test("an unknown classId fails to parse"):
    val bad =
      """[{"id":"x","name":"X","classId":"paladin","twoPiece":{"type":"FlatAttack","label":"L","value":1},"fourPiece":{"type":"FlatAttack","label":"L","value":1}}]"""
    SetLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown bonus effect type fails to parse"):
    val bad =
      """[{"id":"x","name":"X","classId":"warrior","twoPiece":{"type":"NotReal","label":"L"},"fourPiece":{"type":"FlatAttack","label":"L","value":1}}]"""
    SetLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a value-based bonus effect missing its 'value' field fails to parse"):
    val bad =
      """[{"id":"x","name":"X","classId":"warrior","twoPiece":{"type":"FlatAttack","label":"L"},"fourPiece":{"type":"FlatAttack","label":"L","value":1}}]"""
    SetLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/sets.json resource loads successfully"):
    for defs <- SetLoader.loadAll()
    yield assert(defs.nonEmpty)

  test("every real set bonus has a non-empty label"):
    SetLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            s =>
              assert(s.twoPiece.label.nonEmpty, s"${s.id} 2pc label is empty")
              assert(s.fourPiece.label.nonEmpty, s"${s.id} 4pc label is empty")
