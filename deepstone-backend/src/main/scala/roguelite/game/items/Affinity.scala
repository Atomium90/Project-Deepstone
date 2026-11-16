package roguelite.game.items

/** The affinity rule: a weapon, armor or accessory whose type tag is one of the player's class
  * affinity tags has its bonuses doubled. In one place so the combat math and the stat line shown on
  * an item cannot disagree.
  */
object Affinity:

  /** How many times an item's bonuses count for a class with these affinity tags. */
  val MatchMultiplier = 2

  /** Whether an item of this type tag benefits from the class affinity. */
  def matches(typeTag: Option[String], affinityTags: Set[String]): Boolean =
    typeTag.exists(affinityTags.contains)

  def multiplier(typeTag: Option[String], affinityTags: Set[String]): Int =
    if matches(typeTag, affinityTags) then MatchMultiplier else 1
