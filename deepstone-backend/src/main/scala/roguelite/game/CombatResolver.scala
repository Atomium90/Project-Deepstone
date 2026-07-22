package roguelite.game

import roguelite.engine.{
  ClassId,
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
  * Turn sequence per player action:
  *   - Apply player action (Attack / Defend / Ability / Item)
  *   - If enemy dies → victory: return to ExplorationState, award XP
  *   - If player acts without killing enemy → resolve enemy counter-action
  *   - If player dies from enemy counter → GameOverState
  *   - Otherwise → return to CombatState, player's turn again
  *
  * Resource generation rules (applied inside enemyTurn and player action handlers):
  *   - Warrior: +5 Rage when attacking, +10 Rage when hit by an enemy
  *   - Archer: +8 Focus when defending, +5 Focus at the end of every round
  *   - Mage: no in-combat regeneration (pure resource-management pressure)
  *
  * Ability cost checks: insufficient resource does NOT consume the player's turn. The state is
  * returned unchanged so the player can choose a different action. Ability data (cost, resource
  * name, and effect) comes from [[abilityDefs]] — see [[AbilityDef]] — so adding or tuning a class
  * ability never requires touching this class.
  *
  * @param rng
  *   Random instance — inject a seeded one for deterministic tests.
  * @param abilityDefs
  *   Loaded ability catalog, keyed by class. See [[AbilityLoader]].
  */
class CombatResolver(rng: Random = Random(),
                     itemDefs: Map[String, Item] = Map.empty,
                     abilityDefs: Map[ClassId, AbilityDef] = Map.empty
):

  /** Entry point called by the StateMachine for every CombatAction. */
  def resolve(state: CombatState, action: CombatAction): (GameState, List[String], List[GameEvent]) =
    action.action match {
      case CombatActionType.Attack  => handleAttack(state)
      case CombatActionType.Defend  => handleDefend(state)
      case CombatActionType.Item    => handleItem(state, action.itemId)
      case CombatActionType.Ability => handleAbility(state)
    }

  // ---------------------------------------------
  // Player actions
  // ---------------------------------------------

  private def handleAttack(state: CombatState): (GameState, List[String], List[GameEvent]) =
    val pending = state.combat.pendingAbility

    // Ability activation banner — the pending effect was armed by this player's own class
    // ability, so the ability name is always resolvable from their classId.
    val abilityLog: List[String] = pending match {
      case Some(_) => abilityDefs.get(state.player.classId).map(a => s"${a.name} activates!").toList
      case None    => Nil
    }

    // Damage modified by pending ability effect
    val damage = pending match {
      case Some(PendingAbilityEffect.DoubleNextAttack) =>
        // Double the full post-jitter value
        calcDamage(state.player.attack, state.combat.enemy.defense) * 2
      case Some(PendingAbilityEffect.IgnoreDefenseNextAttack) =>
        // Roll against 0 defense
        calcDamage(state.player.attack, 0)
      case None =>
        calcDamage(state.player.attack, state.combat.enemy.defense)
    }

    val damagedEnemy = state.combat.enemy.takeDamage(damage)
    val strikeLog    = List(s"You strike the ${damagedEnemy.label} for $damage damage.")
    val log          = abilityLog ++ strikeLog

    // Warrior: +5 Rage on every action
    val playerAfterResource = gainResource(state.player, onAttack = true)

    // Consume the pending effect
    val updatedCombat = state.combat.copy(
      enemy = damagedEnemy,
      pendingAbility = None,
      playerIsDefending = false
    )

    if !damagedEnemy.isAlive then
      victory(state.copy(player = playerAfterResource, combat = updatedCombat), damagedEnemy, log)
    else enemyTurn(state.copy(player = playerAfterResource, combat = updatedCombat), log)

  private def handleDefend(state: CombatState): (GameState, List[String], List[GameEvent]) =
    val log = List("You brace for impact. Incoming damage is halved this round.")
    // Archer: +8 Focus when defending
    val playerAfterResource = gainResource(state.player, onDefend = true)
    val updatedCombat       = state.combat.copy(playerIsDefending = true)
    enemyTurn(state.copy(player = playerAfterResource, combat = updatedCombat), log)

  private def handleItem(state: CombatState,
                         itemId: Option[String]
  ): (GameState, List[String], List[GameEvent]) =
    itemId match {
      case None =>
        (state, List("No item selected."), Nil)
      case Some(id) =>
        state.player.inventory.findById(id) match {
          case None =>
            (state, List("Item not found in inventory."), Nil)
          case Some((idx, consumable: Consumable)) =>
            val (_, newInventory)          = state.player.inventory.removeAt(idx)
            val playerWithoutItem          = state.player.copy(inventory = newInventory)
            val (updatedPlayer, effectLog) = applyConsumableEffect(playerWithoutItem, consumable)
            val updatedCombat              = state.combat.copy(playerIsDefending = false)
            enemyTurn(state.copy(player = updatedPlayer, combat = updatedCombat), effectLog)
          case Some((_, item)) =>
            (state, List(s"${item.name} cannot be used in combat."), Nil)
        }
    }

  /** Look up the player's class ability and apply it. Insufficient resource returns the state
    * unchanged; a class with no ability defined (should not happen with a valid abilities.json)
    * also returns the state unchanged.
    */
  private def handleAbility(state: CombatState): (GameState, List[String], List[GameEvent]) =
    abilityDefs.get(state.player.classId) match {
      case None =>
        (state, List("No ability available for this class."), Nil)

      case Some(ability) if state.player.resourceCurrent < ability.cost =>
        (state,
         List(
           s"Not enough ${ability.resourceName} — ${ability.name} requires ${ability.cost} ${ability.resourceName}."
         ),
         Nil
        )

      case Some(ability) =>
        val updatedPlayer = state.player.copy(
          resourceCurrent = state.player.resourceCurrent - ability.cost
        )
        applyAbilityEffect(state.copy(player = updatedPlayer), ability)
    }

  /** Apply an ability's effect once its cost has already been deducted. */
  private def applyAbilityEffect(state: CombatState,
                                 ability: AbilityDef
  ): (GameState, List[String], List[GameEvent]) =
    ability.effect match {
      case AbilityEffect.DoubleNextAttack =>
        val updatedCombat = state.combat.copy(
          pendingAbility = Some(PendingAbilityEffect.DoubleNextAttack),
          playerIsDefending = false
        )
        val log = List(s"${ability.name}! Your next attack will deal double damage.")
        enemyTurn(state.copy(combat = updatedCombat), log)

      case AbilityEffect.IgnoreDefenseNextAttack =>
        val updatedCombat = state.combat.copy(
          pendingAbility = Some(PendingAbilityEffect.IgnoreDefenseNextAttack),
          playerIsDefending = false
        )
        val log = List(s"${ability.name}! Your next attack will bypass enemy defense.")
        enemyTurn(state.copy(combat = updatedCombat), log)

      case AbilityEffect.FlatDamage(amount) =>
        // Flat damage: no calcDamage call, no jitter, no defense subtraction
        val damagedEnemy  = state.combat.enemy.takeDamage(amount)
        val log           = List(s"${ability.name}! You unleash arcane energy for $amount damage.")
        val updatedCombat = state.combat.copy(enemy = damagedEnemy, playerIsDefending = false)

        if !damagedEnemy.isAlive then
          victory(state.copy(combat = updatedCombat), damagedEnemy, log)
        else enemyTurn(state.copy(combat = updatedCombat), log)
    }

  // -----------------------------------------------------------------------
  // Resource generation
  // -----------------------------------------------------------------------

  /** Apply resource generation events to the player, capped at resourceMax.
    *
    * Each flag represents a distinct combat event; multiple flags can be combined in a single call
    * (e.g. onHit + onRound at the end of an enemy attack turn).
    *
    * Class rules:
    *   - Warrior: +5 on attack, +10 on hit — no per-round or defend gain
    *   - Archer: +8 on defend, +5 per round — no attack or hit gain
    *   - Mage: 0 in all cases
    */
  private[game] def gainResource(player: Player,
                                 onAttack: Boolean = false,
                                 onDefend: Boolean = false,
                                 onHit: Boolean = false,
                                 onRound: Boolean = false
  ): Player =
    val gain = player.classId match {
      case ClassId.Warrior =>
        (if onAttack then 5 else 0) + (if onHit then 10 else 0)
      case ClassId.Archer =>
        (if onDefend then 8 else 0) + (if onRound then 5 else 0)
      case ClassId.Mage => 0
    }
    if gain == 0 then player
    else player.copy(resourceCurrent = (player.resourceCurrent + gain).min(player.resourceMax))

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

  private def enemyTurn(state: CombatState,
                        priorLog: List[String]
  ): (GameState, List[String], List[GameEvent]) =
    val enemyAction = pickEnemyAction(state.combat.enemy)

    enemyAction match {
      case "ATTACK" =>
        val rawDamage = calcDamage(state.combat.enemy.attack, state.player.defense)
        val finalDamage =
          if state.combat.playerIsDefending then (rawDamage / 2).max(1) else rawDamage
        val newHp      = (state.player.hp - finalDamage).max(0)
        val defendNote = if state.combat.playerIsDefending then " (halved)" else ""
        val attackLog =
          priorLog :+ s"${state.combat.enemy.label} attacks you for $finalDamage damage$defendNote."

        if newHp <= 0 then defeat(state, state.player.copy(hp = 0), attackLog)
        else
          // Warrior +10 Rage when hit; Archer +5 Focus per round — both fire here
          val playerAfterHit = gainResource(
            state.player.copy(hp = newHp),
            onHit = true,
            onRound = true
          )
          val nextCombat = state.combat.copy(
            isPlayerTurn = true,
            round = state.combat.round + 1,
            playerIsDefending = false,
            tookDamage = true
          )
          (state.copy(player = playerAfterHit, combat = nextCombat), attackLog, Nil)

      case "DEFEND" =>
        val roundLog = priorLog :+ s"${state.combat.enemy.label} takes a defensive stance."
        // Archer +5 Focus per round even when the enemy defends
        val playerAfterRound = gainResource(state.player, onRound = true)
        val nextCombat = state.combat.copy(
          isPlayerTurn = true,
          round = state.combat.round + 1,
          playerIsDefending = false
        )
        (state.copy(player = playerAfterRound, combat = nextCombat), roundLog, Nil)

      case other =>
        // Unknow action -> skip enemy turn
        val roundLog         = priorLog :+ s"${state.combat.enemy.label} hesitates."
        val playerAfterRound = gainResource(state.player, onRound = true)
        val nextCombat       = state.combat.copy(isPlayerTurn = true, playerIsDefending = false)
        (state.copy(player = playerAfterRound, combat = nextCombat), roundLog, Nil)
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

  /** Player wins: remove enemy from room, award XP & Coins, roll for a loot drop, return to exploration. */
  private def victory(state: CombatState,
                      deadEnemy: EnemyInstance,
                      log: List[String]
  ): (GameState, List[String], List[GameEvent]) = {
    val xpGained = deadEnemy.xpReward
    // Stone Shards: 1 per 5 XP, minimum 1 per kill
    val shardsEarned = (xpGained / 5).max(1)
    val playerWithXp = state.player.copy(xp = state.player.xp + xpGained,
                                         metaCurrency = state.player.metaCurrency + shardsEarned
    )

    // Remove the defeated enemy
    val updatedRoom = state.dungeon.currentRoom.removeEntity(deadEnemy.entityId)
    val updatedDungeon = state.dungeon.copy(
      rooms = state.dungeon.rooms.updated(updatedRoom.id, updatedRoom)
    )

    val victoryLog = log ++ List(
      s"${deadEnemy.label} has been defeated!",
      s"You gain $xpGained XP and $shardsEarned Shard${if shardsEarned != 1 then "s" else ""}."
    )

    val (playerAfterLoot, lootLog, lootEvents) =
      LootTable.rollEnemy(deadEnemy, itemDefs, rng, state.difficulty) match {
        case None => (playerWithXp, Nil, Nil)
        case Some(item) =>
          playerWithXp.withItemPickup(item) match {
            case Right(p) =>
              (p,
               List(s"${deadEnemy.label} dropped ${item.name}! (${item.statLine})"),
               List(GameEvent.ItemPickedUp(inventoryFull = p.inventory.isFull))
              )
            case Left(_) =>
              (playerWithXp,
               List(s"${deadEnemy.label} dropped ${item.name}, but your inventory is full!"),
               Nil
              )
          }
      }

    // Level-up after loot
    val startLevel                = playerAfterLoot.level
    val (finalPlayer, levelUpLog) = LevelUpSystem.applyLevelUps(playerAfterLoot, rng)
    val levelUpEvents = (startLevel + 1 to finalPlayer.level).map(GameEvent.LeveledUp.apply).toList

    val enemyDefeatedEvent =
      GameEvent.EnemyDefeated(isBoss = updatedDungeon.isAtBoss, tookNoDamage = !state.combat.tookDamage)

    if updatedDungeon.isAtBoss then
      val runCompleteLog = List("You have vanquished the dungeon's guardian! Victory is yours.")
      val events =
        enemyDefeatedEvent :: lootEvents ::: levelUpEvents ::: List(GameEvent.RunEnded(victory = true))
      (GameOverState(finalPlayer, victory = true),
       victoryLog ++ lootLog ++ levelUpLog ++ runCompleteLog,
       events
      )
    else
      val nextState = ExplorationState(
        player = finalPlayer,
        dungeon = updatedDungeon,
        playerX = state.playerX,
        playerY = state.playerY,
        difficulty = state.difficulty
      )
      val events = enemyDefeatedEvent :: lootEvents ::: levelUpEvents
      (nextState, victoryLog ++ lootLog ++ levelUpLog, events)
  }

  /** Player loses: transition to GameOver, preserve meta-currency. */
  private def defeat(state: CombatState,
                     deadPlayer: Player,
                     log: List[String]
  ): (GameState, List[String], List[GameEvent]) =
    val defeatLog = log :+ "You have been defeated. The dungeon claims another soul."
    (GameOverState(deadPlayer), defeatLog, List(GameEvent.RunEnded(victory = false)))

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
    /** Effective attack: level scaling + max-HP factor + permanent bonus + affinity-aware weapon
      * sum.
      *
      * Weapons whose typeTag is in the player's affinityTags contribute double their attackBonus.
      * Example: Hunter's Bow (+5 ATK, "ranged") held by an Archer (affinity: "ranged") → +10 ATK.
      */
    private def attack: Int =
      val weaponBonus = player.inventory.slots.collect {
        case Some(w: Weapon) =>
          val multiplier = if w.typeTag.exists(player.affinityTags.contains) then 2 else 1
          w.attackBonus * multiplier
      }.sum
      player.level * 5 + (player.maxHp / 10) + player.bonusAttack + weaponBonus

    /** Effective defense: level scaling + permanent bonus + affinity-aware armor sum.
      *
      * Armors whose typeTag is in the player's affinityTags contribute double their defenseBonus.
      * Example: Chain Mail (+6 DEF, "heavy") held by a Warrior (affinity: "heavy") → +12 DEF.
      */
    private def defense: Int =
      val armorBonus = player.inventory.slots.collect {
        case Some(a: Armor) =>
          val multiplier = if a.typeTag.exists(player.affinityTags.contains) then 2 else 1
          a.defenseBonus * multiplier
      }.sum
      player.level * 2 + player.bonusDefense + armorBonus
