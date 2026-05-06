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

import scala.util.Random

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

class CombatResolver(rng: Random = Random()):

  /** Entry point called by the StateMachine for every CombatAction. */
  def resolve(state: CombatState, action: CombatAction): (GameState, List[String]) =
    action.action match {
      case CombatActionType.Attack  => handleAttack(state)
      case CombatActionType.Ability => handleAbilityStub(state)
      case CombatActionType.Item    => handleItemStub(state)
      case CombatActionType.Defend  => handleDefend(state)
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

  private def handleAbilityStub(state: CombatState): (GameState, List[String]) =
    (state, List("Abilities are not yet implemented."))

  private def handleItemStub(state: CombatState): (GameState, List[String]) =
    (state, List("Item use is not yet implemented."))

  // ---------------------------------------------
  // Enemy turn
  // ---------------------------------------------

  private def enemyTurn(state: CombatState, priorLog: List[String]): (GameState, List[String]) =
    val enemyAction = pickEnemyAction(state.combat.enemy)

    enemyAction match {
      case "ATTACK" =>
        val rawDamage = calcDamage(state.combat.enemy.attack, state.combat.enemy.defense)
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
    val roll   = rng.nextInt(total)
    var cursor = 0
    for a <- enemy.actions do {
      cursor += a.weight
      if roll < cursor then return a.action
    }
    "ATTACK" // fallback

  // ---------------------------------------------
  // Outcome helpers
  // ---------------------------------------------

  /** Player wins: remove enemy from room, award XP, return to exploration. */
  private def victory(state: CombatState,
                      deadEnemy: EnemyInstance,
                      log: List[String]
  ): (GameState, List[String]) = {
    val xpGained      = deadEnemy.xpReward
    val updatedPlayer = state.player.copy(xp = state.player.xp + xpGained)

    // Remove the defeated enemy
    val updatedRoom = state.dungeon.currentRoom.removeEntity(deadEnemy.entityId)
    val updatedDungeon = state.dungeon.copy(
      rooms = state.dungeon.rooms.updated(updatedRoom.id, updatedRoom)
    )

    val victoryLog = log ++ List(
      s"${deadEnemy.label} has been defeated!",
      s"You gain $xpGained XP."
    )

    val nextState = ExplorationState(
      player = updatedPlayer,
      dungeon = updatedDungeon,
      playerX = state.playerX,
      playerY = state.playerY
    )
    (nextState, victoryLog)
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
  // Convenience: player and enemy stat accessors
  // ---------------------------------------------

  /** Will be tuned later. */
  extension (player: Player)
    /** Flat attack value. */
    private def attack: Int = player.level * 5 + (player.maxHp / 10)

    /** Flat defense value. */
    private def defense: Int = player.level * 2
