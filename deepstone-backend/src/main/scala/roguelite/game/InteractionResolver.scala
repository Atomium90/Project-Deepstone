package roguelite.game

import roguelite.engine.{ CombatState, DialogueView, Direction, ExplorationState, GameOverState, GameState, Player, TransitionResult }

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
  * @param perkDefs
  *   Loaded run-perk catalog, keyed by id. See [[PerkLoader]]. Resolves
  *   [[roguelite.engine.Player.activePerkId]] back to its [[PerkEffect]] for the Lucky Find hook
  *   in [[handleChest]] - same role as `setDefs` for equipment set bonuses.
  */
class InteractionResolver(enemyStats: Map[String, EnemyStats],
                          itemDefs: Map[String, Item],
                          rng: Random = Random(),
                          npcDialogueDefs: Map[String, NpcDialogueDef] = Map.empty,
                          clock: () => Long = () => System.currentTimeMillis(),
                          setDefs: Map[String, SetDef] = Map.empty,
                          perkDefs: Map[String, PerkDef] = Map.empty
):

  /** The player's chosen run perk, resolved against [[perkDefs]] (mirrors
    * `CombatResolver.activePerkEffect`).
    */
  private def activePerkEffect(player: Player): Option[PerkEffect] =
    player.activePerkId.flatMap(perkDefs.get).map(_.effect)

  def interact(exp: ExplorationState, targetId: String): TransitionResult =
    exp.dungeon.currentRoom.entityById(targetId) match {
      case None =>
        TransitionResult(exp, List(s"No entity found with id '$targetId'."))

      case Some(door: Door) if door.doorKind == DoorKind.Secret && !door.revealed =>
        TransitionResult(exp, List(s"No entity found with id '$targetId'.")) // never acknowledge an unrevealed door

      case Some(door: Door) if door.doorKind == DoorKind.Trapped =>
        lift(handleTrappedDoor(exp, door))

      case Some(door: Door) =>
        liftEvents(handleDoor(exp, door))

      case Some(door: LockedDoor) =>
        liftEvents(handleLockedDoor(exp, door))

      case Some(enemy: Enemy) =>
        lift(handleEnemy(exp, enemy))

      case Some(chest: Chest) =>
        liftEvents(handleChest(exp, chest))

      case Some(shrine: Shrine) =>
        lift(handleShrine(exp, shrine))

      case Some(sanctuary: Sanctuary) =>
        liftEvents(handleSanctuary(exp, sanctuary))

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
    * place the player in front of the door they came through (see [[findSpawnPoint]]).
    * `passedRole` is the role of the door just used, or `None` for a LockedDoor, which has none. */
  private def navigateThroughDoor(exp: ExplorationState,
                                  targetRoomId: String,
                                  direction: Direction,
                                  passedRole: Option[ConnectorRole] = None
  ): (GameState, List[String], List[GameEvent]) =
    exp.dungeon.navigateTo(targetRoomId) match {
      case Left(err) => (exp, List(err), Nil)
      case Right(newDungeon) =>
        val spawnPoint =
          findSpawnPoint(newDungeon.currentRoom, direction, exp.dungeon.currentRoomId, passedRole)
        val nextState =
          exp.copy(dungeon = newDungeon, playerX = spawnPoint._1, playerY = spawnPoint._2)
        (nextState, List(s"You pass through the door heading ${direction}."), List(GameEvent.DoorOpened))
    }

  private def handleDoor(exp: ExplorationState,
                         door: Door
  ): (GameState, List[String], List[GameEvent]) =
    door.link match {
      case DoorLink.Resolved(role, _, roomId) => navigateThroughDoor(exp, roomId, door.direction, Some(role))
      case DoorLink.Unresolved(_, _) =>
        (exp, List(s"Door '${door.id}' is not connected to any room."), Nil)
    }

  /** Springs a trapped door: the player is thrown back one tile and a guardian from
    * [[availableTrapEnemies]] takes the tile they were standing on. That tile is the door's only
    * approach, so the door stays out of reach until the guardian is dealt with, with no blocking
    * state of its own: entities already block movement, and interacting needs a cardinal
    * neighbor. No fight starts by itself, the player chooses when to engage. The door becomes
    * Normal either way, so it only ever springs once.
    *
    * Both the retreat and the guardian's tile come from the player's own position, never from the
    * client's notion of the door's interact tile. If the room has no free tile to retreat to, or
    * the catalog has no eligible enemy, nothing emerges and the player stays where they are.
    */
  private def handleTrappedDoor(exp: ExplorationState, door: Door): (GameState, List[String]) =
    val room    = exp.dungeon.currentRoom
    val vacated = (exp.playerX, exp.playerY)
    val sprungRoom = room.updateEntity(door.id):
      case d: Door => d.copy(doorKind = DoorKind.Normal)
      case other   => other

    val guardianType =
      Option.when(availableTrapEnemies.nonEmpty)(availableTrapEnemies(rng.nextInt(availableTrapEnemies.size)))
    (guardianType, retreatTile(room, vacated, door.direction)) match {
      case (Some(typeId), Some((retreatX, retreatY))) =>
        val guardian =
          Enemy(id = s"${door.id}_guardian", x = vacated._1, y = vacated._2, typeId = typeId, label = enemyStats(typeId).label)
        val trappedRoom = sprungRoom.withEntities(List(guardian))
        (exp.copy(dungeon = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(trappedRoom.id, trappedRoom)),
                  playerX = retreatX,
                  playerY = retreatY
         ),
         List("A trap triggers! You are thrown back and a guardian steps in to bar the way.")
        )
      case _ =>
        (exp.copy(dungeon = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(sprungRoom.id, sprungRoom))),
         List("A trap triggers! But nothing emerges from the shadows.")
        )
    }

  /** Where a player thrown back from a door lands: one tile away from the door's wall when that
    * tile is free, otherwise the nearest free tile. `None` when the room has no free tile at all.
    */
  private def retreatTile(room: Room, from: (Int, Int), doorDirection: Direction): Option[(Int, Int)] =
    val (dx, dy) = doorDirection match {
      case Direction.Up    => (0, 1)
      case Direction.Down  => (0, -1)
      case Direction.Left  => (1, 0)
      case Direction.Right => (-1, 0)
    }
    val straightBack = (from._1 + dx, from._2 + dy)
    if room.isWalkable(straightBack._1, straightBack._2) then Some(straightBack)
    else room.nearbyFreeTiles(from._1, from._2, 1, exclude = Set(from)).headOption

  private def handleLockedDoor(exp: ExplorationState,
                               door: LockedDoor
  ): (GameState, List[String], List[GameEvent]) =
    if door.unlocked then
      navigateThroughDoor(exp, door.targetRoomId, door.direction)
    else
      exp.player.keyCounts.find { case (kind, count) => count > 0 && KeyKind.canUnlock(kind, door) } match {
        case None =>
          (exp, List("This door is locked. You need a key."), Nil)
        case Some((kind, count)) =>
          val updatedPlayer = exp.player.copy(keyCounts = exp.player.keyCounts.updated(kind, count - 1))
          val unlockedRoom = exp.dungeon.currentRoom.updateEntity(door.id):
            case d: LockedDoor => d.copy(unlocked = true)
            case o             => o
          val updatedDungeon =
            exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(unlockedRoom.id, unlockedRoom))
          val (state, navLog, _) = navigateThroughDoor(
            exp.copy(dungeon = updatedDungeon, player = updatedPlayer),
            door.targetRoomId,
            door.direction
          )
          (state, "You use a key to unlock the door." :: navLog, List(GameEvent.DoorUnlockedWithKey))
      }

  private def handleEnemy(exp: ExplorationState, enemy: Enemy): (GameState, List[String]) =
    enemyStats.get(enemy.typeId) match {
      case None =>
        (exp, List(s"Unknown enemy type '${enemy.typeId}' — cannot start combat."))
      case Some(stats) =>
        val instance = EnemyInstance.fromStats(enemy.id, stats, exp.difficulty, enemy.isElite)
        val combat   = Combat(enemy = instance)
        val nextState = CombatState(exp.player,
                                    exp.dungeon,
                                    exp.playerX,
                                    exp.playerY,
                                    combat,
                                    enemy.id,
                                    exp.difficulty,
                                    enemyStats = enemyStats
        )
        (nextState, List(s"You engage the ${stats.label}!"))
    }

  /** Opens, re-opens or springs a chest, depending on its [[ChestState]]. A chest stays on the map
    * in every state, only its state changes:
    *   - a closed trapped chest springs (see [[springChestTrap]]);
    *   - any other closed chest rolls its item (see [[openChest]]);
    *   - an open-full chest, whose item the player has not taken yet, offers it again;
    *   - an open-empty or sprung chest has nothing left to give.
    */
  private def handleChest(exp: ExplorationState,
                          chest: Chest
  ): (GameState, List[String], List[GameEvent]) =
    chest.state match {
      case ChestState.Closed if chest.trapped => springChestTrap(exp, chest)
      case ChestState.Closed                  => openChest(exp, chest)
      case ChestState.OpenFull =>
        chest.contents match {
          case Some(item) => takeFromChest(exp, chest, item, "You go back to the chest and find")
          case None       => (replaceChest(exp, chest.emptied), List("The chest is empty."), Nil)
        }
      case ChestState.OpenEmpty | ChestState.Sprung => (exp, List("The chest is empty."), Nil)
    }

  /** The trap goes off: the chest becomes [[ChestState.Sprung]] and enemies appear around it instead
    * of any loot.
    */
  private def springChestTrap(exp: ExplorationState,
                              chest: Chest
  ): (GameState, List[String], List[GameEvent]) =
    val sprungRoom = exp.dungeon.currentRoom.updateEntity(chest.id) {
      case c: Chest => c.copy(state = ChestState.Sprung)
      case other    => other
    }
    val (trappedRoom, trapLog) = spawnTrapEnemies(sprungRoom, chest, exp.playerX, exp.playerY)
    (withRoom(exp, trappedRoom), trapLog, Nil)

  /** First opening of an untrapped chest: rolls its item, then offers it exactly as every later
    * attempt to take it does (see [[takeFromChest]]). A roll that finds nothing leaves the chest
    * open and empty.
    */
  private def openChest(exp: ExplorationState, chest: Chest): (GameState, List[String], List[GameEvent]) =
    // Lucky Find: raise the roll's floor for this one chest, then consume the perk regardless of
    // what the roll actually lands on - it's a one-shot boost to the roll, not a standing floor.
    // The Rarity Insight upgrade (Player.chestRarityFloor) is the opposite: a permanent floor on
    // every chest, never consumed - the two combine by taking whichever floor is stronger. Both only
    // ever apply here, since the item is rolled once, on the first opening.
    val perkFloor = activePerkEffect(exp.player) match {
      case Some(PerkEffect.GuaranteedRarityFirstChest(minRarity)) if !exp.player.firstChestBonusUsed =>
        Some(minRarity)
      case _ => None
    }
    val rarityFloorOverride = List(perkFloor, exp.player.chestRarityFloor).flatten.maxByOption(_.ordinal)
    val opened =
      if perkFloor.isDefined then exp.copy(player = exp.player.copy(firstChestBonusUsed = true)) else exp

    LootTable.rollChest(itemDefs, rng, exp.difficulty, rarityFloorOverride) match {
      case None =>
        (replaceChest(opened, chest.emptied), List("You open the chest. It's empty."), Nil)
      case Some(item) =>
        takeFromChest(opened,
                      chest.copy(state = ChestState.OpenFull, contents = Some(item)),
                      item,
                      "You open the chest and find"
        )
    }

  /** Offers `item`, which `chest` holds, to the player. `chest` must already be open-full with
    * `item` as its contents. The chest empties once the item is actually taken: right away for an
    * auto-equip or a key, and later, through the pending choice, if the player picks it over what
    * they had (see [[PendingEquipChoice.sourceChestId]]). A pending choice the player declines leaves
    * the chest full, so the item can be taken again later. A discarded duplicate (the player already
    * has an equal or better copy) empties it too: nothing in it is worth coming back for. `lead`
    * opens every log line.
    */
  private def takeFromChest(exp: ExplorationState,
                            chest: Chest,
                            item: Item,
                            lead: String
  ): (GameState, List[String], List[GameEvent]) =
    def taken(p: Player): (GameState, List[String], List[GameEvent]) =
      (replaceChest(exp.copy(player = p), chest.emptied),
       List(s"$lead ${item.name}! (${item.statLine})"),
       List(GameEvent.itemPickedUp(p, item, setDefs))
      )

    EquipmentResolver.resolvePickup(exp.player, item, setDefs) match {
      case PickupOutcome.Equipped(p)     => taken(p)
      case PickupOutcome.KeyCollected(p) => taken(p)

      case PickupOutcome.ChoicePending(pending) =>
        (replaceChest(exp, chest).copy(pendingEquipChoice = Some(pending.copy(sourceChestId = Some(chest.id)))),
         List(s"$lead ${item.name}. Choose what to do with it."),
         Nil
        )

      case PickupOutcome.Discarded(p) =>
        (replaceChest(exp.copy(player = p), chest.emptied),
         List(s"$lead ${item.name}, but you already have a better one."),
         Nil
        )
    }

  /** `exp` with `room` swapped in for the room of the same id. */
  private def withRoom(exp: ExplorationState, room: Room): ExplorationState =
    exp.copy(dungeon = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(room.id, room)))

  /** `exp` with the current room's chest of the same id replaced by `chest`. */
  private def replaceChest(exp: ExplorationState, chest: Chest): ExplorationState =
    withRoom(exp, exp.dungeon.currentRoom.updateEntity(chest.id) {
      case _: Chest => chest
      case other    => other
    })

  /** Rolls 3 candidates and removes the Shrine from the room (one-shot) - the actual pick is resolved later by [[RewardChoiceResolver]] once the player
    * sends a `RewardChoice` action.
    */
  private def handleShrine(exp: ExplorationState, shrine: Shrine): (GameState, List[String]) =
    val roomWithoutShrine = exp.dungeon.currentRoom.removeEntity(shrine.id)
    val updatedDungeon    = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(roomWithoutShrine.id, roomWithoutShrine))
    val options           = LootTable.rollShrineChoices(itemDefs, rng, difficulty = exp.difficulty)
    val nextState = exp.copy(dungeon = updatedDungeon,
                             pendingRewardChoice = Some(PendingRewardChoice(options))
    )
    (nextState, List("The shrine offers you a choice."))

  /** The dungeon's one true ending: transitions straight to `GameOverState(victory = true)` and
    * emits `RunEnded`, exactly what a boss kill used to do directly in `CombatResolver.victory`
    * before that responsibility moved here - see [[roguelite.game.Sanctuary]]'s own doc for why the
    * win is deliberately gated behind an explicit Interact rather than firing on the killing blow.
    */
  private def handleSanctuary(exp: ExplorationState, sanctuary: Sanctuary): (GameState, List[String], List[GameEvent]) =
    (GameOverState(exp.player, victory = true),
     List("You step into the sanctuary. Your journey ends here - victory is yours."),
     List(GameEvent.RunEnded(victory = true, difficulty = exp.difficulty, activePerkId = exp.player.activePerkId))
    )

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

  /** Where the player appears in the target room: in front of the door they came through, whatever
    * the room's wall thickness or layout.
    *
    * That door is, in order of preference: a door leading back to `originRoomId` (a Door whose link
    * points there, or a LockedDoor targeting it - this covers going back, a fork's two exits, and the
    * Vault's return door); else the door with the role opposite to the one just used, `passedRole`
    * (this covers the room after a fork, whose single Prev door only points at one of the two
    * branches). With no such door the position falls back to a fixed spot on the wall opposite the
    * direction of travel, which assumes a one-tile wall.
    *
    * Whichever it is, an occupied or blocked spot is replaced by the nearest free tile, and only a
    * room with no free tile at all falls back to (1,1).
    */
  private def findSpawnPoint(room: Room,
                             fromDirection: Direction,
                             originRoomId: String,
                             passedRole: Option[ConnectorRole]
  ): (Int, Int) =
    val preferred = arrivalDoor(room, originRoomId, passedRole)
      .flatMap(approachTile(room, _))
      .getOrElse(fixedSpawnSpot(room, fromDirection))
    room.nearbyFreeTiles(preferred._1, preferred._2, 1).headOption.getOrElse((1, 1))

  /** The door of `room` the player just came through, see [[findSpawnPoint]]. */
  private def arrivalDoor(room: Room, originRoomId: String, passedRole: Option[ConnectorRole]): Option[Entity] =
    val leadingBack = room.entities.find {
      case d: Door =>
        d.link match {
          case DoorLink.Resolved(_, _, target) => target == originRoomId
          case DoorLink.Unresolved(_, _)       => false
        }
      case d: LockedDoor => d.targetRoomId == originRoomId
      case _             => false
    }
    leadingBack.orElse {
      passedRole.flatMap { role =>
        room.entities.find {
          case d: Door => d.link.role == role.opposite
          case _       => false
        }
      }
    }

  /** The first floor tile stepping inward from a door's anchor, which is the tile in front of it:
    * one step for a door on the wall gap, two for one set into a thicker wall. Looks up to 3 tiles
    * deep. `None` for an entity that is not a door, or a door with no floor in front of it. */
  private def approachTile(room: Room, door: Entity): Option[(Int, Int)] =
    val direction = door match {
      case d: Door       => Some(d.direction)
      case d: LockedDoor => Some(d.direction)
      case _             => None
    }
    direction.flatMap { dir =>
      val (dx, dy) = dir match {
        case Direction.Up    => (0, 1)
        case Direction.Down  => (0, -1)
        case Direction.Left  => (1, 0)
        case Direction.Right => (-1, 0)
      }
      (1 to 3).map(k => (door.x + k * dx, door.y + k * dy)).find {
        case (x, y) => room.tileAt(x, y) == Tile.Floor
      }
    }

  /** Fixed spot on the wall opposite the direction of travel, used when the room has no door to
    * appear in front of. Entering through a DOWN door means the player came from below, so they
    * appear near the top of the new room. */
  private def fixedSpawnSpot(room: Room, fromDirection: Direction): (Int, Int) =
    fromDirection match {
      case Direction.Down  => (room.width / 2, 1)
      case Direction.Up    => (room.width / 2, room.height - 2)
      case Direction.Right => (1, room.height / 2)
      case Direction.Left  => (room.width - 2, room.height / 2)
    }

  /** Non-boss enemy typeIds eligible to spawn from a trap (a trapped chest's ambush or a trapped
    * door's guardian). Deliberately excludes boss-tier enemies (roughly half the roster) so a trap
    * never confronts the player with a full boss encounter.
    */
  private val TrapEnemyPool =
    List("goblin", "orc", "skeleton", "cave_troll", "bandit", "dire_wolf", "cultist")

  /** The [[TrapEnemyPool]] entries actually present in the loaded enemy catalog. */
  private val availableTrapEnemies: List[String] = TrapEnemyPool.filter(enemyStats.contains)

  /** Spawn 1-2 enemies from [[TrapEnemyPool]] on free tiles near the chest, avoiding the player's
    * own tile. Falls back to fewer enemies (or none) if the room has no space.
    */
  private def spawnTrapEnemies(room: Room,
                               chest: Chest,
                               playerX: Int,
                               playerY: Int
  ): (Room, List[String]) =
    val pool = availableTrapEnemies
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
