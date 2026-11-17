package roguelite.game.items

/** The numbers behind an item's one-line stat summary, resolved for a given player (see
  * [[Item.effectiveStats]]), so the client writes the line itself in the player's language instead
  * of showing an English string built here.
  *
  * Equipment fills the stat fields it carries (`maxHp`, `attack`, `defense`, `critChance`, already
  * multiplied by affinity where that applies). A consumable or a key names its effect in `effect`
  * (a stable id, see [[ItemStats.HealFixed]] and the other constants) with the figures that go
  * with it in `amount`, `percent` and `turns`.
  */
final case class ItemStats(
    maxHp: Option[Int] = None,
    attack: Option[Int] = None,
    defense: Option[Int] = None,
    critChance: Option[Int] = None,
    effect: Option[String] = None,
    amount: Option[Int] = None,
    percent: Option[Int] = None,
    turns: Option[Int] = None
)

object ItemStats:
  val HealFixed       = "healFixed"
  val HealPercent     = "healPercent"
  val RestoreResource = "restoreResource"
  val AttackBuff      = "attackBuff"
  val FlatDamage      = "flatDamage"
  val CritBuff        = "critBuff"
  val OpensDoor       = "opensDoor"

  /** The figures of a consumable's effect. */
  def of(effect: ConsumableEffect): ItemStats = effect match
    case ConsumableEffect.HealFixed(n)       => ItemStats(effect = Some(HealFixed), amount = Some(n))
    case ConsumableEffect.HealPercent(pct)   => ItemStats(effect = Some(HealPercent), percent = Some(pct))
    case ConsumableEffect.RestoreResource(n) => ItemStats(effect = Some(RestoreResource), amount = Some(n))
    case ConsumableEffect.AttackBuff(pct, t) =>
      ItemStats(effect = Some(AttackBuff), percent = Some(pct), turns = Some(t))
    case ConsumableEffect.FlatDamage(n)      => ItemStats(effect = Some(FlatDamage), amount = Some(n))
    case ConsumableEffect.CritBuff(pct, t)   =>
      ItemStats(effect = Some(CritBuff), percent = Some(pct), turns = Some(t))
