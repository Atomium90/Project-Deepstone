package roguelite.game

/** One NPC of the Rest rooms, loaded from `data/npcs.json`.
  *
  * @param id
  *   The NPC's id, e.g. "npc.1": the one the scenes of `story.json` name, and the one a Rest room
  *   is authored with. The name shown for the NPC is the text `story.<id>.name`.
  * @param spriteId
  *   The atlas sprite the client draws for the NPC, resolved here like an enemy's (see
  *   [[EnemyStats.spriteId]]) so a sprite is changed in one place, whatever the NPC looks like.
  * @param revealedBy
  *   The scene that gives the NPC's real name away. Until the player has seen it the NPC goes by
  *   its title (`story.<id>.name`), after it by its name (`story.<id>.name_revealed`). `None` for an
  *   NPC that gives its name from the start.
  */
case class NpcDef(id: String, spriteId: String, revealedBy: Option[String] = None)
