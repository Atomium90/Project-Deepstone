package roguelite.game

import roguelite.engine.{ ExplorationState, GameState }

/** Resolves a follow-up `RewardChoice` action against a [[roguelite.engine.GameState.ExplorationState]]'s
  * `pendingRewardChoice` (see [[PendingRewardChoice]]), mirroring
  * [[EquipmentResolver.resolveChoice]]'s exact shape. The chosen item is fed through the same
  * [[EquipmentResolver.resolvePickup]] every other loot source uses, so a pick that lands on a
  * full slot still falls through to the existing keep/replace `EquipChoice` flow rather than
  * needing its own equip logic.
  */
object RewardChoiceResolver:

  /** `itemId = None` means "walk away, take nothing". `Some(id)` must match one of the offered
    * options' item id; anything else (no choice pending, or an id that wasn't actually offered)
    * is rejected with a log message and the pending choice, if any, is left untouched so the
    * client can retry.
    */
  def resolve(exp: ExplorationState,
             itemId: Option[String],
             setDefs: Map[String, SetDef] = Map.empty
  ): (GameState, List[String], List[GameEvent]) =
    exp.pendingRewardChoice match
      case None => (exp, List("No reward choice pending."), Nil)
      case Some(pending) =>
        itemId match
          case None =>
            (exp.copy(pendingRewardChoice = None), List("You leave the shrine's other offerings behind."), Nil)

          case Some(id) if !pending.options.exists(_.id == id) =>
            (exp, List("Invalid reward choice."), Nil)

          case Some(id) =>
            val chosen  = pending.options.find(_.id == id).get
            val cleared = exp.copy(pendingRewardChoice = None)
            EquipmentResolver.resolvePickup(cleared.player, chosen, setDefs) match
              case PickupOutcome.Equipped(p) =>
                (cleared.copy(player = p),
                 List(s"You take ${chosen.name}! (${chosen.statLine})"),
                 List(GameEvent.itemPickedUp(p, chosen, setDefs))
                )
              case PickupOutcome.KeyCollected(p) =>
                (cleared.copy(player = p),
                 List(s"You take ${chosen.name}! (${chosen.statLine})"),
                 List(GameEvent.itemPickedUp(p, chosen, setDefs))
                )
              case PickupOutcome.ChoicePending(equipPending) =>
                (cleared.copy(pendingEquipChoice = Some(equipPending)),
                 List(s"You take ${chosen.name}. Choose what to do with it."),
                 Nil
                )
              case PickupOutcome.Discarded(p) =>
                (cleared.copy(player = p),
                 List(s"You take ${chosen.name}, but you already have a better one."),
                 Nil
                )
