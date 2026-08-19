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
  * name, and effect) comes from [[abilityDefs]] (see [[AbilityDef]]), so adding or tuning a class
  * ability never requires touching this class.
  *
  * @param rng
  *   Random instance: inject a seeded one for deterministic tests.
  * @param abilityDefs
  *   Loaded ability catalog, keyed by class. See [[AbilityLoader]].
  * @param setDefs
  *   Loaded equipment set catalog, keyed by setId. See [[SetLoader]].
  */
class CombatResolver(rng: Random = Random(),
                     itemDefs: Map[String, Item] = Map.empty,
                     abilityDefs: Map[ClassId, AbilityDef] = Map.empty,
                     setDefs: Map[String, SetDef] = Map.empty
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

    // Ability activation banner: the pending effect was armed by this player's own class
    // ability, so the ability name is always resolvable from their classId.
    val abilityLog: List[String] = pending match {
      case Some(_) => abilityDefs.get(state.player.classId).map(a => s"${a.name} activates!").toList
      case None    => Nil
    }

    // Damage modified by pending ability effect
    val baseDamage = pending match {
      case Some(PendingAbilityEffect.DoubleNextAttack) =>
        // Double the full post-jitter value
        calcDamage(state.player.attack, state.combat.enemy.defense) * 2
      case Some(PendingAbilityEffect.IgnoreDefenseNextAttack) =>
        // Roll against 0 defense
        calcDamage(state.player.attack, 0)
      case None =>
        calcDamage(state.player.attack, state.combat.enemy.defense)
    }

    // Crit chance is an Attack-specific weapon-strike stat: it never applies to Ability's
    // FlatDamage (Arcane Blast), which bypasses calcDamage entirely. FirstAttackAlwaysCrit (Silent
    // Archer 4pc) overrides the roll on the player's first Attack of the fight, win or lose the roll.
    val firstAttackGuaranteedCrit =
      !state.combat.hasAttackedThisCombat &&
        activeSetBonuses(state.player).contains(SetBonusEffect.FirstAttackAlwaysCrit)
    val isCrit = firstAttackGuaranteedCrit || rollCrit(state.player.critChance)

    // Set-driven "+N% attack damage" is a final multiplier on top of the base hit, applied
    // alongside (not instead of) the crit multiplier - the two stack multiplicatively.
    val setDamagePercent = sumSetBonus(state.player, { case SetBonusEffect.AttackDamagePercent(n) => n })
    val critMultiplier    = if isCrit then CritMultiplier else 1.0
    val damage = math.round(baseDamage * (1.0 + setDamagePercent / 100.0) * critMultiplier).toInt

    val damagedEnemy = state.combat.enemy.takeDamage(damage)
    val critSuffix   = if isCrit then " Critical hit!" else ""
    val strikeLog    = List(s"You strike the ${damagedEnemy.label} for $damage damage.$critSuffix")
    val log          = abilityLog ++ strikeLog

    // Warrior: +5 Rage on every action
    val playerAfterResource = gainResource(state.player, onAttack = true)

    // Consume the pending effect
    val updatedCombat = state.combat.copy(
      enemy = damagedEnemy,
      pendingAbility = None,
      playerIsDefending = false,
      hasAttackedThisCombat = true
    )

    val damageEvent = GameEvent.DamageDealt(targetIsPlayer = false, amount = damage, crit = isCrit)
    val (finalState, finalLog, downstreamEvents) =
      if !damagedEnemy.isAlive then
        victory(state.copy(player = playerAfterResource, combat = updatedCombat), damagedEnemy, log, damage)
      else enemyTurn(state.copy(player = playerAfterResource, combat = updatedCombat), log)
    (finalState, finalLog, damageEvent :: downstreamEvents)

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
        state.player.potionBelt.zipWithIndex.collectFirst {
          case (Some(c), idx) if c.id == id => (idx, c)
        } match {
          case None =>
            (state, List("Item not found in inventory."), Nil)
          case Some((idx, consumable)) =>
            val playerWithoutItem = state.player.copy(potionBelt = state.player.potionBelt.updated(idx, None))
            val (updatedPlayer, effectLog, healEvents) =
              applyConsumableEffect(playerWithoutItem, consumable)
            val updatedCombat = state.combat.copy(playerIsDefending = false)
            val (finalState, finalLog, downstreamEvents) =
              enemyTurn(state.copy(player = updatedPlayer, combat = updatedCombat), effectLog)
            (finalState, finalLog, healEvents ++ downstreamEvents)
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

      case Some(ability) =>
        val cost = effectiveAbilityCost(state.player, ability)
        if state.player.resourceCurrent < cost then
          (state,
           List(s"Not enough ${ability.resourceName} — ${ability.name} requires $cost ${ability.resourceName}."),
           Nil
          )
        else
          val updatedPlayer = state.player.copy(resourceCurrent = state.player.resourceCurrent - cost)
          applyAbilityEffect(state.copy(player = updatedPlayer), ability)
    }

  /** `ability.cost` reduced by any active set AbilityCostReductionPercent bonus (Pyromancer 4pc),
    * floored at 0.
    */
  private def effectiveAbilityCost(player: Player, ability: AbilityDef): Int =
    val reduction = sumSetBonus(player, { case SetBonusEffect.AbilityCostReductionPercent(n) => n })
    math.round(ability.cost * (100 - reduction).max(0) / 100.0).toInt

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
        val damageEvent   = GameEvent.DamageDealt(targetIsPlayer = false, amount = amount)

        val (finalState, finalLog, downstreamEvents) =
          if !damagedEnemy.isAlive then victory(state.copy(combat = updatedCombat), damagedEnemy, log, amount)
          else enemyTurn(state.copy(combat = updatedCombat), log)
        (finalState, finalLog, damageEvent :: downstreamEvents)
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
    *   - Warrior: +5 on attack, +10 on hit, no per-round or defend gain
    *   - Archer: +8 on defend, +5 per round, no attack or hit gain
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

  private def applyConsumableEffect(player: Player,
                                    item: Consumable
  ): (Player, List[String], List[GameEvent]) =
    item.effect match
      case ConsumableEffect.HealFixed(amount) =>
        val before = player.hp
        val after  = (player.hp + amount).min(player.maxHp)
        val healed = after - before
        (player.copy(hp = after),
         List(s"You use ${item.name}. Restored $healed HP."),
         if healed > 0 then List(GameEvent.Healed(healed)) else Nil
        )

      case ConsumableEffect.HealPercent(pct) =>
        val amount = (player.maxHp * pct / 100).max(1)
        val before = player.hp
        val after  = (player.hp + amount).min(player.maxHp)
        val healed = after - before
        (player.copy(hp = after),
         List(s"You use ${item.name}. Restored $healed HP."),
         if healed > 0 then List(GameEvent.Healed(healed)) else Nil
        )

      case ConsumableEffect.RestoreResource(amount) =>
        val before   = player.resourceCurrent
        val after    = (player.resourceCurrent + amount).min(player.resourceMax)
        val restored = after - before
        (player.copy(resourceCurrent = after),
         List(s"You use ${item.name}. Restored $restored resource."),
         Nil
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
        val damageEvent = GameEvent.DamageDealt(targetIsPlayer = true, amount = finalDamage)

        if newHp <= 0 then
          val (finalState, finalLog, downstreamEvents) = defeat(state, state.player.copy(hp = 0), attackLog)
          (finalState, finalLog, damageEvent :: downstreamEvents)
        else
          // Warrior +10 Rage when hit; Archer +5 Focus per round: both fire here
          val playerAfterHit = gainResource(
            state.player.copy(hp = newHp),
            onHit = true,
            onRound = true
          )
          val (survivingBuffs, buffExpiryLog) = advanceBuffs(state.combat)
          val nextCombat = state.combat.copy(
            isPlayerTurn = true,
            round = state.combat.round + 1,
            playerIsDefending = false,
            tookDamage = true,
            activeBuffs = survivingBuffs
          )
          (state.copy(player = playerAfterHit, combat = nextCombat), attackLog ++ buffExpiryLog, List(damageEvent))

      case "DEFEND" =>
        val roundLog = priorLog :+ s"${state.combat.enemy.label} takes a defensive stance."
        // Archer +5 Focus per round even when the enemy defends
        val playerAfterRound = gainResource(state.player, onRound = true)
        val (survivingBuffs, buffExpiryLog) = advanceBuffs(state.combat)
        val nextCombat = state.combat.copy(
          isPlayerTurn = true,
          round = state.combat.round + 1,
          playerIsDefending = false,
          activeBuffs = survivingBuffs
        )
        (state.copy(player = playerAfterRound, combat = nextCombat), roundLog ++ buffExpiryLog, Nil)

      case other =>
        // Unknow action -> skip enemy turn
        val roundLog                        = priorLog :+ s"${state.combat.enemy.label} hesitates."
        val playerAfterRound                = gainResource(state.player, onRound = true)
        val (survivingBuffs, buffExpiryLog) = advanceBuffs(state.combat)
        val nextCombat = state.combat.copy(isPlayerTurn = true,
                                           playerIsDefending = false,
                                           activeBuffs = survivingBuffs
        )
        (state.copy(player = playerAfterRound, combat = nextCombat), roundLog ++ buffExpiryLog, Nil)
    }

  /** Decrement every active buff's remaining duration by one round. Returns the surviving buffs
    * plus a log line for each one that just expired.
    */
  private def advanceBuffs(combat: Combat): (List[TimedBuff], List[String]) =
    val decremented                = combat.activeBuffs.map(b => b.copy(turnsRemaining = b.turnsRemaining - 1))
    val (expired, surviving)       = decremented.partition(_.turnsRemaining <= 0)
    (surviving, expired.map(b => describeBuffExpiry(b.effect)))

  private def describeBuffExpiry(effect: TimedBuffEffect): String = effect match
    case TimedBuffEffect.AttackBonusPercent(_) => "Your battle fury fades."

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

  /** Player wins: remove enemy from room, award XP & Coins, apply heal-on-kill set bonuses if any,
    * roll for a loot drop, return to exploration.
    *
    * @param killingBlowDamage
    *   Raw damage dealt by the hit that killed `deadEnemy`, feeding necromancer's
    *   HealOnKillPercent 4pc bonus. Passed in rather than re-derived since both call sites
    *   (Attack, Arcane Blast's FlatDamage) already have it in scope.
    */
  private def victory(state: CombatState,
                      deadEnemy: EnemyInstance,
                      log: List[String],
                      killingBlowDamage: Int
  ): (GameState, List[String], List[GameEvent]) = {
    val xpGained = deadEnemy.xpReward
    // Stone Shards: 1 per 5 XP, minimum 1 per kill
    val shardsEarned = (xpGained / 5).max(1)
    val playerWithXp = state.player.copy(xp = state.player.xp + xpGained,
                                         metaCurrency = state.player.metaCurrency + shardsEarned
    )

    // Necromancer 4pc: heal a percentage of the killing blow's damage as HP.
    val healOnKillPercent = sumSetBonus(playerWithXp, { case SetBonusEffect.HealOnKillPercent(n) => n })
    val healAmount         = (killingBlowDamage * healOnKillPercent / 100).max(0)
    val healedHp            = (playerWithXp.hp + healAmount).min(playerWithXp.maxHp) - playerWithXp.hp
    val playerAfterHeal      = playerWithXp.copy(hp = playerWithXp.hp + healedHp)
    val healLog   = if healedHp > 0 then List(s"The kill heals you for $healedHp HP.") else Nil
    val healEvent = if healedHp > 0 then List(GameEvent.Healed(healedHp)) else Nil

    // Remove the defeated enemy
    val updatedRoom = state.dungeon.currentRoom.removeEntity(deadEnemy.entityId)
    val updatedDungeon = state.dungeon.copy(
      rooms = state.dungeon.rooms.updated(updatedRoom.id, updatedRoom)
    )

    val victoryLog = log ++ List(
      s"${deadEnemy.label} has been defeated!",
      s"You gain $xpGained XP and $shardsEarned Shard${if shardsEarned != 1 then "s" else ""}."
    ) ++ healLog

    // Known before resolving loot: a boss kill ends the run into GameOverState, which has no
    // room to hold a pending equip choice - see the ChoicePending branch below.
    val isBossKill = updatedDungeon.isAtBoss

    val (playerAfterLoot, lootLog, lootEvents, pendingChoice) =
      LootTable.rollEnemy(deadEnemy, itemDefs, rng, state.difficulty) match {
        case None => (playerAfterHeal, Nil, Nil, None)
        case Some(item) =>
          EquipmentResolver.resolvePickup(playerAfterHeal, item, setDefs) match {
            case PickupOutcome.Equipped(p) =>
              (p,
               List(s"${deadEnemy.label} dropped ${item.name}! (${item.statLine})"),
               List(GameEvent.ItemPickedUp(inventoryFull = p.isFullyEquipped)),
               None
              )
            case PickupOutcome.KeyCollected(p) =>
              (p,
               List(s"${deadEnemy.label} dropped ${item.name}! (${item.statLine})"),
               List(GameEvent.ItemPickedUp(inventoryFull = p.isFullyEquipped)),
               None
              )
            case PickupOutcome.ChoicePending(pending) if isBossKill =>
              // No ExplorationState survives this kill to hold a pending choice - lost, not offered.
              (playerAfterHeal,
               List(s"${deadEnemy.label} dropped ${item.name}, but there's no time to decide - it's lost."),
               Nil,
               None
              )
            case PickupOutcome.ChoicePending(pending) =>
              (playerAfterHeal,
               List(s"${deadEnemy.label} dropped ${item.name}. Choose what to do with it."),
               Nil,
               Some(pending)
              )
          }
      }

    // Level-up after loot
    val startLevel                = playerAfterLoot.level
    val (finalPlayer, levelUpLog) = LevelUpSystem.applyLevelUps(playerAfterLoot, rng)
    val levelUpEvents = (startLevel + 1 to finalPlayer.level).map(GameEvent.LeveledUp.apply).toList

    val enemyDefeatedEvent =
      GameEvent.EnemyDefeated(isBoss = isBossKill, tookNoDamage = !state.combat.tookDamage)

    if isBossKill then
      val runCompleteLog = List("You have vanquished the dungeon's guardian! Victory is yours.")
      val events =
        enemyDefeatedEvent :: healEvent ::: lootEvents ::: levelUpEvents :::
          List(GameEvent.RunEnded(victory = true))
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
        difficulty = state.difficulty,
        enemyStats = state.enemyStats,
        pendingEquipChoice = pendingChoice
      )
      val events = enemyDefeatedEvent :: healEvent ::: lootEvents ::: levelUpEvents
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

  /** Damage multiplier applied on a critical hit. */
  private[game] val CritMultiplier = 1.5

  /** Roll for a critical hit against a percent chance (0-100). Never crits at 0 or below. */
  private[game] def rollCrit(chancePercent: Int): Boolean =
    chancePercent > 0 && rng.nextInt(100) < chancePercent

  // ---------------------------------------------
  // Player stat accessors (include equipped-item bonuses)
  // ---------------------------------------------

  /** Sum one bonus field across both equipped accessory slots, applying the same affinity-doubling
    * rule as weapon/armor bonuses to each accessory independently (its own typeTag against the
    * player's affinityTags, not the accessory kind as a whole).
    */
  private def accessoryBonusSum(player: Player, extract: Accessory => Option[Int]): Int =
    player.equippedAccessories.flatten.map: acc =>
      val base = extract(acc).getOrElse(0)
      base * (if acc.typeTag.exists(player.affinityTags.contains) then 2 else 1)
    .sum

  /** Every set bonus effect currently active for `player` (see [[SetDef.activeBonuses]]). */
  private[game] def activeSetBonuses(player: Player): List[SetBonusEffect] =
    SetDef.activeBonuses(player.equippedSetIds, setDefs, player.classId)

  private def sumSetBonus(player: Player, extract: PartialFunction[SetBonusEffect, Int]): Int =
    activeSetBonuses(player).collect(extract).sum

  /** Will be tuned later. */
  extension (player: Player)
    /** Effective attack: level scaling + max-HP factor + permanent bonus + affinity-aware weapon
      * and accessory bonuses + any active set FlatAttack bonus.
      *
      * The equipped weapon's bonus is doubled if its typeTag is in the player's affinityTags.
      * Example: Hunter's Bow (+5 ATK, "ranged") held by an Archer (affinity: "ranged") → +10 ATK.
      * Equipped accessories contribute the same way if they carry an attackBonus.
      */
    private def attack: Int =
      val weaponBonus = player.equippedWeapon match {
        case Some(w) => w.attackBonus * (if w.typeTag.exists(player.affinityTags.contains) then 2 else 1)
        case None    => 0
      }
      val accessoryBonus = accessoryBonusSum(player, _.attackBonus)
      val setBonus        = sumSetBonus(player, { case SetBonusEffect.FlatAttack(n) => n })
      player.level * 5 + (player.maxHp / 10) + player.bonusAttack + weaponBonus + accessoryBonus + setBonus

    /** Effective defense: level scaling + permanent bonus + affinity-aware armor and accessory
      * bonuses + any active set FlatDefense bonus.
      *
      * The equipped armor's bonus is doubled if its typeTag is in the player's affinityTags.
      * Example: Chain Mail (+6 DEF, "heavy") held by a Warrior (affinity: "heavy") → +12 DEF.
      * Equipped accessories contribute the same way if they carry a defenseBonus.
      */
    private def defense: Int =
      val armorBonus = player.equippedArmor match {
        case Some(a) => a.defenseBonus * (if a.typeTag.exists(player.affinityTags.contains) then 2 else 1)
        case None    => 0
      }
      val accessoryBonus = accessoryBonusSum(player, _.defenseBonus)
      val setBonus        = sumSetBonus(player, { case SetBonusEffect.FlatDefense(n) => n })
      player.level * 2 + player.bonusDefense + armorBonus + accessoryBonus + setBonus

    /** Percent chance (0-100) of a critical hit on the player's next Attack action, summed across
      * both equipped accessories' critChanceBonus (same affinity-doubling rule as attack/defense)
      * plus any active set CritChancePercent bonus. Weapon/armor never contribute directly - crit
      * chance is otherwise accessory-only in the current data.
      */
    private[game] def critChance: Int =
      accessoryBonusSum(player, _.critChanceBonus) +
        sumSetBonus(player, { case SetBonusEffect.CritChancePercent(n) => n })
