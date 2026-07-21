package roguelite.game

/** Static dialogue content for one NPC, loaded from `data/npcs.json` and keyed by id (matching the
  * `id` of the corresponding [[Npc]] entity placed in rooms.json).
  *
  * @param dialogue
  *   Shown once, in order, one line per interaction.
  * @param fallbackDialogue
  *   Rotated through once `dialogue` is exhausted. May be empty, in which case
  *   [[InteractionResolver]] keeps re-showing the last line of `dialogue` instead.
  */
case class NpcDialogueDef(
    id: String,
    name: String,
    dialogue: List[String],
    fallbackDialogue: List[String] = Nil
)
