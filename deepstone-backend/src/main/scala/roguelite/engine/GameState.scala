package roguelite.engine

import roguelite.game.{
  Combat,
  Dungeon,
  EnemyStats,
  Item,
  KeyKind,
  MetaProgression,
  PendingEquipChoice,
  UpgradeDef
}

/** Convert a game-layer Item to the protocol ItemView. Defined at file level so all GameState
  * subtypes (which live in this file) can use it without repeating the mapping.
  */
private def itemToView(item: Item): ItemView =
  ItemView(
    id = item.id,
    typeId = item.typeId,
    name = item.name,
    kind = item.kind,
    rarity = item.rarity.label,
    statLine = item.statLine
  )

/** Coarse display label for a key kind - collapses `Specific`/`Typed`'s payload away, see
  * [[KeyCountView]].
  */
private def keyKindLabel(kind: KeyKind): String = kind match
  case KeyKind.Generic     => "generic"
  case KeyKind.Specific(_) => "specific"
  case KeyKind.Typed(_)    => "typed"
  case KeyKind.Universal   => "universal"

/** Project a [[PendingEquipChoice]] into the client-facing [[PendingEquipChoiceView]]. */
private def pendingChoiceToView(pending: PendingEquipChoice): PendingEquipChoiceView =
  PendingEquipChoiceView(
    newItem = itemToView(pending.newItem),
    options = pending.currentItems.toList.map { case (slot, item) => EquipChoiceOptionView(slot, itemToView(item)) }
  )

/** Project a player's equipment into the client-facing [[EquipmentView]]. */
private def equipmentToView(player: Player): EquipmentView =
  EquipmentView(
    weapon = player.equippedWeapon.map(itemToView),
    armor = player.equippedArmor.map(itemToView),
    accessories = player.equippedAccessories.map(_.map(itemToView)).toList,
    potionBelt = player.potionBelt.map(_.map(itemToView)).toList,
    keys = player.keyCounts.collect {
      case (kind, count) if count > 0 => KeyCountView(keyKindLabel(kind), count)
    }.toList
  )

// ---------------------------------------------
// Game states (one per game phase)
//
// These are richer than the client-facing views.
// The views in MessageProtocol are read-only projections of these.
// ---------------------------------------------

/** The four possible states the server-side game can be in. */
sealed trait GameState:
  def player: Player

  /** Project this game state into a StateUpdate for the client. */
  def toStateUpdate(log: List[String] = Nil, dialogue: Option[DialogueView] = None): StateUpdate

/** @param upgradeDefs
  *   The loaded upgrade catalog (see [[roguelite.game.UpgradeLoader]]), used to render [[HubView]].
  *   Defaults to empty so tests that don't care about the upgrade list can keep using
  *   `HubState(player)`.
  */
case class HubState(player: Player,
                    upgradeDefs: Map[String, UpgradeDef] = Map.empty,
                    meta: MetaProgression = MetaProgression.empty
) extends GameState:
  def toStateUpdate(log: List[String] = Nil, dialogue: Option[DialogueView] = None): StateUpdate =
    StateUpdate(
      phase = GamePhase.Hub,
      player = player.toView,
      hub = Some(HubView(upgrades = upgradeDefs.values.toList.sortBy(_.displayOrder).map {
        u =>
          UpgradeView(
            id = u.id,
            label = u.label,
            description = u.description,
            cost = u.cost,
            icon = u.icon,
            unlocked = meta.isUnlocked(u.id)
          )
      })),
      equipment = equipmentToView(player),
      log = log
    )

/** @param enemyStats
  *   The loaded enemy catalog (see [[roguelite.game.EnemyLoader]]), forwarded to
  *   [[roguelite.game.Room.toView]] to resolve each enemy's `spriteId`. Defaults to empty so
  *   tests that don't care about sprites can keep using the shorter constructor.
  * @param pendingEquipChoice
  *   A pickup awaiting a keep/replace decision (see [[PendingEquipChoice]]). Durable server
  *   state, not a transient per-action signal - it survives until resolved by an `EquipChoice`
  *   action, so it is resolved into a view here on every call, not projected from `events` like
  *   `damageEvents`/`soundEvents`.
  */
case class ExplorationState(player: Player,
                            dungeon: Dungeon,
                            playerX: Int,
                            playerY: Int,
                            difficulty: Difficulty = Difficulty.Normal,
                            enemyStats: Map[String, EnemyStats] = Map.empty,
                            pendingEquipChoice: Option[PendingEquipChoice] = None
) extends GameState:
  def toStateUpdate(log: List[String] = Nil, dialogue: Option[DialogueView] = None): StateUpdate =
    StateUpdate(
      phase = GamePhase.Exploration,
      player = player.toView,
      room = Some(dungeon.currentRoom.toView(playerX, playerY, enemyStats)),
      equipment = equipmentToView(player),
      pendingEquipChoice = pendingEquipChoice.map(pendingChoiceToView),
      log = log,
      dialogue = dialogue
    )

/** Active combat state.
  *
  * @param combat
  *   Runtime state of the current fight.
  * @param enemyEntityId
  *   Id of the Enemy entity in the room, used to remove it after a victorious combat.
  * @param enemyStats
  *   Same role as on [[ExplorationState]] - forwarded to [[roguelite.game.Room.toView]].
  */
case class CombatState(player: Player,
                       dungeon: Dungeon,
                       playerX: Int,
                       playerY: Int,
                       combat: Combat,
                       enemyEntityId: String,
                       difficulty: Difficulty = Difficulty.Normal,
                       enemyStats: Map[String, EnemyStats] = Map.empty
) extends GameState:
  def toStateUpdate(log: List[String] = Nil, dialogue: Option[DialogueView] = None): StateUpdate =
    StateUpdate(
      phase = GamePhase.Combat,
      player = player.toView,
      room = Some(dungeon.currentRoom.toView(playerX, playerY, enemyStats)),
      combat = Some(
        CombatView(enemyId = combat.enemy.typeId,
                   enemyLabel = combat.enemy.label,
                   enemyHp = combat.enemy.hp,
                   enemyMaxHp = combat.enemy.maxHp,
                   isPlayerTurn = combat.isPlayerTurn,
                   spriteId = enemyStats.get(combat.enemy.typeId).map(_.spriteId),
                   isBoss = dungeon.isAtBoss
        )
      ),
      equipment = equipmentToView(player),
      log = log
    )

/** @param victory
  *   True if the run ended by defeating the dungeon's boss, false if the player died.
  */
case class GameOverState(player: Player, victory: Boolean = false) extends GameState:
  def toStateUpdate(log: List[String] = Nil, dialogue: Option[DialogueView] = None): StateUpdate =
    StateUpdate(phase = GamePhase.GameOver,
                player = player.toView,
                equipment = equipmentToView(player),
                victory = victory,
                log = log
    )
