package roguelite.game

import roguelite.engine.{ CombatState, DialogueView, Direction, ExplorationState, GameState, TransitionResult }

import scala.util.Random

/** Resolves everything the player can [[roguelite.engine.Interact]] with during exploration, plus
  * the secret-door reveal check (triggered by [[roguelite.engine.Move]], but the same family of
  * "entities reacting to the player" logic). Kept separate from [[roguelite.engine.StateMachine]]
  * for the same reason [[CombatResolver]] and [[LootTable]] already are: the state machine stays a
  * thin router as more entity kinds are added.
  *
  * @param enemyStats
  *   Lookup table of enemy stats keyed by typeId, used to start combat.
  * @param itemDefs
  *   Prototype item map keyed by typeId, used by [[LootTable]] for chest rolls.
  * @param rng
  *   Random instance for chest loot rolls and trapped-chest enemy spawns.
  * @param npcDialogueDefs
  *   Dialogue content keyed by [[Npc.id]], used by [[handleNpc]].
  * @param clock
  *   Wall-clock time source for the NPC interact cooldown. Inject a fake one for deterministic
  *   tests; defaults to real time in production.
  */
class InteractionResolver(enemyStats: Map[String, EnemyStats],
                          itemDefs: Map[String, Item],
                          rng: Random = Random(),
                          npcDialogueDefs: Map[String, NpcDialogueDef] = Map.empty,
                          clock: () => Long = () => System.currentTimeMillis()
):

  def interact(exp: ExplorationState, targetId: String): TransitionResult =
    exp.dungeon.currentRoom.entityById(targetId) match {
      case None =>
        TransitionResult(exp, List(s"No entity found with id '$targetId'."))

      case Some(door: Door) if door.doorKind == DoorKind.Secret && !door.revealed =>
        TransitionResult(exp, List(s"No entity found with id '$targetId'.")) // never acknowledge an unrevealed door

      case Some(door: Door) if door.doorKind == DoorKind.Trapped =>
        lift(handleTrappedDoor(exp, door))

      case Some(door: Door) =>
        lift(handleDoor(exp, door))

      case Some(door: LockedDoor) =>
        liftEvents(handleLockedDoor(exp, door))

      case Some(enemy: Enemy) =>
        lift(handleEnemy(exp, enemy))

      case Some(chest: Chest) =>
        liftEvents(handleChest(exp, chest))

      case Some(npc: Npc) =>
        val (state, log, dialogue) = handleNpc(exp, npc)
        TransitionResult(state, log, dialogue)
    }

  /** Lifts a handler that never produces dialogue or events into the richer return type `interact`
    * needs, so every existing per-entity handler below can stay untouched. */
  private def lift(r: (GameState, List[String])): TransitionResult = TransitionResult(r._1, r._2)

  /** Same as [[lift]], for handlers that also report [[GameEvent]]s. */
  private def liftEvents(r: (GameState, List[String], List[GameEvent])): TransitionResult =
    TransitionResult(r._1, r._2, events = r._3)

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

  private def handleLockedDoor(exp: ExplorationState,
                               door: LockedDoor
  ): (GameState, List[String], List[GameEvent]) =
    if door.unlocked then
      val (state, log) = navigateThroughDoor(exp, door.targetRoomId, door.direction)
      (state, log, Nil)
    else
      exp.player.inventory.keys.find { case (_, key) => KeyKind.canUnlock(key.keyKind, door) } match {
        case None =>
          (exp, List("This door is locked. You need a key."), Nil)
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
          (state, s"You use ${key.name} to unlock the door." :: navLog, List(GameEvent.DoorUnlockedWithKey))
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

  private def handleChest(exp: ExplorationState,
                          chest: Chest
  ): (GameState, List[String], List[GameEvent]) =
    val roomWithoutChest = exp.dungeon.currentRoom.removeEntity(chest.id)

    if chest.trapped then
      val (trappedRoom, trapLog) =
        spawnTrapEnemies(roomWithoutChest, chest, exp.playerX, exp.playerY)
      val updatedDungeon =
        exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(trappedRoom.id, trappedRoom))
      (exp.copy(dungeon = updatedDungeon), trapLog, Nil)
    else
      val updatedDungeon = exp.dungeon.copy(
        rooms = exp.dungeon.rooms.updated(roomWithoutChest.id, roomWithoutChest)
      )

      LootTable.rollChest(itemDefs, rng, exp.difficulty) match {
        case None =>
          (exp.copy(dungeon = updatedDungeon), List("You open the chest. It's empty."), Nil)
        case Some(item) =>
          exp.player.withItemPickup(item) match {
            case Left(_) =>
              (exp.copy(dungeon = updatedDungeon),
               List(s"You open the chest but your inventory is full — ${item.name} is lost!"),
               Nil
              )

            case Right(updatedPlayer) =>
              (exp.copy(dungeon = updatedDungeon, player = updatedPlayer),
               List(s"You open the chest and find ${item.name}! (${item.statLine})"),
               List(GameEvent.ItemPickedUp(inventoryFull = updatedPlayer.inventory.isFull))
              )
          }
      }

  /** Show one line of dialogue. Never touches the narrative log (a chest/door produces "you
    * open..." style flavor text there, but dialogue rides only [[DialogueView]] so it doesn't get
    * displayed twice).
    *
    * If the NPC has no matching entry in npcs.json, degrades gracefully instead of crashing - a
    * placed-but-uncontented NPC shouldn't break exploration.
    */
  private def handleNpc(exp: ExplorationState,
                        npc: Npc
  ): (GameState, List[String], Option[DialogueView]) =
    npcDialogueDefs.get(npc.id) match
      case None =>
        (exp, List(s"${npc.name} has nothing to say."), None)

      case Some(d) =>
        val now = clock()
        npc.lastShown match
          case Some((shownAt, shownLine)) if now - shownAt < InteractionResolver.NpcInteractCooldownMillis =>
            // Redisplay only: a misclick or key-repeat within the cooldown must never advance past
            // a line the player hasn't had time to read, so the Npc entity is left untouched here.
            (exp, Nil, Some(DialogueView(npc.name, shownLine)))

          case _ =>
            val (line, updatedNpc) = nextLine(npc, d, now)
            val updatedRoom = exp.dungeon.currentRoom.updateEntity(npc.id):
              case n: Npc => updatedNpc
              case o      => o
            val updatedDungeon =
              exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(updatedRoom.id, updatedRoom))
            (exp.copy(dungeon = updatedDungeon), Nil, Some(DialogueView(npc.name, line)))

  /** Pick the next line to show and the updated [[Npc]] progress: advance through `d.dialogue` in
    * order first, then rotate through `d.fallbackDialogue` forever (never repeating the same
    * fallback line twice in a row when the pool has more than one entry). If there is no fallback
    * content, keep re-showing the last main line rather than looping back to the start.
    */
  private def nextLine(npc: Npc, d: NpcDialogueDef, now: Long): (String, Npc) =
    if npc.dialogueIndex < d.dialogue.length then
      val line = d.dialogue(npc.dialogueIndex)
      (line, npc.copy(dialogueIndex = npc.dialogueIndex + 1, lastShown = Some((now, line))))
    else if d.fallbackDialogue.isEmpty then
      val line = d.dialogue.last
      (line, npc.copy(lastShown = Some((now, line))))
    else
      val nextIdx = npc.fallbackIndex match
        case None    => 0
        case Some(i) => if d.fallbackDialogue.length <= 1 then i else (i + 1) % d.fallbackDialogue.length
      val line = d.fallbackDialogue(nextIdx)
      (line, npc.copy(fallbackIndex = Some(nextIdx), lastShown = Some((now, line))))

  /** Reveal any hidden secret door within Chebyshev distance 1 of (x, y). Called from
    * [[roguelite.engine.StateMachine]]'s Move handling, not Interact (same family of "entities
    * reacting to the player" logic, so it lives here rather than splitting door logic across two
    * classes). */
  def revealSecretDoors(room: Room, x: Int, y: Int): (Room, List[String], List[GameEvent]) =
    val toReveal = room.entities.collect:
      case d: Door
          if d.doorKind == DoorKind.Secret && !d.revealed &&
            math.max(math.abs(d.x - x), math.abs(d.y - y)) <= 1 =>
        d

    if toReveal.isEmpty then (room, Nil, Nil)
    else
      val updated = toReveal.foldLeft(room): (r, d) =>
        r.withFloorAt(d.x, d.y).updateEntity(d.id) {
          case door: Door => door.copy(revealed = true)
          case o          => o
        }
      (updated, List("You notice a hidden passage in the wall!"), List(GameEvent.SecretDoorRevealed))

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

object InteractionResolver:
  /** Minimum real time between two dialogue advances on the same NPC. Re-interacting sooner than
    * this redisplays the current line instead of advancing - see [[InteractionResolver.handleNpc]].
    */
  val NpcInteractCooldownMillis: Long = 1500L
