package roguelite.game.dungeon

import roguelite.engine.{ CombatState, Direction, ExplorationState, GameOverState, GameState, Player, TransitionResult }
import roguelite.game.combat.{ Combat, EnemyInstance, EnemyStats }
import roguelite.game.core.{ GameEvent, LogLine }
import roguelite.game.items.{ EquipmentResolver, Item, KeyKind, LootTable, PendingRewardChoice, PickupOutcome, SetDef }
import roguelite.game.progression.{ PerkDef, PerkEffect }

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
  * @param perkDefs
  *   Loaded run-perk catalog, keyed by id. See [[PerkLoader]]. Resolves
  *   [[roguelite.engine.Player.activePerkId]] back to its [[PerkEffect]] for the Lucky Find hook
  *   in [[handleChest]] - same role as `setDefs` for equipment set bonuses.
  */
class InteractionResolver(enemyStats: Map[String, EnemyStats],
                          itemDefs: Map[String, Item],
                          rng: Random = Random(),
                          setDefs: Map[String, SetDef] = Map.empty,
                          perkDefs: Map[String, PerkDef] = Map.empty
):

  /** The effects of the run perks the player took, resolved against [[perkDefs]] (mirrors
    * `CombatResolver.activePerkEffects`).
    */
  private def activePerkEffects(player: Player): List[PerkEffect] = PerkDef.activeEffects(player, perkDefs)

  def interact(exp: ExplorationState, targetId: String): TransitionResult =
    exp.dungeon.currentRoom.entityById(targetId) match {
      case None =>
        TransitionResult(exp, List(LogLine.of("log.interact.noEntity", "id" -> targetId)))

      case Some(door: Door) if door.doorKind == DoorKind.Secret && !door.revealed =>
        TransitionResult(exp, List(LogLine.of("log.interact.noEntity", "id" -> targetId))) // never acknowledge an unrevealed door

      case Some(door: Door) if isSealedByBoss(exp.dungeon.currentRoom, door) =>
        TransitionResult(exp, List(InteractionResolver.BossExitSealedLine))

      case Some(door: Door) if isSealedBehindFork(exp.dungeon, door) =>
        TransitionResult(exp, List(InteractionResolver.ForkExitSealedLine))

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

      case Some(_: Npc) =>
        // What an NPC says depends on what the story remembers, which this pure resolver does not
        // see: GameSession opens the conversation before an Interact on an NPC gets here.
        TransitionResult(exp, Nil)
    }

  /** Lifts a handler that reports no events into the richer return type `interact`
    * needs, so every existing per-entity handler below can stay untouched. */
  private def lift(r: (GameState, List[LogLine])): TransitionResult = TransitionResult(r._1, r._2)

  /** Same as [[lift]], for handlers that also report [[GameEvent]]s. */
  private def liftEvents(r: (GameState, List[LogLine], List[GameEvent])): TransitionResult =
    TransitionResult(r._1, r._2, events = r._3)

  /** The way on out of a Boss room stays shut while an enemy of that room lives, so a Boss cannot be
    * walked past. The way back (a Prev door) is never shut. Killing the Boss removes it from the room
    * (see [[CombatResolver]]), which is what opens the door: no state of its own. A Boss room is told
    * by its type, as everywhere else, there is no per-enemy Boss flag.
    */
  private def isSealedByBoss(room: Room, door: Door): Boolean =
    room.roomType == RoomType.Boss
      && door.link.role == ConnectorRole.Next
      && room.entities.exists(_.isInstanceOf[Enemy])

  /** A fork is a choice: once the player is in a branch, the way back to the Fork room is shut, so
    * the other branch cannot be reached any more. Only that one door is: from the room where the
    * branches meet the player can still walk back along the branch they took, so nothing in it is
    * lost, and the Fork room's own way back to the rooms before it stays open. Told by what the door
    * leads to (a Fork room), never by an authored flag, since the same room is the head of a branch
    * in one run and a lead-in room in another. No state of its own, like [[isSealedByBoss]].
    */
  private def isSealedBehindFork(dungeon: Dungeon, door: Door): Boolean =
    door.link match
      case DoorLink.Resolved(ConnectorRole.Prev, branch, linked) =>
        dungeon.rooms.get(doorTarget(dungeon, ConnectorRole.Prev, branch, linked)).exists(_.roomType == RoomType.Fork)
      case _ => false

  /** Shared by a normal Door and an already-unlocked LockedDoor: navigate to the target room and
    * place the player in front of the door they came through (see [[findSpawnPoint]]).
    * `passedRole` is the role of the door just used, or `None` for a LockedDoor, which has none. */
  private def navigateThroughDoor(exp: ExplorationState,
                                  targetRoomId: String,
                                  direction: Direction,
                                  passedRole: Option[ConnectorRole] = None
  ): (GameState, List[LogLine], List[GameEvent]) =
    exp.dungeon.navigateTo(targetRoomId, forward = passedRole.contains(ConnectorRole.Next)) match {
      case Left(err) => (exp, List(LogLine.raw(err)), Nil)
      case Right(newDungeon) =>
        val spawnPoint =
          findSpawnPoint(newDungeon.currentRoom, direction, exp.dungeon.currentRoomId, passedRole)
        val nextState =
          exp.copy(dungeon = newDungeon, playerX = spawnPoint._1, playerY = spawnPoint._2)
        (nextState, List(LogLine.of("log.door.pass", "direction" -> direction.toString.toUpperCase)), List(GameEvent.DoorOpened))
    }

  private def handleDoor(exp: ExplorationState,
                         door: Door
  ): (GameState, List[LogLine], List[GameEvent]) =
    door.link match {
      case DoorLink.Resolved(role, branch, roomId) =>
        navigateThroughDoor(exp, doorTarget(exp.dungeon, role, branch, roomId), door.direction, Some(role))
      case DoorLink.Unresolved(_, _) =>
        (exp, List(LogLine.of("log.door.notConnected", "id" -> door.id)), Nil)
    }

  /** The room a door leads to. An untagged Prev door points at the one room the wiring chose, which
    * for the room after a fork is the end of a single branch whichever branch the player took, so
    * it follows the room they actually came from. A tagged door already names its branch and keeps
    * its link, as does any room entered without going forward (nothing recorded in
    * [[Dungeon.cameFrom]]).
    */
  private def doorTarget(dungeon: Dungeon, role: ConnectorRole, branch: Option[String], linked: String): String =
    if role == ConnectorRole.Prev && branch.isEmpty
    then dungeon.cameFrom.getOrElse(dungeon.currentRoomId, linked)
    else linked

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
  private def handleTrappedDoor(exp: ExplorationState, door: Door): (GameState, List[LogLine]) =
    val room    = exp.dungeon.currentRoom
    val vacated = (exp.playerX, exp.playerY)
    val sprungRoom = room.updateEntity(door.id):
      case d: Door => d.copy(doorKind = DoorKind.Normal)
      case other   => other

    val guardianType =
      Option.when(!exp.trapsInert && availableTrapEnemies.nonEmpty)(
        availableTrapEnemies(rng.nextInt(availableTrapEnemies.size))
      )
    (guardianType, retreatTile(room, vacated, door.direction)) match {
      case (Some(typeId), Some((retreatX, retreatY))) =>
        val guardian =
          Enemy(id = s"${door.id}_guardian", x = vacated._1, y = vacated._2, typeId = typeId, label = enemyStats(typeId).label)
        val trappedRoom = sprungRoom.withEntities(List(guardian))
        (exp.copy(dungeon = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(trappedRoom.id, trappedRoom)),
                  playerX = retreatX,
                  playerY = retreatY
         ),
         List(LogLine.of("log.trap.door"))
        )
      case _ =>
        (exp.copy(dungeon = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(sprungRoom.id, sprungRoom))),
         List(LogLine.of("log.trap.doorEmpty"))
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
  ): (GameState, List[LogLine], List[GameEvent]) =
    if door.unlocked then
      navigateThroughDoor(exp, door.targetRoomId, door.direction)
    else
      exp.player.keyCounts.find { case (kind, count) => count > 0 && KeyKind.canUnlock(kind, door) } match {
        case None =>
          (exp, List(LogLine.of("log.door.locked")), Nil)
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
          (state, LogLine.of("log.door.unlock") :: navLog, List(GameEvent.DoorUnlockedWithKey))
      }

  private def handleEnemy(exp: ExplorationState, enemy: Enemy): (GameState, List[LogLine]) =
    enemyStats.get(enemy.typeId) match {
      case None =>
        (exp, List(LogLine.of("log.combat.unknownEnemy", "type" -> enemy.typeId)))
      case Some(stats) =>
        val perkPercent = activePerkEffects(exp.player).collect { case PerkEffect.EnemyStatPercent(n) => n }.sum
        val instance    = EnemyInstance.fromStats(enemy.id, stats, exp.difficulty, enemy.isElite, perkPercent)
        val combat   = Combat(enemy = instance)
        val nextState = CombatState(exp.player,
                                    exp.dungeon,
                                    exp.playerX,
                                    exp.playerY,
                                    combat,
                                    enemy.id,
                                    exp.difficulty,
                                    enemyStats = enemyStats,
                                    altarShut = exp.altarShut
        )
        (nextState, List(LogLine.of("log.combat.engage", "enemy" -> enemy.typeId)))
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
  ): (GameState, List[LogLine], List[GameEvent]) =
    chest.state match {
      case ChestState.Closed if chest.trapped => springChestTrap(exp, chest)
      case ChestState.Closed                  => openChest(exp, chest)
      case ChestState.OpenFull =>
        chest.contents match {
          case Some(item) => takeFromChest(exp, chest, item, "log.chest.foundBack")
          case None       => (replaceChest(exp, chest.emptied), List(LogLine.of("log.chest.empty")), Nil)
        }
      case ChestState.OpenEmpty | ChestState.Sprung => (exp, List(LogLine.of("log.chest.empty")), Nil)
    }

  /** The trap goes off: the chest becomes [[ChestState.Sprung]] and enemies appear around it instead
    * of any loot.
    */
  private def springChestTrap(exp: ExplorationState,
                              chest: Chest
  ): (GameState, List[LogLine], List[GameEvent]) =
    val sprungRoom = exp.dungeon.currentRoom.updateEntity(chest.id) {
      case c: Chest => c.copy(state = ChestState.Sprung)
      case other    => other
    }
    val (trappedRoom, trapLog) = spawnTrapEnemies(sprungRoom, chest, exp.playerX, exp.playerY, exp.trapsInert)
    (withRoom(exp, trappedRoom), trapLog, Nil)

  /** First opening of an untrapped chest: rolls its item, then offers it exactly as every later
    * attempt to take it does (see [[takeFromChest]]). A roll that finds nothing leaves the chest
    * open and empty.
    */
  private def openChest(exp: ExplorationState, chest: Chest): (GameState, List[LogLine], List[GameEvent]) =
    // Lucky Find: raise the roll's floor for this one chest, then consume the perk regardless of
    // what the roll actually lands on - it's a one-shot boost to the roll, not a standing floor.
    // The Rarity Insight upgrade (Player.chestRarityFloor) is the opposite: a permanent floor on
    // every chest, never consumed - the two combine by taking whichever floor is stronger. Both only
    // ever apply here, since the item is rolled once, on the first opening.
    val perkFloor = activePerkEffects(exp.player).collectFirst {
      case PerkEffect.GuaranteedRarityFirstChest(minRarity) if !exp.player.firstChestBonusUsed => minRarity
    }
    val rarityFloorOverride = List(perkFloor, exp.player.chestRarityFloor).flatten.maxByOption(_.ordinal)
    val opened =
      if perkFloor.isDefined then exp.copy(player = exp.player.copy(firstChestBonusUsed = true)) else exp

    LootTable.rollChest(itemDefs, rng, exp.difficulty, rarityFloorOverride, exp.player.affinityTags) match {
      case None =>
        (replaceChest(opened, chest.emptied), List(LogLine.of("log.chest.openEmpty")), Nil)
      case Some(item) =>
        takeFromChest(opened,
                      chest.copy(state = ChestState.OpenFull, contents = Some(item)),
                      item,
                      "log.chest.found"
        )
    }

  /** Offers `item`, which `chest` holds, to the player. `chest` must already be open-full with
    * `item` as its contents. The chest empties once the item is actually taken: right away for an
    * auto-equip or a key, and later, through the pending choice, if the player picks it over what
    * they had (see [[PendingEquipChoice.sourceChestId]]). A pending choice the player declines leaves
    * the chest full, so the item can be taken again later. A discarded duplicate (the player already
    * has an equal or better copy) empties it too: nothing in it is worth coming back for.
    * `leadKey` is the log key of the line for a plain pickup (`log.chest.found` on the first
    * opening, `log.chest.foundBack` when the player comes back for it); the two other outcomes add
    * `Choice` or `Worse` to it.
    */
  private def takeFromChest(exp: ExplorationState,
                            chest: Chest,
                            item: Item,
                            leadKey: String
  ): (GameState, List[LogLine], List[GameEvent]) =
    def taken(p: Player): (GameState, List[LogLine], List[GameEvent]) =
      (replaceChest(exp.copy(player = p), chest.emptied),
       List(LogLine.of(leadKey, "item" -> item.typeId)),
       List(GameEvent.itemPickedUp(p, item, setDefs))
      )

    EquipmentResolver.resolvePickup(exp.player, item, setDefs) match {
      case PickupOutcome.Equipped(p)     => taken(p)
      case PickupOutcome.KeyCollected(p) => taken(p)

      case PickupOutcome.ChoicePending(pending) =>
        (replaceChest(exp, chest).copy(pendingEquipChoice = Some(pending.copy(sourceChestId = Some(chest.id)))),
         List(LogLine.of(leadKey + "Choice", "item" -> item.typeId)),
         Nil
        )

      case PickupOutcome.Discarded(p) =>
        (replaceChest(exp.copy(player = p), chest.emptied),
         List(LogLine.of(leadKey + "Worse", "item" -> item.typeId)),
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
  private def handleShrine(exp: ExplorationState, shrine: Shrine): (GameState, List[LogLine]) =
    val roomWithoutShrine = exp.dungeon.currentRoom.removeEntity(shrine.id)
    val updatedDungeon    = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(roomWithoutShrine.id, roomWithoutShrine))
    val options           = LootTable.rollShrineChoices(itemDefs, rng, difficulty = exp.difficulty, affinityTags = exp.player.affinityTags)
    val nextState = exp.copy(dungeon = updatedDungeon,
                             pendingRewardChoice = Some(PendingRewardChoice(options, shrine.devoted))
    )
    (nextState, List(LogLine.of("log.shrine.offer")))

  /** The dungeon's one true ending: transitions straight to `GameOverState(victory = true)` and
    * emits `RunEnded`, exactly what a boss kill used to do directly in `CombatResolver.victory`
    * before that responsibility moved here - see [[roguelite.game.dungeon.Sanctuary]]'s own doc for why the
    * win is deliberately gated behind an explicit Interact rather than firing on the killing blow.
    *
    * Once the story has come to the light (see [[ExplorationState.endingOpen]]) the run does not end
    * here: the player is asked what to do with the light first, and the run ends with the answer.
    */
  private def handleSanctuary(exp: ExplorationState, sanctuary: Sanctuary): (GameState, List[LogLine], List[GameEvent]) =
    if exp.endingOpen then (exp.copy(pendingEndingChoice = true), List(LogLine.of("log.sanctuary.waits")), Nil)
    else
      val (paid, bonusLog) = winBonus(exp)
      (GameOverState(paid, victory = true),
       LogLine.of("log.sanctuary.victory") :: bonusLog,
       List(
         GameEvent.RunEnded(victory = true,
                            difficulty = exp.difficulty,
                            activePerkId = exp.player.activePerkId,
                            riskyPerkId = exp.player.activeRiskyPerkId
         )
       )
      )

  /** The player with the Shards their risky perk pays for a won run, and the line that tells it. Nothing
    * for a run that takes no blessing or once the altar is shut, like the Shards of a kill.
    */
  private def winBonus(exp: ExplorationState): (Player, List[LogLine]) =
    val bonus = PerkDef.winBonus(exp.player, perkDefs, exp.difficulty.usesBlessings, exp.altarShut)
    if bonus <= 0 then (exp.player, Nil)
    else (exp.player.copy(metaCurrency = exp.player.metaCurrency + bonus), List(LogLine.of("log.run.victoryBonus", "count" -> bonus)))

  /** Reveal any hidden secret door within Chebyshev distance 1 of (x, y). Called from
    * [[roguelite.engine.StateMachine]]'s Move handling, not Interact (same family of "entities
    * reacting to the player" logic, so it lives here rather than splitting door logic across two
    * classes). */
  def revealSecretDoors(room: Room, x: Int, y: Int): (Room, List[LogLine], List[GameEvent]) =
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
      (updated, List(LogLine.of("log.door.secret")), List(GameEvent.SecretDoorRevealed))

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

  /** Where a run begins: in front of the entrance door of the dungeon's first room, the Prev door
    * that leads nowhere because nothing comes before it, whatever the room's wall thickness. A first
    * room with no such door begins near the top of the room. Either way an occupied or blocked spot
    * is replaced by the nearest free tile, see [[findSpawnPoint]].
    */
  def startSpawnPoint(room: Room): (Int, Int) =
    findSpawnPoint(room, Direction.Down, originRoomId = "", passedRole = Some(ConnectorRole.Next))

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
    * own tile. Falls back to fewer enemies (or none) if the room has no space, and spawns none
    * when the trap is `inert`.
    */
  private def spawnTrapEnemies(room: Room,
                               chest: Chest,
                               playerX: Int,
                               playerY: Int,
                               inert: Boolean
  ): (Room, List[LogLine]) =
    val pool = availableTrapEnemies
    if inert || pool.isEmpty then (room, List(LogLine.of("log.trap.chestEmpty")))
    else
      val count   = rng.nextInt(2) + 1
      val typeIds = List.fill(count)(pool(rng.nextInt(pool.size)))
      val spots   = room.nearbyFreeTiles(chest.x, chest.y, count, exclude = Set((playerX, playerY)))

      val newEnemies = typeIds.zip(spots).zipWithIndex.map {
        case ((typeId, (x, y)), i) =>
          Enemy(id = s"${chest.id}_trap_$i", x = x, y = y, typeId = typeId, label = enemyStats(typeId).label)
      }

      (room.withEntities(newEnemies), List(LogLine.of("log.trap.chest")))

object InteractionResolver:

  /** What the player reads on trying the way on out of a Boss room while its guardian still stands. */
  val BossExitSealedLine: LogLine = LogLine.of("log.door.bossSealed")

  /** What the way back to a Fork room says: the branch the player took is a choice with no return. */
  val ForkExitSealedLine: LogLine = LogLine.of("log.door.forkSealed")
