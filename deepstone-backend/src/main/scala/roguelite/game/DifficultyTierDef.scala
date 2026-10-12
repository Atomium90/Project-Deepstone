package roguelite.game

import roguelite.engine.Difficulty

/** What a save must have reached before a difficulty tier can be started: `wins` victories on the
  * tier `winsAt`, and at least `shardsSpent` Shards spent on hub upgrades over the whole save.
  */
case class TierRequirement(winsAt: Difficulty, wins: Int, shardsSpent: Int)

/** Static definition of one difficulty tier, loaded from `data/difficulty.json`.
  *
  * @param requirement
  *   What unlocks the tier, or `None` for a tier that is always available.
  * @param themes
  *   The room theme of each section of a run on this tier, in order (see [[DungeonBuilder]]). A run
  *   with more sections than entries repeats the last theme.
  */
case class DifficultyTierDef(difficulty: Difficulty, requirement: Option[TierRequirement], themes: List[String])
