package roguelite.game

/** Condition that must become true for an achievement to unlock.
  *
  * Checked by AchievementChecker against either a [[GameEvent]] (most conditions) or, for
  * `TotalShardsSpent` and `AllUpgradesUnlocked`, directly from
  * [[roguelite.engine.GameSession.handleBuyUpgrade]] — BuyUpgrade never flows through the pure
  * resolvers that emit [[GameEvent]]s (see CLAUDE.md "BuyUpgrade routing").
  */
enum AchievementCondition:
  case FirstKill
  case DefeatBoss
  case ReachLevel(level: Int)
  case NoDamageVictory
  case FillInventory
  case TotalShardsSpent(amount: Int)
  case UnlockDoorWithKey
  case RevealSecretDoor
  case RunsCompleted(count: Int)
  case RunsWon(count: Int)
  case WinStreak(count: Int)
  case AllUpgradesUnlocked

/** Static definition of one achievement, loaded from `data/achievements.json`.
  *
  * @param displayOrder
  *   Position in the hub achievement panel (ascending). JSON array order is not preserved once
  *   loaded into a `Map`, so this field is the explicit source of truth for display order — same
  *   rationale as [[UpgradeDef.displayOrder]].
  */
case class AchievementDef(
    id: String,
    label: String,
    description: String,
    displayOrder: Int,
    condition: AchievementCondition
)
