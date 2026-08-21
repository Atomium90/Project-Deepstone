package roguelite.game

import roguelite.engine.ClassId

/** Effect applied when an upgrade is unlocked.
  *
  * Interpreted in two places:
  *   - Player-state effects (`MaxHpBoost`, `ExtraPotionSlot`, `StartingItem`) are applied once
  *     per run, at Hub → Exploration transition, by
  *     [[roguelite.engine.GameSession.applyMetaBonuses]].
  *   - `UnlockClass` is a gate, not a player-state change: [[roguelite.engine.StateMachine]]
  *     checks it when handling `StartRun` and rejects locked classes.
  */
enum UpgradeEffect:
  /** Increase max HP (and current HP by the same amount) at the start of each run. */
  case MaxHpBoost(amount: Int)

  /** Grow the potion belt from [[roguelite.engine.Player.BasePotionBeltSlots]] to 3 slots. */
  case ExtraPotionSlot

  /** Raise each potion-belt stack's capacity from
    * [[roguelite.engine.Player.BasePotionCapacity]] to [[roguelite.engine.Player.UpgradedPotionCapacity]].
    */
  case ExtraPotionCapacity

  /** Add one instance of the given item typeId to the starting inventory. Silently skipped if the
    * typeId is unknown or the inventory is full.
    */
  case StartingItem(typeId: String)

  /** Gate selecting the given class at run start behind this upgrade. Classes with no matching
    * `UnlockClass` upgrade are available by default.
    */
  case UnlockClass(classId: ClassId)

  /** Flat bonus added to the player's effective attack for every run, permanently. Reuses
    * [[roguelite.engine.Player.bonusAttack]] directly - the same field level-up perks already
    * add to, already wired into the attack formula - so no new [[roguelite.game.CombatResolver]]
    * hook is needed.
    */
  case FlatAttackBoost(amount: Int)

  /** Guarantee at least `minRarity` on every non-trapped chest for the whole run, permanently.
    * Baked onto [[roguelite.engine.Player.chestRarityFloor]] once at Hub -> Exploration, not
    * re-checked against the hub catalog per chest - same "resolve once onto Player" discipline as
    * `bonusAttack`/`potionCapacity`. Deliberately weaker than the Lucky Find perk's one-shot
    * Rare-or-better first chest (this applies to every chest, not just the first), so the upgrade
    * doesn't make the perk redundant - see [[roguelite.game.InteractionResolver.handleChest]] for
    * how the two combine when both are active.
    */
  case GuaranteedChestRarity(minRarity: Rarity)

/** Which HUB upgrade tab an upgrade belongs to (the ALL tab always shows everything).
  *
  * `Stat`: a mechanical run buff (more HP, more potion slots). `Meta`: unlocks or changes the
  * starting kit (a new class, a guaranteed starting item).
  */
enum UpgradeCategory:
  case Stat, Meta

/** Static definition of one hub upgrade available for purchase, loaded from `data/upgrades.json`.
  *
  * @param displayOrder
  *   Position in the hub upgrade list (ascending). JSON array order is not preserved once loaded
  *   into a `Map`, so this field is the explicit source of truth for display order.
  */
case class UpgradeDef(
    id: String,
    label: String,
    description: String,
    cost: Int,
    displayOrder: Int,
    icon: String,
    category: UpgradeCategory,
    effect: UpgradeEffect
)
