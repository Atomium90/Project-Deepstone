package roguelite.game

import roguelite.engine.Difficulty

/** Condition that must become true for an achievement to unlock.
  *
  * Checked by AchievementChecker against either a [[GameEvent]] (most conditions) or, for
  * `TotalShardsSpent` and `AllUpgradesUnlocked`, directly from
  * [[roguelite.engine.GameSession.handleBuyUpgrade]]: BuyUpgrade never flows through the pure
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
  case LootRarity(rarity: Rarity)
  case FourPieceSetActive
  case FillPotionBelt
  case FillPotionStack
  case WinOnDifficulty(difficulty: Difficulty)
  case ConsumablesUsed(count: Int)

  /** Defeat at least one Elite enemy. */
  case DefeatElite

  /** `count` is a fixed threshold baked into `achievements.json` (currently 5, the size of the
    * potion pool), not a live comparison against the loaded catalog - bump it by hand if the pool
    * ever grows.
    */
  case DistinctPotionTypesUsed(count: Int)

  /** Same fixed-threshold convention as [[DistinctPotionTypesUsed]] (currently 5, the size of the
    * perk pool) - only counts perks active in a *won* run, see [[roguelite.game.AchievementChecker]].
    */
  case DistinctPerksWonWith(count: Int)

/** Static definition of one achievement, loaded from `data/achievements.json`.
  *
  * @param displayOrder
  *   Position in the hub achievement panel (ascending). JSON array order is not preserved once
  *   loaded into a `Map`, so this field is the explicit source of truth for display order, same
  *   rationale as [[UpgradeDef.displayOrder]].
  */
case class AchievementDef(
    id: String,
    label: String,
    description: String,
    displayOrder: Int,
    condition: AchievementCondition
)
