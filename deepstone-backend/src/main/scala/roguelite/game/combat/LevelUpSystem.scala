package roguelite.game

import roguelite.engine.Player

import scala.util.Random

/** Pure level-up logic: XP thresholds, perk selection, and perk application.
  *
  * XP is cumulative throughout a run and never resets. To determine whether a level-up is due, the
  * player's total XP is compared against [[cumulativeXpFor]] the next level.
  *
  * Threshold formula per level: `level × 100 + (level − 1) × 75`
  *   - Level 1 → 2: 100 XP
  *   - Level 2 → 3: 375 XP total
  *   - Level 3 → 4: 825 XP total
  *
  * Note for later: replace [[applyLevelUps]] with a transition to a new LevelUpState so the player
  * can choose their perk rather than receiving a random one.
  */
object LevelUpSystem:

  /** XP required to advance from `level` to `level + 1`. */
  def xpThreshold(level: Int): Int = level * 100 + (level - 1) * 75

  /** Total cumulative XP required to reach `targetLevel` from level 1. */
  def cumulativeXpFor(targetLevel: Int): Int =
    (1 until targetLevel).map(xpThreshold).sum

  /** Apply all pending level-ups to the player, returning the updated player and log lines.
    *
    * Handles multiple level-ups in a single call via tail recursion (e.g. killing a boss while
    * close to two thresholds). Each level awards one randomly selected perk.
    */
  def applyLevelUps(player: Player, rng: Random): (Player, List[String]) =
    @annotation.tailrec
    def loop(p: Player, acc: List[String]): (Player, List[String]) =
      if p.xp < cumulativeXpFor(p.level + 1) then (p, acc)
      else
        val perk                 = Perk.fromOrdinal(rng.nextInt(Perk.values.length))
        val (upgraded, perkDesc) = applyPerk(p, perk)
        val leveled              = upgraded.copy(level = upgraded.level + 1)
        loop(leveled, acc :+ s"⬆ Level ${leveled.level}! $perkDesc")
    loop(player, Nil)

  // Package-private for testability

  private[game] enum Perk:
    case MaxHpBoost, AttackBoost, DefenseBoost, ResourceBoost

  private[game] def applyPerk(player: Player, perk: Perk): (Player, String) =
    perk match {
      case Perk.MaxHpBoost =>
        val newMax = player.maxHp + 15
        // Also heal by the same amount so the player is not penalized for leveling up at low HP
        val newHp = (player.hp + 15).min(newMax)
        (player.copy(maxHp = newMax, hp = newHp), "+15 Max HP")

      case Perk.AttackBoost =>
        (player.copy(bonusAttack = player.bonusAttack + 2), "+2 Attack")

      case Perk.DefenseBoost =>
        (player.copy(bonusDefense = player.bonusDefense + 1), "+1 Defense")

      case Perk.ResourceBoost =>
        (player.copy(resourceMax = player.resourceMax + 20), "+20 Max Resource")
    }
