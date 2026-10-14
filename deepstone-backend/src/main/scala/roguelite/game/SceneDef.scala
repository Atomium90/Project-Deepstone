package roguelite.game

/** What an NPC asks of the player for a vow. Each kind is judged against the events of the run,
  * between the Rest where the vow was taken and the next one.
  *
  * A vow is either held to (`achievement` false): it is kept as long as nothing goes against it, and
  * failed the moment something does. Or it is achieved (`achievement` true): it is kept the moment
  * the player has done what was asked, `goal` times, and failed if the stretch ends first.
  *
  * @param achievement
  *   Whether the vow asks the player to do something rather than to hold back from something.
  * @param goal
  *   How many times an achievement has to be done.
  * @param inertTraps
  *   Whether the traps summon nothing while the vow runs. A vow that forbids fighting must not be
  *   broken by a fight the dungeon forces.
  */
enum VowType(val achievement: Boolean, val goal: Int, val inertTraps: Boolean = false):
  /** Use no potion. */
  case NoPotion extends VowType(false, 1)

  /** Kill no ordinary enemy: a Boss may be killed, a MiniBoss counts as an ordinary enemy. */
  case NoOrdinaryKill extends VowType(false, 1, inertTraps = true)

  /** Defeat an Elite. */
  case DefeatElite extends VowType(true, 1)

  /** Gain two levels. */
  case GainTwoLevels extends VowType(true, 2)

  /** Pick up an item of Rare quality or better. */
  case FindRareItem extends VowType(true, 1)

/** One scene of an NPC, loaded from `data/story.json`. The server only decides which scene plays and
  * when: the lines themselves are texts of the client's language file, under the keys
  * `story.<id>.1`, `story.<id>.2` and so on.
  *
  * @param id
  *   The NPC id followed by the scene name, e.g. `npc.1.s2`. Also the prefix of the scene's text keys.
  * @param npc
  *   The id of the NPC that plays the scene, e.g. `npc.1`.
  * @param minChapter
  *   The scene is offered only once this chapter has been reached, which is what keeps an NPC from
  *   saying more than the story has revealed.
  * @param requires
  *   A scene of the same NPC that must have been played first.
  * @param offersVow
  *   The vow the NPC puts to the player at the end of the scene, if any. An NPC has at most one.
  * @param requiresVowKept
  *   The scene only plays once the NPC's vow has been kept and the NPC has commented on it.
  * @param requiresRelicRead
  *   The scene only plays once the relic of this Devoted has been read, i.e. once that Devoted fell
  *   while its inscription could be read (see [[StoryProgress.relicsRead]]).
  */
case class SceneDef(
    id: String,
    npc: String,
    minChapter: Int,
    requires: Option[String] = None,
    offersVow: Option[VowType] = None,
    requiresVowKept: Boolean = false,
    requiresRelicRead: Option[Int] = None
)
