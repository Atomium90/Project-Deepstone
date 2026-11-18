package roguelite.sim

import roguelite.engine.{
  CombatAction,
  CombatActionType,
  CombatState,
  EquipChoice,
  ExplorationState,
  GameState,
  Interact,
  Player,
  PlayerAction,
  RewardChoice
}
import roguelite.game.combat.{ AbilityDef, TimedBuffEffect }
import roguelite.game.dungeon.{
  Chest,
  ChestState,
  ConnectorRole,
  Door,
  DoorKind,
  DoorLink,
  Dungeon,
  Enemy,
  RoomType,
  Sanctuary,
  Shrine
}
import roguelite.game.items.{
  Accessory,
  Armor,
  Consumable,
  ConsumableEffect,
  Item,
  Key,
  PendingEquipChoice,
  PendingRewardChoice,
  Weapon
}

import scala.collection.mutable

/** How the bot plays. The defaults are the "ordinary player" the spike is calibrated on: ability as
  * soon as it can be paid, a plain attack otherwise, a healing potion when the HP run low.
  *
  * @param healBelowPercent
  *   A healing potion is drunk when HP fall under this percentage of the maximum.
  * @param usePowerPotions
  *   Buff, damage and resource potions are drunk in the fights that matter (Elite, MiniBoss, Boss).
  * @param clearRooms
  *   Fight every enemy of a room. When false, only the Boss is fought (the way on is shut until then).
  * @param openChests
  *   Open every chest of a room.
  * @param takeMiniBossBranch
  *   At a fork, take the branch that holds the MiniBoss (more loot, a harder fight) rather than the
  *   other one.
  */
final case class BotPolicy(
    healBelowPercent: Int = 40,
    usePowerPotions: Boolean = true,
    clearRooms: Boolean = true,
    openChests: Boolean = true,
    takeMiniBossBranch: Boolean = true
)

/** A player that reads the server-side state and answers with the next action. One instance plays
  * one run: it remembers the chests it has already given up on.
  */
final class Bot(world: SimWorld, policy: BotPolicy):

  /** `room id/chest id` of the chests whose item the bot declined, so it does not open them again. */
  private val declinedChests = mutable.Set.empty[String]
  private val chestAttempts  = mutable.Map.empty[String, Int].withDefaultValue(0)

  /** The next action, or None once there is nothing left to decide (the run is over). */
  def decide(state: GameState): Option[PlayerAction] = state match
    case exp: ExplorationState => explore(exp)
    case combat: CombatState   => Some(fight(combat))
    case _                     => None

  // ---------------------------------------------------------------------------------------------
  // Exploration
  // ---------------------------------------------------------------------------------------------

  private def explore(exp: ExplorationState): Option[PlayerAction] =
    exp.pendingEquipChoice
      .map(answerEquipChoice(exp, _))
      .orElse(exp.pendingRewardChoice.map(answerRewardChoice(exp.player, _)))
      .orElse(act(exp))

  private def act(exp: ExplorationState): Option[PlayerAction] =
    val room     = exp.dungeon.currentRoom
    def key(id: String) = s"${room.id}/$id"
    val sanctuary = room.entities.collectFirst { case s: Sanctuary => s }
    val shrine    = room.entities.collectFirst { case s: Shrine => s }
    val enemy =
      room.entities.collectFirst { case e: Enemy if policy.clearRooms || room.roomType == RoomType.Boss => e }
    def worthOpening(c: Chest): Boolean =
      (c.state == ChestState.Closed || c.state == ChestState.OpenFull) &&
        !declinedChests.contains(key(c.id)) && chestAttempts(key(c.id)) < 3
    val chest =
      if policy.openChests then room.entities.collectFirst { case c: Chest if worthOpening(c) => c } else None

    sanctuary.map(s => Interact(s.id))
      .orElse(shrine.map(s => Interact(s.id)))
      .orElse(enemy.map(e => Interact(e.id)))
      .orElse(chest.map { c =>
        chestAttempts(key(c.id)) += 1
        Interact(c.id)
      })
      .orElse(nextDoor(exp).map(d => Interact(d.id)))

  /** The way on: the one forward door, or at a fork the one the policy prefers. */
  private def nextDoor(exp: ExplorationState): Option[Door] =
    val forward = exp.dungeon.currentRoom.entities.collect:
      case d: Door if d.link.role == ConnectorRole.Next && (d.doorKind != DoorKind.Secret || d.revealed) => d
    forward match
      case Nil           => None
      case single :: Nil => Some(single)
      case many =>
        many
          .find(d => reachesMiniBoss(exp.dungeon, targetOf(d)) == policy.takeMiniBossBranch)
          .orElse(many.headOption)

  private def targetOf(door: Door): String = door.link match
    case DoorLink.Resolved(_, _, roomId) => roomId
    case _                               => ""

  /** Whether a MiniBoss lies ahead of `roomId` before the next Boss. */
  private def reachesMiniBoss(dungeon: Dungeon, roomId: String, depth: Int = 4): Boolean =
    dungeon.rooms.get(roomId).exists:
      room =>
        room.roomType == RoomType.MiniBoss ||
          (depth > 0 && room.roomType != RoomType.Boss && room.entities.exists:
            case d: Door if d.link.role == ConnectorRole.Next => reachesMiniBoss(dungeon, targetOf(d), depth - 1)
            case _                                            => false
          )

  // ---------------------------------------------------------------------------------------------
  // Choices
  // ---------------------------------------------------------------------------------------------

  /** Takes the swap with the best gain, or keeps what is equipped when no swap is worth it. */
  private def answerEquipChoice(exp: ExplorationState, pending: PendingEquipChoice): PlayerAction =
    val best = pending.currentItems.toList
      .map((slot, current) => slot -> (value(exp.player, pending.newItem) - value(exp.player, current)))
      .maxByOption(_._2)
    best.filter(_._2 > 0) match
      case Some((slot, _)) => EquipChoice(Some(slot))
      case None =>
        pending.sourceChestId.foreach(id => declinedChests += s"${exp.dungeon.currentRoomId}/$id")
        EquipChoice(None)

  /** At a Shrine: the offer that improves the loadout the most. */
  private def answerRewardChoice(player: Player, pending: PendingRewardChoice): PlayerAction =
    RewardChoice(pending.options.maxByOption(acquireGain(player, _)).map(_.id))

  /** What taking `item` would add to the loadout: its value minus the value of what it would replace. */
  private def acquireGain(player: Player, item: Item): Double =
    def sameOrBetterHeld(held: Option[Item]) = held.exists(h => h.typeId == item.typeId && h.rarity.ordinal >= item.rarity.ordinal)
    item match
      case w: Weapon =>
        if sameOrBetterHeld(player.equippedWeapon) then 0.0
        else value(player, w) - player.equippedWeapon.fold(0.0)(value(player, _))
      case a: Armor =>
        if sameOrBetterHeld(player.equippedArmor) then 0.0
        else value(player, a) - player.equippedArmor.fold(0.0)(value(player, _))
      case acc: Accessory =>
        if player.equippedAccessories.exists(sameOrBetterHeld) then 0.0
        else value(player, acc) - player.equippedAccessories.map(_.fold(0.0)(value(player, _))).min
      case c: Consumable => value(player, c) * 0.5
      case _: Key        => 0.0

  /** One number for how good an item is for this player, in "points of attack". Deliberately crude:
    * what matters is that the bot keeps the better gear, not that it plays optimally.
    */
  private def value(player: Player, item: Item): Double = item match
    case c: Consumable => if isHeal(c.effect) then 3.0 else 1.5
    case _: Key        => 0.0
    case gear =>
      val s = gear.effectiveStats(player.affinityTags)
      s.attack.getOrElse(0) + s.defense.getOrElse(0) + s.maxHp.getOrElse(0) * 0.1 +
        s.critChance.getOrElse(0) * 0.1 + ownSetBonus(player, gear)

  /** A flat nudge towards the pieces of a set of the player's own class. */
  private def ownSetBonus(player: Player, item: Item): Double =
    val setId = item match
      case w: Weapon      => w.setId
      case a: Armor       => a.setId
      case acc: Accessory => acc.setId
      case _              => None
    if setId.flatMap(world.setDefs.get).exists(_.classId == player.classId) then 4.0 else 0.0

  private def isHeal(effect: ConsumableEffect): Boolean = effect match
    case ConsumableEffect.HealFixed(_) | ConsumableEffect.HealPercent(_) => true
    case _                                                               => false

  // ---------------------------------------------------------------------------------------------
  // Combat
  // ---------------------------------------------------------------------------------------------

  private def fight(state: CombatState): CombatAction =
    val player   = state.player
    val combat   = state.combat
    val hpPct    = player.hp * 100 / math.max(1, player.maxHp)
    val stacks   = player.potionBelt.flatten
    val ability  = world.abilityDefs.get(player.classId)
    val cost     = ability.map(AbilityDef.effectiveCost(player, _, world.setDefs, world.perkDefs))
    val mattering = combat.enemy.isElite || state.dungeon.currentRoom.roomType == RoomType.Boss ||
      state.dungeon.currentRoom.roomType == RoomType.MiniBoss

    def drink(stack: roguelite.game.items.PotionStack) = CombatAction(CombatActionType.Item, itemId = Some(stack.item.id))
    def has(pred: ConsumableEffect => Boolean) = stacks.find(s => pred(s.item.effect))

    val heal = if hpPct < policy.healBelowPercent then has(isHeal) else None
    def usefulNow(effect: ConsumableEffect): Boolean = effect match
      case ConsumableEffect.AttackBuff(_, _) =>
        !combat.activeBuffs.exists(_.effect.isInstanceOf[TimedBuffEffect.AttackBonusPercent])
      case ConsumableEffect.CritBuff(_, _) =>
        !combat.activeBuffs.exists(_.effect.isInstanceOf[TimedBuffEffect.CritChanceBonusPercent])
      case ConsumableEffect.FlatDamage(_) => true
      case ConsumableEffect.RestoreResource(_) =>
        cost.exists(player.resourceCurrent < _) && combat.pendingAbility.isEmpty
      case _ => false
    val power = if policy.usePowerPotions && mattering then has(usefulNow) else None
    val canCast = combat.pendingAbility.isEmpty && cost.exists(player.resourceCurrent >= _)

    heal.map(drink)
      .orElse(power.map(drink))
      .getOrElse(
        if canCast then CombatAction(CombatActionType.Ability, abilityId = ability.map(_.id))
        else CombatAction(CombatActionType.Attack)
      )
