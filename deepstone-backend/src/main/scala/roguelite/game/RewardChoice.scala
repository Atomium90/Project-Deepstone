package roguelite.game

/** A [[Shrine]]'s rolled reward options, awaiting a follow-up `RewardChoice` action to pick one
  * (or walk away with nothing). Held on [[roguelite.engine.GameState.ExplorationState]] until
  * resolved - durable state, not a transient event, same discipline as
  * [[roguelite.game.PendingEquipChoice]] (survives a reconnect or an unrelated action sent in
  * between). The Shrine entity itself is already removed from the room at the point this is
  * created (mirrors a [[Chest]] being removed on open), so nothing further ties back to it here.
  *
  * @param options
  *   The 3 rolled candidate items (see [[LootTable.rollShrineChoices]]). Each already carries a
  *   distinct instance id (from `withNewId`), so a follow-up `RewardChoice` action picks by item
  *   id, not by position.
  */
case class PendingRewardChoice(options: List[Item])
