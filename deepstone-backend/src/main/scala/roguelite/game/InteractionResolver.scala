package roguelite.game

import roguelite.engine.{ CombatState, Direction, ExplorationState, GameState }

import scala.util.Random

/** Resolves everything the player can [[roguelite.engine.Interact]] with during exploration, plus
  * the secret-door reveal check (triggered by [[roguelite.engine.Move]], but the same family of
  * "entities reacting to the player" logic). Kept separate from [[roguelite.engine.StateMachine]]
  * for the same reason [[CombatResolver]] and [[LootTable]] already are: the state machine stays a
  * thin router as more entity kinds are added (NPC dialogue is next, in a later phase).
  *
  * @param enemyStats
  *   Lookup table of enemy stats keyed by typeId, used to start combat.
  * @param itemDefs
  *   Prototype item map keyed by typeId, used by [[LootTable]] for chest rolls.
  * @param rng
  *   Random instance for chest loot rolls and trapped-chest enemy spawns.
  */
class InteractionResolver(enemyStats: Map[String, EnemyStats],
                          itemDefs: Map[String, Item],
                          rng: Random = Random()
):

  def interact(exp: ExplorationState, targetId: String): (GameState, List[String]) =
    exp.dungeon.currentRoom.entityById(targetId) match {
      case None =>
        (exp, List(s"No entity found with id '$targetId'."))

      case Some(door: Door) if door.doorKind == DoorKind.Secret && !door.revealed =>
        (exp, List(s"No entity found with id '$targetId'.")) // never acknowledge an unrevealed door

      case Some(door: Door) if door.doorKind == DoorKind.Trapped =>
        handleTrappedDoor(exp, door)

      case Some(door: Door) =>
        handleDoor(exp, door)

      case Some(door: LockedDoor) =>
        handleLockedDoor(exp, door)

      case Some(enemy: Enemy) =>
        handleEnemy(exp, enemy)

      case Some(chest: Chest) =>
        handleChest(exp, chest)
    }

  /** Shared by a normal Door and an already-unlocked LockedDoor: navigate to the target room and
    * place the player at the tile adjacent to the door on the opposite wall. */
  private def navigateThroughDoor(exp: ExplorationState,
                                  targetRoomId: String,
                                  direction: Direction
  ): (GameState, List[String]) =
    exp.dungeon.navigateTo(targetRoomId) match {
      case Left(err) => (exp, List(err))
      case Right(newDungeon) =>
        val spawnPoint = findSpawnPoint(newDungeon.currentRoom, direction)
        val nextState =
          exp.copy(dungeon = newDungeon, playerX = spawnPoint._1, playerY = spawnPoint._2)
        (nextState, List(s"You pass through the door heading ${direction}."))
    }

  private def handleDoor(exp: ExplorationState, door: Door): (GameState, List[String]) =
    navigateThroughDoor(exp, door.targetRoomId, door.direction)

  /** Reuses the exact PREV-transition logic: find this room's entrance (Up) door and go there,
    * ignoring the trapped door's own targetRoomId entirely. */
  private def handleTrappedDoor(exp: ExplorationState, door: Door): (GameState, List[String]) =
    exp.dungeon.currentRoom.entities.collectFirst { case d: Door if d.direction == Direction.Up => d } match {
      case None =>
        (exp, List("The trap triggers, but there's nowhere to be thrown back to."))
      case Some(entranceDoor) =>
        val (state, _) = navigateThroughDoor(exp, entranceDoor.targetRoomId, entranceDoor.direction)
        (state, List("A trap triggers! You are thrown back."))
    }

  private def handleLockedDoor(exp: ExplorationState, door: LockedDoor): (GameState, List[String]) =
    if door.unlocked then navigateThroughDoor(exp, door.targetRoomId, door.direction)
    else
      exp.player.inventory.keys.find { case (_, key) => KeyKind.canUnlock(key.keyKind, door) } match {
        case None =>
          (exp, List("This door is locked. You need a key."))
        case Some((idx, key)) =>
          val (_, invAfterRemoval) = exp.player.inventory.removeAt(idx)
          val updatedPlayer        = exp.player.copy(inventory = invAfterRemoval)
          val unlockedRoom = exp.dungeon.currentRoom.updateEntity(door.id):
            case d: LockedDoor => d.copy(unlocked = true)
            case o             => o
          val updatedDungeon =
            exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(unlockedRoom.id, unlockedRoom))
          val (state, navLog) = navigateThroughDoor(
            exp.copy(dungeon = updatedDungeon, player = updatedPlayer),
            door.targetRoomId,
            door.direction
          )
          (state, s"You use ${key.name} to unlock the door." :: navLog)
      }

  private def handleEnemy(exp: ExplorationState, enemy: Enemy): (GameState, List[String]) =
    enemyStats.get(enemy.typeId) match {
      case None =>
        (exp, List(s"Unknown enemy type '${enemy.typeId}' — cannot start combat."))
      case Some(stats) =>
        val instance = EnemyInstance.fromStats(enemy.id, stats, exp.difficulty)
        val combat   = Combat(enemy = instance)
        val nextState = CombatState(exp.player,
                                    exp.dungeon,
                                    exp.playerX,
                                    exp.playerY,
                                    combat,
                                    enemy.id,
                                    exp.difficulty
        )
        (nextState, List(s"You engage the ${stats.label}!"))
    }

  private def handleChest(exp: ExplorationState, chest: Chest): (GameState, List[String]) =
    val roomWithoutChest = exp.dungeon.currentRoom.removeEntity(chest.id)

    if chest.trapped then
      val (trappedRoom, trapLog) =
        spawnTrapEnemies(roomWithoutChest, chest, exp.playerX, exp.playerY)
      val updatedDungeon =
        exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(trappedRoom.id, trappedRoom))
      (exp.copy(dungeon = updatedDungeon), trapLog)
    else
      val updatedDungeon = exp.dungeon.copy(
        rooms = exp.dungeon.rooms.updated(roomWithoutChest.id, roomWithoutChest)
      )

      LootTable.rollChest(itemDefs, rng, exp.difficulty) match {
        case None =>
          (exp.copy(dungeon = updatedDungeon), List("You open the chest. It's empty."))
        case Some(item) =>
          exp.player.withItemPickup(item) match {
            case Left(_) =>
              (exp.copy(dungeon = updatedDungeon),
               List(s"You open the chest but your inventory is full — ${item.name} is lost!")
              )

            case Right(updatedPlayer) =>
              (exp.copy(dungeon = updatedDungeon, player = updatedPlayer),
               List(s"You open the chest and find ${item.name}! (${item.statLine})")
              )
          }
      }

  /** Reveal any hidden secret door within Chebyshev distance 1 of (x, y). Called from
    * [[roguelite.engine.StateMachine]]'s Move handling, not Interact (same family of "entities
    * reacting to the player" logic, so it lives here rather than splitting door logic across two
    * classes). */
  def revealSecretDoors(room: Room, x: Int, y: Int): (Room, List[String]) =
    val toReveal = room.entities.collect:
      case d: Door
          if d.doorKind == DoorKind.Secret && !d.revealed &&
            math.max(math.abs(d.x - x), math.abs(d.y - y)) <= 1 =>
        d

    if toReveal.isEmpty then (room, Nil)
    else
      val updated = toReveal.foldLeft(room): (r, d) =>
        r.withFloorAt(d.x, d.y).updateEntity(d.id) {
          case door: Door => door.copy(revealed = true)
          case o          => o
        }
      (updated, List("You notice a hidden passage in the wall!"))

  /** Find a sensible spawn point in the target room when entering through a door.
    *
    * The player arrives at the tile adjacent to the door on the opposite wall. For example,
    * entering through a DOWN door means the player came from below, so they spawn just inside the
    * top of the new room. Falls back to (1,1) if the computed position is not walkable.
    */
  private def findSpawnPoint(room: Room, fromDirection: Direction): (Int, Int) =
    val candidate = fromDirection match {
      case Direction.Down => (room.width / 2, 1) // entered from south → spawn near north
      case Direction.Up =>
        (room.width / 2, room.height - 2) // entered from north → spawn near south
      case Direction.Right => (1, room.height / 2) // entered from east  → spawn near west
      case Direction.Left =>
        (room.width - 2, room.height / 2) // entered from west  → spawn near east
    }

    if room.isWalkable(candidate._1, candidate._2) then candidate else (1, 1)

  /** Non-boss enemy typeIds eligible to spawn from a trapped chest. Deliberately excludes
    * boss-tier enemies (roughly half the roster) so opening a chest never ambushes the player with
    * a full boss encounter.
    */
  private val TrapEnemyPool =
    List("goblin", "orc", "skeleton", "cave_troll", "bandit", "dire_wolf", "cultist")

  /** Spawn 1-2 enemies from [[TrapEnemyPool]] on free tiles near the chest, avoiding the player's
    * own tile. Falls back to fewer enemies (or none) if the room has no space.
    */
  private def spawnTrapEnemies(room: Room,
                               chest: Chest,
                               playerX: Int,
                               playerY: Int
  ): (Room, List[String]) =
    val pool = TrapEnemyPool.filter(enemyStats.contains)
    if pool.isEmpty then (room, List("It's a trap! But nothing emerges from the shadows."))
    else
      val count   = rng.nextInt(2) + 1
      val typeIds = List.fill(count)(pool(rng.nextInt(pool.size)))
      val spots   = room.nearbyFreeTiles(chest.x, chest.y, count, exclude = Set((playerX, playerY)))

      val newEnemies = typeIds.zip(spots).zipWithIndex.map {
        case ((typeId, (x, y)), i) =>
          Enemy(id = s"${chest.id}_trap_$i", x = x, y = y, typeId = typeId, label = enemyStats(typeId).label)
      }

      (room.withEntities(newEnemies), List("It's a trap! Enemies emerge from the shadows!"))
