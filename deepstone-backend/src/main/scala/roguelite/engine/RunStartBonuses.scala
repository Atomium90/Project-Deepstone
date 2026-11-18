package roguelite.engine

import roguelite.game.items.{ EquipmentResolver, Item, PickupOutcome, SetDef }
import roguelite.game.progression.{ MetaProgression, PerkDef, PerkEffect, UpgradeDef, UpgradeEffect }

/** What the hub's upgrades and the run's perks change in the player once, as a run starts.
  *
  * Pure, so [[GameSession]] (which knows the save's [[MetaProgression]]) and anything that plays a
  * run without a session (the balance simulation) start a run the same way. The state machine builds
  * the player and its starting kit first; this adds the blessings on top.
  *
  * @param upgradeDefs
  *   The upgrade catalog, which says what each owned upgrade does.
  * @param itemDefs
  *   Item prototypes, to resolve the `StartingItem` effect.
  * @param setDefs
  *   The equipment sets, so a starting item that belongs to one reconciles its HP bonus.
  * @param perkDefs
  *   The run perks, to find the max HP malus of the ones the player took.
  */
final class RunStartBonuses(upgradeDefs: Map[String, UpgradeDef],
                            itemDefs: Map[String, Item],
                            setDefs: Map[String, SetDef],
                            perkDefs: Map[String, PerkDef]
):

  /** `state` with the effects of the owned upgrades applied, then the max HP malus of the perks (last,
    * so the hub's HP upgrades cannot undo it). A run that takes no blessing (Hardcore) keeps only the
    * late-game upgrades, the ones a save earned by winning on Hard: no other upgrade, no perk.
    */
  def apply(state: ExplorationState, meta: MetaProgression): ExplorationState =
    if state.difficulty.usesBlessings then applyPerkMaxHp(applyMetaBonuses(state, meta, _ => true))
    else applyMetaBonuses(state, meta, _.requires.isDefined)

  /** The max HP malus (or bonus) of the perks the player took, applied at the start of the run. */
  private def applyPerkMaxHp(state: ExplorationState): ExplorationState =
    val percent = PerkDef.activeEffects(state.player, perkDefs).collect { case PerkEffect.MaxHpPercent(n) => n }.sum
    if percent == 0 then state
    else
      val player = state.player
      val maxHp  = math.max(1, math.round(player.maxHp * (100 + percent) / 100.0).toInt)
      state.copy(player = player.copy(maxHp = maxHp, hp = math.min(player.hp, maxHp)))

  /** Apply every unlocked upgrade's [[UpgradeEffect]] to the player at the start of a new run.
    *
    * Generic over the effect kind: adding a new upgrade only means adding an entry to
    * `upgrades.json` (and, if it's a genuinely new *kind* of effect, one case here and in
    * [[UpgradeEffect]]), never a change keyed by upgrade id.
    */
  private def applyMetaBonuses(state: ExplorationState,
                               meta: MetaProgression,
                               applies: UpgradeDef => Boolean
  ): ExplorationState =
    val effects       = meta.unlockedUpgrades.toList.flatMap(upgradeDefs.get).filter(applies).map(_.effect)
    val boostedPlayer = effects.foldLeft(state.player)(applyUpgradeEffect)
    state.copy(player = boostedPlayer)

  private def applyUpgradeEffect(player: Player, effect: UpgradeEffect): Player = effect match
    case UpgradeEffect.MaxHpBoost(amount) =>
      player.copy(maxHp = player.maxHp + amount, hp = player.hp + amount)

    case UpgradeEffect.ExtraPotionSlot =>
      if player.potionBelt.length <= Player.BasePotionBeltSlots
      then player.copy(potionBelt = player.potionBelt :+ None)
      else player

    case UpgradeEffect.ExtraPotionCapacity =>
      player.copy(potionCapacity = Player.UpgradedPotionCapacity)

    case UpgradeEffect.StartingItem(typeId) =>
      itemDefs.get(typeId) match
        case None => player
        case Some(proto) =>
          EquipmentResolver.resolvePickup(player, proto.withNewId, setDefs) match
            case PickupOutcome.Equipped(p)      => p
            case PickupOutcome.KeyCollected(p)  => p
            case PickupOutcome.ChoicePending(_) => player // no room => silently skip
            case PickupOutcome.Discarded(p)     => p

    case UpgradeEffect.FlatAttackBoost(amount) =>
      player.copy(bonusAttack = player.bonusAttack + amount)

    case UpgradeEffect.FlatDefenseBoost(amount) =>
      player.copy(bonusDefense = player.bonusDefense + amount)

    case UpgradeEffect.GuaranteedChestRarity(minRarity) =>
      player.copy(chestRarityFloor = Some(minRarity))

    case UpgradeEffect.UnlockClass(_) =>
      // Gates class selection at StartRun (see StateMachine); no player-state effect to apply.
      player

    case UpgradeEffect.UnlockStartingKit(_) =>
      // Gates the starting-kit fold itself inside StateMachine's StartRun case (runs before
      // the bonuses are applied); no player-state effect to apply here.
      player
