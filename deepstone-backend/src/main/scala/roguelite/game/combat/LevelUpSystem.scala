package roguelite.game.combat

import roguelite.engine.Player
import roguelite.game.core.LogLine

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
  def applyLevelUps(player: Player, rng: Random): (Player, List[LogLine]) =
    @annotation.tailrec
    def loop(p: Player, acc: List[LogLine]): (Player, List[LogLine]) =
      if p.xp < cumulativeXpFor(p.level + 1) then (p, acc)
      else
        val perk                 = Perk.fromOrdinal(rng.nextInt(Perk.values.length))
        val (upgraded, gain) = applyPerk(p, perk)
        val leveled          = upgraded.copy(level = upgraded.level + 1)
        loop(leveled, acc :+ LogLine.of(s"log.levelUp.${gain.kind}", "level" -> leveled.level, "amount" -> gain.amount))
    loop(player, Nil)

  // Package-private for testability

  private[game] enum Perk:
    case MaxHpBoost, AttackBoost, DefenseBoost, ResourceBoost

  /** What a level-up perk gave: its kind (the end of the `log.levelUp.<kind>` key) and the amount. */
  private[game] final case class PerkGain(kind: String, amount: Int)

  private[game] def applyPerk(player: Player, perk: Perk): (Player, PerkGain) =
    perk match {
      case Perk.MaxHpBoost =>
        val newMax = player.maxHp + 15
        // Also heal by the same amount so the player is not penalized for leveling up at low HP
        val newHp = (player.hp + 15).min(newMax)
        (player.copy(maxHp = newMax, hp = newHp), PerkGain("maxHp", 15))

      case Perk.AttackBoost =>
        (player.copy(bonusAttack = player.bonusAttack + 2), PerkGain("attack", 2))

      case Perk.DefenseBoost =>
        (player.copy(bonusDefense = player.bonusDefense + 1), PerkGain("defense", 1))

      case Perk.ResourceBoost =>
        (player.copy(resourceMax = player.resourceMax + 20), PerkGain("resource", 20))
    }
