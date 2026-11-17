package roguelite.game.combat

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
import roguelite.game.core.{ GameEvent, LogLine }
import roguelite.game.dungeon.{ Chest, RoomType, Shrine }
import roguelite.game.items.{ Accessory, Consumable, ConsumableEffect, EquipmentResolver, Item, LootTable, PickupOutcome, Rarity, SetBonusEffect, SetDef }
import roguelite.game.progression.{ PerkDef, PerkEffect }

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
  * @param perkDefs
  *   Loaded run-perk catalog, keyed by id. See [[PerkLoader]]. Resolves
  *   [[roguelite.engine.Player.activePerkId]] back to its [[PerkEffect]] wherever a perk needs to
  *   be checked live (the equivalent of `setDefs` for perks).
  */
class CombatResolver(rng: Random = Random(),
                     itemDefs: Map[String, Item] = Map.empty,
                     abilityDefs: Map[ClassId, AbilityDef] = Map.empty,
                     setDefs: Map[String, SetDef] = Map.empty,
                     perkDefs: Map[String, PerkDef] = Map.empty
):

  /** The attack, defense and crit math, shared with what the client shows. */
  private val stats = StatCalculator(setDefs, perkDefs)

  /** Entry point called by the StateMachine for every CombatAction. */
  def resolve(state: CombatState, action: CombatAction): (GameState, List[LogLine], List[GameEvent]) =
    action.action match {
      case CombatActionType.Attack  => handleAttack(state)
      case CombatActionType.Defend  => handleDefend(state)
      case CombatActionType.Item    => handleItem(state, action.itemId)
      case CombatActionType.Ability => handleAbility(state)
    }

  // ---------------------------------------------
  // Player actions
  // ---------------------------------------------

  private def handleAttack(state: CombatState): (GameState, List[LogLine], List[GameEvent]) =
    val pending = state.combat.pendingAbility

    // Ability activation banner: the pending effect was armed by this player's own class
    // ability, so the ability name is always resolvable from their classId.
    val abilityLog: List[LogLine] = pending match {
      case Some(_) => abilityDefs.get(state.player.classId).map(a => LogLine.of("log.combat.abilityActivates", "ability" -> a.id)).toList
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
      !state.combat.hasAttackedThisCombat && stats.firstAttackAlwaysCrits(state.player)
    val buffCritBonus = state.combat.activeBuffs.collect {
      case TimedBuff(TimedBuffEffect.CritChanceBonusPercent(n), _) => n
    }.sum
    val isCrit = firstAttackGuaranteedCrit || rollCrit(state.player.critChance + buffCritBonus)

    // Set-driven "+N% attack damage" and any active potion AttackBuff are both a final percentage
    // added on top of the base hit (summed together, not each its own multiplier), then the crit
    // multiplier applies on top of that combined total.
    val setDamagePercent = stats.setDamagePercent(state.player)
    val buffDamagePercent = state.combat.activeBuffs.collect {
      case TimedBuff(TimedBuffEffect.AttackBonusPercent(n), _) => n
    }.sum
    val critMultiplier = if isCrit then CritMultiplier else 1.0
    val damage =
      math.round(baseDamage * (1.0 + (setDamagePercent + buffDamagePercent) / 100.0) * critMultiplier).toInt

    val damagedEnemy = state.combat.enemy.takeDamage(damage)
    val strikeKey    = if isCrit then "log.combat.strikeCrit" else "log.combat.strike"
    val strikeLog    = List(LogLine.of(strikeKey, "enemy" -> damagedEnemy.typeId, "damage" -> damage))
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

  private def handleDefend(state: CombatState): (GameState, List[LogLine], List[GameEvent]) =
    val log = List(LogLine.of("log.combat.defend"))
    // Archer: +8 Focus when defending
    val playerAfterResource = gainResource(state.player, onDefend = true)
    val updatedCombat       = state.combat.copy(playerIsDefending = true)
    enemyTurn(state.copy(player = playerAfterResource, combat = updatedCombat), log)

  private def handleItem(state: CombatState,
                         itemId: Option[String]
  ): (GameState, List[LogLine], List[GameEvent]) =
    itemId match {
      case None =>
        (state, List(LogLine.of("log.combat.noItemSelected")), Nil)
      case Some(id) =>
        state.player.potionBelt.zipWithIndex.collectFirst {
          case (Some(stack), idx) if stack.item.id == id => (idx, stack)
        } match {
          case None =>
            (state, List(LogLine.of("log.combat.itemNotFound")), Nil)
          case Some((idx, stack)) =>
            // Drink one charge: decrement the stack, clearing the slot only once it's empty.
            val remaining = stack.count - 1
            val newSlot   = if remaining > 0 then Some(stack.copy(count = remaining)) else None
            val stateWithoutItem = state.copy(
              player = state.player.copy(potionBelt = state.player.potionBelt.updated(idx, newSlot))
            )
            val (updatedState, effectLog, effectEventsRaw, killingBlowDamage) =
              applyConsumableEffect(stateWithoutItem, stack.item)
            val effectEvents = GameEvent.ConsumableUsed(stack.item.typeId) :: effectEventsRaw

            killingBlowDamage match
              case Some(damage) =>
                // FlatDamage killed the enemy: route to victory like Attack/Arcane Blast do.
                val (finalState, finalLog, downstreamEvents) =
                  victory(updatedState, updatedState.combat.enemy, effectLog, damage)
                (finalState, finalLog, effectEvents ++ downstreamEvents)
              case None =>
                val updatedCombat = updatedState.combat.copy(playerIsDefending = false)
                val (finalState, finalLog, downstreamEvents) =
                  enemyTurn(updatedState.copy(combat = updatedCombat), effectLog)
                (finalState, finalLog, effectEvents ++ downstreamEvents)
        }
    }

  /** Look up the player's class ability and apply it. Insufficient resource returns the state
    * unchanged; a class with no ability defined (should not happen with a valid abilities.json)
    * also returns the state unchanged.
    */
  private def handleAbility(state: CombatState): (GameState, List[LogLine], List[GameEvent]) =
    abilityDefs.get(state.player.classId) match {
      case None =>
        (state, List(LogLine.of("log.combat.noAbility")), Nil)

      case Some(ability) =>
        val cost = effectiveAbilityCost(state.player, ability)
        if state.player.resourceCurrent < cost then
          (state,
           List(LogLine.of("log.combat.notEnoughResource", "resource" -> ability.id, "ability" -> ability.id, "cost" -> cost)),
           Nil
          )
        else
          val updatedPlayer = state.player.copy(resourceCurrent = state.player.resourceCurrent - cost)
          applyAbilityEffect(state.copy(player = updatedPlayer), ability)
    }

  /** See [[AbilityDef.effectiveCost]]. */
  private def effectiveAbilityCost(player: Player, ability: AbilityDef): Int =
    AbilityDef.effectiveCost(player, ability, setDefs, perkDefs)

  /** Apply an ability's effect once its cost has already been deducted. */
  private def applyAbilityEffect(state: CombatState,
                                 ability: AbilityDef
  ): (GameState, List[LogLine], List[GameEvent]) =
    ability.effect match {
      case AbilityEffect.DoubleNextAttack =>
        val updatedCombat = state.combat.copy(
          pendingAbility = Some(PendingAbilityEffect.DoubleNextAttack),
          playerIsDefending = false
        )
        val log = List(LogLine.of("log.combat.abilityDouble", "ability" -> ability.id))
        enemyTurn(state.copy(combat = updatedCombat), log)

      case AbilityEffect.IgnoreDefenseNextAttack =>
        val updatedCombat = state.combat.copy(
          pendingAbility = Some(PendingAbilityEffect.IgnoreDefenseNextAttack),
          playerIsDefending = false
        )
        val log = List(LogLine.of("log.combat.abilityPierce", "ability" -> ability.id))
        enemyTurn(state.copy(combat = updatedCombat), log)

      case AbilityEffect.FlatDamage(amount) =>
        // Flat damage: no calcDamage call, no jitter, no defense subtraction
        val damagedEnemy  = state.combat.enemy.takeDamage(amount)
        val log           = List(LogLine.of("log.combat.abilityBlast", "ability" -> ability.id, "damage" -> amount))
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

  /** Apply a consumable's effect within an active combat.
    *
    * Returns the updated combat state, log lines, any GameEvents, and - only for an effect that
    * can kill the enemy (FlatDamage) - the killing blow's damage, so [[handleItem]] knows to
    * route to [[victory]] instead of continuing to [[enemyTurn]].
    */
  /** `amount` scaled up by any active PotionHealBonusPercent perk, rounded to the nearest int. */
  private def applyPotionHealBonus(player: Player, amount: Int): Int =
    activePerkEffects(player).collect { case PerkEffect.PotionHealBonusPercent(n) => n }.sum match {
      case 0   => amount
      // A negative percent (a risky perk) thins the heal, which never drops below 1.
      case pct => math.max(1, math.round(amount * (100 + pct).max(0) / 100.0).toInt)
    }

  private def applyConsumableEffect(state: CombatState,
                                    item: Consumable
  ): (CombatState, List[LogLine], List[GameEvent], Option[Int]) =
    item.effect match
      case ConsumableEffect.HealFixed(baseAmount) =>
        val amount = applyPotionHealBonus(state.player, baseAmount)
        val before = state.player.hp
        val after  = (state.player.hp + amount).min(state.player.maxHp)
        val healed = after - before
        (state.copy(player = state.player.copy(hp = after)),
         List(LogLine.of("log.combat.useHeal", "item" -> item.typeId, "amount" -> healed)),
         if healed > 0 then List(GameEvent.Healed(healed)) else Nil,
         None
        )

      case ConsumableEffect.HealPercent(pct) =>
        val baseAmount = (state.player.maxHp * pct / 100).max(1)
        val amount     = applyPotionHealBonus(state.player, baseAmount)
        val before = state.player.hp
        val after  = (state.player.hp + amount).min(state.player.maxHp)
        val healed = after - before
        (state.copy(player = state.player.copy(hp = after)),
         List(LogLine.of("log.combat.useHeal", "item" -> item.typeId, "amount" -> healed)),
         if healed > 0 then List(GameEvent.Healed(healed)) else Nil,
         None
        )

      case ConsumableEffect.RestoreResource(amount) =>
        val before   = state.player.resourceCurrent
        val after    = (state.player.resourceCurrent + amount).min(state.player.resourceMax)
        val restored = after - before
        (state.copy(player = state.player.copy(resourceCurrent = after)),
         List(LogLine.of("log.combat.useResource", "item" -> item.typeId, "amount" -> restored)),
         Nil,
         None
        )

      case ConsumableEffect.AttackBuff(percent, turns) =>
        val updatedCombat = armBuff(state.combat, TimedBuffEffect.AttackBonusPercent(percent), turns)
        (state.copy(combat = updatedCombat),
         List(LogLine.of("log.combat.useAttackBuff", "item" -> item.typeId, "percent" -> percent, "count" -> turns)),
         Nil,
         None
        )

      case ConsumableEffect.FlatDamage(amount) =>
        // Flat damage: no calcDamage call, no jitter, no crit, no defense subtraction - same
        // pattern as Arcane Blast.
        val damagedEnemy  = state.combat.enemy.takeDamage(amount)
        val updatedCombat = state.combat.copy(enemy = damagedEnemy)
        val log           = List(LogLine.of("log.combat.useFlatDamage", "item" -> item.typeId, "amount" -> amount, "enemy" -> damagedEnemy.typeId))
        val damageEvent   = GameEvent.DamageDealt(targetIsPlayer = false, amount = amount)
        (state.copy(combat = updatedCombat),
         log,
         List(damageEvent),
         if !damagedEnemy.isAlive then Some(amount) else None
        )

      case ConsumableEffect.CritBuff(percent, turns) =>
        val updatedCombat = armBuff(state.combat, TimedBuffEffect.CritChanceBonusPercent(percent), turns)
        (state.copy(combat = updatedCombat),
         List(LogLine.of("log.combat.useCritBuff", "item" -> item.typeId, "percent" -> percent, "count" -> turns)),
         Nil,
         None
        )

  /** Arm a timed buff on `combat`, replacing any existing buff of the same [[TimedBuffEffect]]
    * case rather than stacking alongside it (refresh duration, not magnitude - see TimedBuff's
    * docblock). Compares by `.ordinal` (same enum case, regardless of percent value) so a new
    * TimedBuffEffect case never needs a matching branch added here.
    */
  private def armBuff(combat: Combat, effect: TimedBuffEffect, turns: Int): Combat =
    val otherBuffs = combat.activeBuffs.filterNot(_.effect.ordinal == effect.ordinal)
    combat.copy(activeBuffs = TimedBuff(effect, turns) :: otherBuffs)

  // ---------------------------------------------
  // Enemy turn
  // ---------------------------------------------

  private def enemyTurn(state: CombatState,
                        priorLog: List[LogLine]
  ): (GameState, List[LogLine], List[GameEvent]) =
    val enemyAction = pickEnemyAction(state.combat.enemy)

    enemyAction match {
      case "ATTACK" =>
        val rawDamage = calcDamage(state.combat.enemy.attack, state.player.defense)
        val finalDamage =
          if state.combat.playerIsDefending then (rawDamage / 2).max(1) else rawDamage
        val newHp      = (state.player.hp - finalDamage).max(0)
        val attackKey  = if state.combat.playerIsDefending then "log.combat.enemyAttackHalved" else "log.combat.enemyAttack"
        val attackLog =
          priorLog :+ LogLine.of(attackKey, "enemy" -> state.combat.enemy.typeId, "damage" -> finalDamage)
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
        val roundLog = priorLog :+ LogLine.of("log.combat.enemyDefend", "enemy" -> state.combat.enemy.typeId)
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
        val roundLog                        = priorLog :+ LogLine.of("log.combat.enemyHesitates", "enemy" -> state.combat.enemy.typeId)
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
  private def advanceBuffs(combat: Combat): (List[TimedBuff], List[LogLine]) =
    val decremented                = combat.activeBuffs.map(b => b.copy(turnsRemaining = b.turnsRemaining - 1))
    val (expired, surviving)       = decremented.partition(_.turnsRemaining <= 0)
    (surviving, expired.map(b => describeBuffExpiry(b.effect)))

  private def describeBuffExpiry(effect: TimedBuffEffect): LogLine = effect match
    case TimedBuffEffect.AttackBonusPercent(_)     => LogLine.of("log.combat.attackBuffEnds")
    case TimedBuffEffect.CritChanceBonusPercent(_) => LogLine.of("log.combat.critBuffEnds")

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
                      log: List[LogLine],
                      killingBlowDamage: Int
  ): (GameState, List[LogLine], List[GameEvent]) = {
    val xpGained = deadEnemy.xpReward
    // Stone Shards: 1 per 5 XP, minimum 1 per kill, and none on a run that takes no blessing, nor once
    // the altar is shut: there is nothing left to buy them with.
    // A risky perk adds a percentage to what a kill earns.
    val shardsEarned =
      if state.difficulty.usesBlessings && !state.altarShut then
        PerkDef.shardsWithBonus((xpGained / 5).max(1), PerkDef.shardBonusPercent(state.player, perkDefs))
      else 0
    val playerWithXp = state.player.copy(xp = state.player.xp + xpGained,
                                         metaCurrency = state.player.metaCurrency + shardsEarned
    )

    // Necromancer 4pc: heal a percentage of the killing blow's damage as HP.
    val healOnKillPercent = sumSetBonus(playerWithXp, { case SetBonusEffect.HealOnKillPercent(n) => n })
    val healAmount         = (killingBlowDamage * healOnKillPercent / 100).max(0)
    val healedHp            = (playerWithXp.hp + healAmount).min(playerWithXp.maxHp) - playerWithXp.hp
    val playerAfterHeal      = playerWithXp.copy(hp = playerWithXp.hp + healedHp)
    val healLog   = if healedHp > 0 then List(LogLine.of("log.combat.killHeal", "amount" -> healedHp)) else Nil
    val healEvent = if healedHp > 0 then List(GameEvent.Healed(healedHp)) else Nil

    // Capture the enemy's position before removing it - needed for the Shrine/Chest spawns below,
    // but has to happen now since removeEntity doesn't hand back what it removed.
    val deadEnemyPosition = state.dungeon.currentRoom.entityById(deadEnemy.entityId).map(e => (e.x, e.y))

    // Known before resolving loot: a Boss kill spawns a Shrine (moved here from MiniBoss - the
    // run's hardest per-section fight earns the best reward), a MiniBoss kill spawns a Chest
    // (downgraded from Shrine - opening a chest is more deliberately visual than an auto-equip the
    // player might miss on an optional side encounter). Checked against the room the enemy was
    // actually in, before it's removed below.
    val isBossKill     = state.dungeon.currentRoom.roomType == RoomType.Boss
    val isMiniBossKill = state.dungeon.currentRoom.roomType == RoomType.MiniBoss

    // Remove the defeated enemy, and drop a Shrine (Boss) or Chest (MiniBoss) in its place - a
    // "grave" marking the checkpoint. The Shrine offers a guaranteed 3-item choice instead of the
    // usual single roll; the Chest resolves through the ordinary chest-open flow.
    val roomAfterRemoval = state.dungeon.currentRoom.removeEntity(deadEnemy.entityId)
    val updatedRoom =
      deadEnemyPosition match {
        case Some((x, y)) if isBossKill =>
          val devoted = state.enemyStats.get(deadEnemy.typeId).flatMap(_.devoted)
          roomAfterRemoval.withEntities(List(Shrine(id = s"shrine_${deadEnemy.entityId}", x = x, y = y, devoted = devoted)))
        case Some((x, y)) if isMiniBossKill =>
          roomAfterRemoval.withEntities(List(Chest(id = s"chest_${deadEnemy.entityId}", x = x, y = y)))
        case _ => roomAfterRemoval
      }
    val updatedDungeon = state.dungeon.copy(
      rooms = state.dungeon.rooms.updated(updatedRoom.id, updatedRoom)
    )

    val victoryLog = log ++ List(
      CombatResolver.defeatedLine(deadEnemy.typeId),
      if shardsEarned == 0 then LogLine.of("log.combat.xp", "xp" -> xpGained)
      else LogLine.of("log.combat.xpShards", "xp" -> xpGained, "count" -> shardsEarned)
    ) ++ healLog

    // isBossKill is also what drives the EnemyDefeated event below (the "defeat a boss"
    // achievement) - a boss kill no longer ends the run by itself, only interacting with the
    // dungeon's Sanctuary does (see InteractionResolver.handleSanctuary).

    // Elite kills guarantee at least Rare - no other enemy-kill-side floor source exists today
    // (Lucky Find/Rarity Insight only apply to chests), so a plain Some/None is enough here; if a
    // second source appears later, follow InteractionResolver's maxByOption(_.ordinal) precedence
    // pattern instead of special-casing.
    val eliteFloor = if deadEnemy.isElite then Some(Rarity.Rare) else None

    val (playerAfterLoot, lootLog, lootEvents, pendingChoice) =
      if isBossKill then
        (playerAfterHeal, List(LogLine.of("log.combat.leavesShrine", "enemy" -> deadEnemy.typeId)), Nil, None)
      else if isMiniBossKill then
        (playerAfterHeal, List(LogLine.of("log.combat.leavesChest", "enemy" -> deadEnemy.typeId)), Nil, None)
      else
        LootTable.rollEnemy(deadEnemy, itemDefs, rng, state.difficulty, eliteFloor) match {
          case None => (playerAfterHeal, Nil, Nil, None)
          case Some(item) =>
            EquipmentResolver.resolvePickup(playerAfterHeal, item, setDefs) match {
              case PickupOutcome.Equipped(p) =>
                (p,
                 List(LogLine.of("log.loot.dropped", "enemy" -> deadEnemy.typeId, "item" -> item.typeId)),
                 List(GameEvent.itemPickedUp(p, item, setDefs)),
                 None
                )
              case PickupOutcome.KeyCollected(p) =>
                (p,
                 List(LogLine.of("log.loot.dropped", "enemy" -> deadEnemy.typeId, "item" -> item.typeId)),
                 List(GameEvent.itemPickedUp(p, item, setDefs)),
                 None
                )
              case PickupOutcome.ChoicePending(pending) =>
                (playerAfterHeal,
                 List(LogLine.of("log.loot.droppedChoice", "enemy" -> deadEnemy.typeId, "item" -> item.typeId)),
                 Nil,
                 Some(pending)
                )
              case PickupOutcome.Discarded(p) =>
                (p,
                 List(LogLine.of("log.loot.droppedWorse", "enemy" -> deadEnemy.typeId, "item" -> item.typeId)),
                 Nil,
                 None
                )
            }
        }

    // Level-up after loot
    val startLevel                = playerAfterLoot.level
    val (finalPlayer, levelUpLog) = LevelUpSystem.applyLevelUps(playerAfterLoot, rng)
    val levelUpEvents = (startLevel + 1 to finalPlayer.level).map(GameEvent.LeveledUp.apply).toList

    val enemyDefeatedEvent =
      GameEvent.EnemyDefeated(isBoss = isBossKill,
                              tookNoDamage = !state.combat.tookDamage,
                              wasElite = deadEnemy.isElite,
                              typeId = deadEnemy.typeId
      )

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
                     log: List[LogLine]
  ): (GameState, List[LogLine], List[GameEvent]) =
    val defeatLog = log :+ LogLine.of("log.combat.playerDefeated")
    (GameOverState(deadPlayer),
     defeatLog,
     List(GameEvent.RunEnded(victory = false,
                             difficulty = state.difficulty,
                             activePerkId = deadPlayer.activePerkId
     ))
    )

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
  private[game] val CritMultiplier = StatCalculator.CritMultiplier

  /** Roll for a critical hit against a percent chance (0-100). Never crits at 0 or below. */
  private[game] def rollCrit(chancePercent: Int): Boolean =
    chancePercent > 0 && rng.nextInt(100) < chancePercent

  // ---------------------------------------------
  // Player stat accessors (include equipped-item bonuses)
  // ---------------------------------------------

  /** Every set bonus effect currently active for `player` (see [[SetDef.activeBonuses]]). */
  private[game] def activeSetBonuses(player: Player): List[SetBonusEffect] = stats.activeSetBonuses(player)

  private def sumSetBonus(player: Player, extract: PartialFunction[SetBonusEffect, Int]): Int =
    stats.sumSetBonus(player, extract)

  /** The effects of the run perks the player took, resolved against [[perkDefs]] (none if no perk is
    * active or its id isn't in the loaded catalog).
    */
  private[game] def activePerkEffects(player: Player): List[PerkEffect] = stats.activePerkEffects(player)

  extension (player: Player)
    /** Effective attack, defense and crit chance, see [[StatCalculator]]. */
    private def attack: Int               = stats.attack(player)
    private def defense: Int              = stats.defense(player)
    private[game] def critChance: Int     = stats.critChance(player)

object CombatResolver:

  /** The line that tells an enemy has fallen. In the update that ends a won fight it is where the blows
    * of the fight stop and its outcome begins, which [[roguelite.engine.StateUpdate.fightLines]] relies
    * on.
    */
  def defeatedLine(enemyTypeId: String): LogLine = LogLine.of(DefeatedKey, "enemy" -> enemyTypeId)

  /** The key of [[defeatedLine]], which marks where the blows of a won fight stop in its log. */
  val DefeatedKey = "log.combat.defeated"
