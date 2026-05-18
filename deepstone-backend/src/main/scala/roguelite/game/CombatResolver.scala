package roguelite.game

import roguelite.engine.{
  CombatAction,
  CombatActionType,
  CombatState,
  ExplorationState,
  GameOverState,
  GameState,
  Player
}

import scala.util.{ boundary, Random }
import boundary.break

/** Resolves combat turns and produces new game states.
  *
  * This class owns all combat math. The [[roguelite.engine.StateMachine]] delegates every
  * [[CombatAction]] here, keeping the state machine thin.
  *
  * <p>Turn sequence per player action:</p> <ul> <li>Apply player action (Attack / Defend / Ability
  * stub / Item stub)</li> <li>If enemy dies → victory: return to ExplorationState, award XP</li>
  * <li>If player acts without killing enemy → resolve enemy turn</li> <li>If player dies from enemy
  * counter → GameOverState</li> <li>Otherwise → return to CombatState, player's turn again</li>
  * </ul>
  *
  * @param rng
  *   Random instance — inject a seeded one for deterministic tests.
  */
class CombatResolver(rng: Random = Random(), itemDefs: Map[String, Item] = Map.empty):

  /** Entry point called by the StateMachine for every CombatAction. */
  def resolve(state: CombatState, action: CombatAction): (GameState, List[String]) =
    action.action match {
      case CombatActionType.Attack  => handleAttack(state)
      case CombatActionType.Defend  => handleDefend(state)
      case CombatActionType.Item    => handleItem(state, action.itemId)
      case CombatActionType.Ability => handleAbilityStub(state)
    }

  // ---------------------------------------------
  // Player actions
  // ---------------------------------------------

  private def handleAttack(state: CombatState): (GameState, List[String]) =
    val damage       = calcDamage(state.player.attack, state.combat.enemy.defense)
    val damagedEnemy = state.combat.enemy.takeDamage(damage)
    val log          = List(s"You strike the ${damagedEnemy.label} for $damage damage.")

    if !damagedEnemy.isAlive then victory(state, damagedEnemy, log)
    else
      val afterPlayer =
        state.copy(combat = state.combat.copy(enemy = damagedEnemy, playerIsDefending = false))
      enemyTurn(afterPlayer, log)

  private def handleDefend(state: CombatState): (GameState, List[String]) =
    val log         = List("You brace for impact. Incoming damage is halved this round.")
    val afterPlayer = state.copy(combat = state.combat.copy(playerIsDefending = true))
    enemyTurn(afterPlayer, log)

  private def handleItem(state: CombatState, itemId: Option[String]): (GameState, List[String]) =
    itemId match {
      case None =>
        (state, List("No item selected."))
      case Some(id) =>
        state.player.inventory.findById(id) match {
          case None =>
            (state, List("Item not found in inventory."))
          case Some((_, nonConsumable)) if !nonConsumable.isInstanceOf[Consumable] =>
            (state, List(s"${nonConsumable.name} cannot be used in combat."))
          case Some((idx, consumable: Consumable)) =>
            val (_, newInventory)          = state.player.inventory.removeAt(idx)
            val playerWithoutItem          = state.player.copy(inventory = newInventory)
            val (updatedPlayer, effectLog) = applyConsumableEffect(playerWithoutItem, consumable)
            val afterPlayer =
              state.copy(player = updatedPlayer,
                         combat = state.combat.copy(playerIsDefending = false)
              )
            enemyTurn(afterPlayer, effectLog)
        }
    }

  private def handleAbilityStub(state: CombatState): (GameState, List[String]) =
    (state, List("Abilities are not yet implemented."))

  // ---------------------------------------------
  // Consumable effect application
  // ---------------------------------------------

  private def applyConsumableEffect(player: Player, item: Consumable): (Player, List[String]) =
    item.effect match
      case ConsumableEffect.HealFixed(amount) =>
        val before = player.hp
        val after  = (player.hp + amount).min(player.maxHp)
        val healed = after - before
        (player.copy(hp = after), List(s"You use ${item.name}. Restored $healed HP."))

      case ConsumableEffect.HealPercent(pct) =>
        val amount = (player.maxHp * pct / 100).max(1)
        val before = player.hp
        val after  = (player.hp + amount).min(player.maxHp)
        val healed = after - before
        (player.copy(hp = after), List(s"You use ${item.name}. Restored $healed HP."))

      case ConsumableEffect.RestoreResource(amount) =>
        val before   = player.resourceCurrent
        val after    = (player.resourceCurrent + amount).min(player.resourceMax)
        val restored = after - before
        (player.copy(resourceCurrent = after),
         List(s"You use ${item.name}. Restored $restored resource.")
        )

  // ---------------------------------------------
  // Enemy turn
  // ---------------------------------------------

  private def enemyTurn(state: CombatState, priorLog: List[String]): (GameState, List[String]) =
    val enemyAction = pickEnemyAction(state.combat.enemy)

    enemyAction match {
      case "ATTACK" =>
        val rawDamage = calcDamage(state.combat.enemy.attack, state.player.defense)
        val finalDamage =
          if state.combat.playerIsDefending then (rawDamage / 2).max(1) else rawDamage
        val newHp         = (state.player.hp - finalDamage).max(0)
        val damagedPlayer = state.player.copy(hp = newHp)
        val defendNote    = if state.combat.playerIsDefending then " (halved)" else ""
        val log =
          priorLog :+ s"${state.combat.enemy.label} attacks you for $finalDamage damage$defendNote."

        if newHp <= 0 then defeat(state, damagedPlayer, log)
        else
          val nextCombat = state.combat.copy(isPlayerTurn = true,
                                             round = state.combat.round + 1,
                                             playerIsDefending = false
          )
          (state.copy(player = damagedPlayer, combat = nextCombat), log)

      case "DEFEND" =>
        val log = priorLog :+ s"${state.combat.enemy.label} takes a defensive stance."
        val nextCombat = state.combat.copy(isPlayerTurn = true,
                                           round = state.combat.round + 1,
                                           playerIsDefending = false
        )
        (state.copy(combat = nextCombat), log)

      case other =>
        // Unknow action -> skip enemy turn
        val log = priorLog :+ s"${state.combat.enemy.label} hesitates."
        (state.copy(combat = state.combat.copy(isPlayerTurn = true)), log)
    }

  /** Pick an enemy action using weighted random selection. */
  private def pickEnemyAction(enemy: EnemyInstance): String =
    val total = enemy.actions.map(_.weight).sum
    if total <= 0 then return "ATTACK"
    val roll = rng.nextInt(total)
    enemy.actions
      .foldLeft((0, Option.empty[String])):
        (acc, a) =>
          val (cursor, found) = acc
          val next            = cursor + a.weight
          val pick            = if found.isEmpty && roll < next then Some(a.action) else found
          (next, pick)
      ._2
      .getOrElse("ATTACK")

  // ---------------------------------------------
  // Outcome helpers
  // ---------------------------------------------

  /** Player wins: remove enemy from room, award XP, roll for a loot drop, return to exploration. */
  private def victory(state: CombatState,
                      deadEnemy: EnemyInstance,
                      log: List[String]
  ): (GameState, List[String]) = {
    val xpGained     = deadEnemy.xpReward
    val playerWithXp = state.player.copy(xp = state.player.xp + xpGained)

    // Remove the defeated enemy
    val updatedRoom = state.dungeon.currentRoom.removeEntity(deadEnemy.entityId)
    val updatedDungeon = state.dungeon.copy(
      rooms = state.dungeon.rooms.updated(updatedRoom.id, updatedRoom)
    )

    val victoryLog = log ++ List(
      s"${deadEnemy.label} has been defeated!",
      s"You gain $xpGained XP."
    )

    val (finalPlayer, lootLog) = LootTable.rollEnemy(deadEnemy, itemDefs, rng) match {
      case None => (playerWithXp, Nil)
      case Some(item) =>
        playerWithXp.withItemPickup(item) match {
          case Right(p) =>
            (p, List(s"${deadEnemy.label} dropped ${item.name}! (${item.statLine})"))
          case Left(_) =>
            (playerWithXp,
             List(s"${deadEnemy.label} dropped ${item.name}, but your inventory is full!")
            )
        }
    }

    val nextState = ExplorationState(
      player = finalPlayer,
      dungeon = updatedDungeon,
      playerX = state.playerX,
      playerY = state.playerY
    )
    (nextState, victoryLog ++ lootLog)
  }

  /** Player loses: transition to GameOver, preserve meta-currency. */
  private def defeat(state: CombatState,
                     deadPlayer: Player,
                     log: List[String]
  ): (GameState, List[String]) =
    val defeatLog = log :+ "You have been defeated. The dungeon claims another soul."
    (GameOverState(deadPlayer), defeatLog)

  // ---------------------------------------------
  // Damage formula
  // ---------------------------------------------

  /** Compute damage dealt from an attacker to a defender.
    *
    * Formula: attack - defense + jitter(-2..+2), floored at 1. The jitter prevents combat from
    * being fully deterministic while keeping outcomes predictable enough to be fair.
    */
  private[game] def calcDamage(attack: Int, defense: Int): Int =
    val jitter = rng.nextInt(5) - 2 // range: -2 to +2
    (attack - defense + jitter).max(1)

  // ---------------------------------------------
  // Player stat accessors (include inventory bonuses)
  // ---------------------------------------------

  /** Will be tuned later. */
  extension (player: Player)
    /** Effective attack: base formula + weapon bonuses from inventory. */
    private def attack: Int =
      player.level * 5 + (player.maxHp / 10) + player.inventory.totalAttackBonus

    /** Effective defense: base formula + armor bonuses from inventory. */
    private def defense: Int = player.level * 2 + player.inventory.totalDefenseBonus
