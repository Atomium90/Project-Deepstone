package roguelite.game

/** A conversation with a Rest NPC that the player has started and not read to its end, kept on
  * [[roguelite.engine.ExplorationState]] until they answer with a `SceneDone` action. Durable like
  * [[PendingEquipChoice]]: it is part of every update until resolved, and blocks movement and
  * interaction meanwhile.
  *
  * @param offerScene
  *   The scene whose choice and reply texts belong to the vow the conversation ends by offering, or
  *   `None` when it offers none. The client builds the keys of those texts from it.
  */
case class PendingScene(encounter: Encounter, offerScene: Option[String] = None)
