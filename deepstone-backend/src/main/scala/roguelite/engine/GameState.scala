package roguelite.engine

import roguelite.game.{ Combat, Dungeon, EnemyStats, Item, MetaProgression, UpgradeDef }

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

/** Temporary: flattens the typed equipment fields back into the pre-existing flat-list wire shape
  * (no slot distinction, keys omitted since they are no longer Items). Keeps this commit scoped to
  * the domain model - a real EquipmentView replaces this in the very next commit, at which point
  * this function goes away entirely.
  */
private def equipmentToFlatViews(player: Player): List[ItemView] =
  (player.equippedWeapon.toList ++ player.equippedArmor.toList ++
    player.equippedAccessories.flatten ++ player.potionBelt.flatten).map(itemToView)

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
      inventory = equipmentToFlatViews(player),
      log = log
    )

/** @param enemyStats
  *   The loaded enemy catalog (see [[roguelite.game.EnemyLoader]]), forwarded to
  *   [[roguelite.game.Room.toView]] to resolve each enemy's `spriteId`. Defaults to empty so
  *   tests that don't care about sprites can keep using the shorter constructor.
  */
case class ExplorationState(player: Player,
                            dungeon: Dungeon,
                            playerX: Int,
                            playerY: Int,
                            difficulty: Difficulty = Difficulty.Normal,
                            enemyStats: Map[String, EnemyStats] = Map.empty
) extends GameState:
  def toStateUpdate(log: List[String] = Nil, dialogue: Option[DialogueView] = None): StateUpdate =
    StateUpdate(
      phase = GamePhase.Exploration,
      player = player.toView,
      room = Some(dungeon.currentRoom.toView(playerX, playerY, enemyStats)),
      inventory = equipmentToFlatViews(player),
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
      inventory = equipmentToFlatViews(player),
      log = log
    )

/** @param victory
  *   True if the run ended by defeating the dungeon's boss, false if the player died.
  */
case class GameOverState(player: Player, victory: Boolean = false) extends GameState:
  def toStateUpdate(log: List[String] = Nil, dialogue: Option[DialogueView] = None): StateUpdate =
    StateUpdate(phase = GamePhase.GameOver,
                player = player.toView,
                inventory = equipmentToFlatViews(player),
                victory = victory,
                log = log
    )
