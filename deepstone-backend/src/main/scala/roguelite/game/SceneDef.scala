package roguelite.game

/** What an NPC asks of the player for a vow. Each kind is judged against the events of the run,
  * between the Rest where the vow was taken and the next one.
  */
enum VowType:
  /** Use no potion. */
  case NoPotion

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
  */
case class SceneDef(
    id: String,
    npc: String,
    minChapter: Int,
    requires: Option[String] = None,
    offersVow: Option[VowType] = None,
    requiresVowKept: Boolean = false
)
