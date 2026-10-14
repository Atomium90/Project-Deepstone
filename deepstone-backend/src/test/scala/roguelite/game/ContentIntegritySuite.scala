package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.{ ClassId, Difficulty, Direction, ExplorationState, PlayerFixtures }

import scala.util.Random

/** Cross-validates the loaded content files against each other - the individual loaders only
  * check their own file is well-formed, none of them check that a typeId one file references
  * (an enemy's lootTable, a class's startingKit) actually exists in items.json. A typo here
  * doesn't crash anything (LootTable/EquipmentResolver treat a missing typeId as "skip it"), it
  * just silently makes something undroppable or un-obtainable - exactly the class of mistake a
  * large loot-pool rewrite is prone to.
  */
class ContentIntegritySuite extends CatsEffectSuite:

  test("every enemy lootTable typeId exists in items.json"):
    for
      items <- ItemLoader.loadAll()
      enemies <- EnemyLoader.loadAll()
    yield enemies.values.foreach:
      enemy =>
        enemy.lootTable.foreach:
          entry =>
            assert(items.contains(entry.typeId),
                   s"${enemy.typeId}'s lootTable references unknown typeId '${entry.typeId}'"
            )

  test("every class startingKit typeId exists in items.json"):
    for
      items   <- ItemLoader.loadAll()
      classes <- ClassLoader.loadAll()
    yield classes.values.foreach:
      cd =>
        cd.startingKit.foreach:
          typeId =>
            assert(items.contains(typeId),
                   s"${cd.classId} startingKit references unknown typeId '$typeId'"
            )

  // Whether rollChest can return None depends only on whether its (ChestPool ∩ items.json) pool is
  // non-empty - a static fact about the catalog, not something that varies by RNG draw (pickWeighted
  // always succeeds once the pool's total weight is positive). One seeded call proves it as well as
  // 200 would.
  test("rollChest never returns None against the real item catalog"):
    for items <- ItemLoader.loadAll()
    yield assert(LootTable.rollChest(items, Random(0), Difficulty.Normal).isDefined,
                 "rollChest returned None - a ChestPool typeId is likely missing from items.json"
    )

  // Same reasoning: with dropChance forced to 100, whether rollEnemy can produce a drop depends
  // only on whether the enemy's own lootTable pool is non-empty against items.json - static per
  // enemy, not RNG-dependent. One seeded call per enemy proves it as well as 50 would.
  test("every enemy with a positive dropChance can produce a drop against the real item catalog"):
    for
      items   <- ItemLoader.loadAll()
      enemies <- EnemyLoader.loadAll()
    yield enemies.values.filter(_.lootTable.nonEmpty).foreach:
      stats =>
        val instance   = EnemyInstance.fromStats("e1", stats, Difficulty.Normal)
        val forcedDrop = instance.copy(dropChance = 100)
        assert(LootTable.rollEnemy(forcedDrop, items, Random(0), Difficulty.Normal).isDefined,
               s"${stats.typeId}'s lootTable never produced a drop - likely all typeIds are missing from items.json"
        )

  test("every item's setId, when present, exists in sets.json"):
    for
      items <- ItemLoader.loadAll()
      sets  <- SetLoader.loadAll()
    yield items.values.foreach:
      item =>
        val setId = item match {
          case w: Weapon    => w.setId
          case a: Armor     => a.setId
          case a: Accessory => a.setId
          case _            => None
        }
        setId.foreach(id => assert(sets.contains(id), s"${item.typeId} references unknown setId '$id'"))

  test("every enemy typeId placed in rooms.json exists in enemies.json"):
    for
      enemies <- EnemyLoader.loadAll()
      rooms   <- RoomLoader.loadAll()
    yield rooms.values.foreach:
      room =>
        room.entities.collect { case e: Enemy => e }.foreach:
          e =>
            assert(enemies.contains(e.typeId),
                   s"room '${room.id}' places enemy '${e.id}' with unknown typeId '${e.typeId}'"
            )

  test("every enemy type of a Boss room is a Devoted, and no other room places one"):
    for
      enemies <- EnemyLoader.loadAll()
      rooms   <- RoomLoader.loadAll()
    yield
      def typesIn(rooms: Iterable[Room]): Set[String] =
        rooms.flatMap(_.entities.collect { case e: Enemy => e.typeId }).toSet
      val (bossRooms, otherRooms) = rooms.values.partition(_.roomType == RoomType.Boss)
      typesIn(bossRooms).foreach(typeId => assert(enemies(typeId).devoted.isDefined, s"'$typeId' stands in a Boss room but is no Devoted"))
      typesIn(otherRooms).foreach(typeId => assertEquals(enemies(typeId).devoted, None, s"Devoted '$typeId' stands in a room that is not a Boss room"))

  test("every npc id placed in rooms.json is the npc of a scene in story.json"):
    for
      scenes <- StoryLoader.loadAll()
      rooms  <- RoomLoader.loadAll()
    yield
      val storyNpcs = scenes.values.map(_.npc).toSet
      rooms.values.foreach:
        room =>
          room.entities.collect { case n: Npc => n }.foreach:
            n =>
              assert(storyNpcs.contains(n.id), s"room '${room.id}' places npc '${n.id}', which no scene in story.json belongs to")

  test("every NPC of the story catalog has an entry in npcs.json, with a sprite"):
    for
      scenes <- StoryLoader.loadAll()
      npcs   <- NpcLoader.loadAll()
    yield scenes.values.map(_.npc).toSet.foreach:
      npc =>
        assert(npcs.contains(npc), s"story.json has scenes for '$npc', which npcs.json does not define")
        assert(npcs(npc).spriteId.nonEmpty, s"'$npc' has no sprite")

  test("the sage, the executioner and the one who looks for a Devoted can take the player's place, nobody else"):
    for npcs <- NpcLoader.loadAll()
    yield assertEquals(npcs.values.filter(_.candidate).map(_.id).toSet, Set("npc.2", "npc.3", "npc.5"))

  test("only the adventurer has something to say about the ending, and he cannot take the player's place"):
    for npcs <- NpcLoader.loadAll()
    yield
      assertEquals(npcs.values.filter(_.epilogue).map(_.id).toSet, Set("npc.4"))
      assertEquals(npcs("npc.4").candidate, false)

  test("every set has exactly 4 pieces: 1 weapon, 1 armor, 2 accessories"):
    for
      items <- ItemLoader.loadAll()
      sets  <- SetLoader.loadAll()
    yield
      val bySet = items.values.collect {
        case w: Weapon if w.setId.isDefined    => w.setId.get -> w.kind
        case a: Armor if a.setId.isDefined     => a.setId.get -> a.kind
        case a: Accessory if a.setId.isDefined => a.setId.get -> a.kind
      }.groupBy(_._1).view.mapValues(_.map(_._2).toList).toMap

      sets.keys.foreach:
        setId =>
          val kinds = bySet.getOrElse(setId, Nil)
          assertEquals(kinds.count(_ == "weapon"), 1, s"$setId should have exactly 1 weapon, found $kinds")
          assertEquals(kinds.count(_ == "armor"), 1, s"$setId should have exactly 1 armor, found $kinds")
          assertEquals(kinds.count(_ == "accessory"), 2, s"$setId should have exactly 2 accessories, found $kinds")
          assertEquals(kinds.size, 4, s"$setId should have exactly 4 pieces total, found $kinds")

  // A door still Unresolved once a dungeon is built is a dead end in game ("Door '...' is not
  // connected to any room"). Content authored with a wiring detail the builder doesn't honor, like
  // a Fork room whose exits carry tags it can't match, only shows up this way: the loader and the
  // builder each look fine on their own. So this builds real dungeons from the real room pool, over
  // many seeds and every difficulty so each theme and fork shape gets drawn, and checks that every
  // door of every room that ended up in them is connected. It also checks that the forks of every
  // theme that has any were actually drawn, so a pool change can't silently stop exercising them.
  test("a dungeon built from the real room pool never leaves a door unconnected"):
    RoomLoader
      .loadAll()
      .map:
        rooms =>
          val forkThemesInPool = rooms.values.filter(_.roomType == RoomType.Fork).map(_.theme).toSet
          val forkThemesBuilt  = scala.collection.mutable.Set.empty[String]
          for
            difficulty <- Difficulty.values.toList
            seed       <- 1 to 150
          do
            DungeonBuilder(rooms, Random(seed.toLong)).build(difficulty)() match
              case Left(err) => fail(s"seed $seed at $difficulty failed to build: $err")
              case Right(dungeon) =>
                dungeon.rooms.values.foreach:
                  room =>
                    if room.roomType == RoomType.Fork then forkThemesBuilt += room.theme
                    room.entities.foreach:
                      case door: Door =>
                        door.link match
                          case DoorLink.Unresolved(role, branch) =>
                            fail(
                              s"seed $seed at $difficulty: door '${door.id}' ($role, branch $branch) of room " +
                                s"'${room.id}' is not connected to any room"
                            )
                          case _ => ()
                      case _ => ()
          assertEquals(forkThemesBuilt.toSet, forkThemesInPool, "expected every theme's fork rooms to be drawn at least once")

  // The map is worked out from the doors alone, so a wiring shape the layout does not expect would
  // only show up on a real dungeon: a room missing from the map, a second start, a link going back.
  // This checks the map of real dungeons against the dungeon itself, then walks the player to the end of
  // each one and checks the map followed.
  test("the map of a dungeon built from the real room pool matches the dungeon"):
    RoomLoader
      .loadAll()
      .map:
        rooms =>
          val revealedInAdvance = Set("boss", "miniboss", "sanctuary")
          for
            difficulty <- Difficulty.values.toList
            seed       <- 1 to 60
          do
            DungeonBuilder(rooms, Random(seed.toLong)).build(difficulty)() match
              case Left(err) => fail(s"seed $seed at $difficulty failed to build: $err")
              case Right(dungeon) =>
                val where = s"seed $seed at $difficulty"
                val view  = MinimapBuilder.build(dungeon)
                val byId  = view.nodes.map(n => n.id -> n).toMap
                def position(id: String): (Int, Int) = (byId(id).section, byId(id).column)

                assertEquals(view.nodes.size, dungeon.rooms.size, s"$where: a room is missing from the map")
                assertEquals(view.sections.size, difficulty.biomeCount, s"$where: expected a row per section")
                assertEquals(view.nodes.count(_.current), 1, s"$where: expected one current room")
                assertEquals(view.nodes.map(_.id).toSet -- view.edges.map(_.to), Set(view.nodes.find(_.current).get.id),
                             s"$where: the starting room should be the only one nothing leads into"
                )
                assert(view.edges.forall(e => Ordering[(Int, Int)].gt(position(e.to), position(e.from))), s"$where: a link goes back")
                assert(view.nodes.forall(n => n.lane == 0 || n.lane == 1), s"$where: unexpected lane")
                assert(view.nodes.forall(n => revealedInAdvance.contains(n.roomType.getOrElse("")) || n.visited || n.roomType.isEmpty),
                       s"$where: a hidden room gave its type away"
                )
                assertEquals(view.nodes.count(_.roomType.contains("sanctuary")), 1, s"$where: expected one Sanctuary")
                assertEquals(view.edges.count(_.exit.isDefined),
                             2 * dungeon.rooms.values.count(_.roomType == RoomType.Fork),
                             s"$where: every fork should have two exits"
                )

                @scala.annotation.tailrec
                def walkToTheEnd(d: Dungeon, steps: Int): (Dungeon, Int) =
                  d.currentRoom.entities
                    .collectFirst { case door: Door if door.link.role == ConnectorRole.Next => door.link }
                    .collect { case DoorLink.Resolved(_, _, target) => target } match
                    case Some(target) => walkToTheEnd(d.navigateTo(target, forward = true).toOption.get, steps + 1)
                    case None         => (d, steps)

                val (end, steps) = walkToTheEnd(dungeon, 0)
                val atEnd        = MinimapBuilder.build(end)
                assertEquals(atEnd.nodes.filter(_.current).map(_.roomType), List(Some("sanctuary")), s"$where: the run should end at the Sanctuary")
                assertEquals(atEnd.nodes.count(_.visited), steps + 1, s"$where: one visited room per step, plus the start")

  // The tile a player appears on after walking through a door depends on the layout of the room they
  // arrive in (see InteractionResolver.findSpawnPoint), so a room with an unusual wall thickness can
  // put them somewhere they cannot stand. This walks through every ordinary door of every room of real
  // dungeons and checks the arrival tile is walkable and sits in a room the player can leave, which
  // an enclosed pocket inside a wall would not be.
  test("walking through any door of a real dungeon never leaves the player stuck in a wall"):
    RoomLoader
      .loadAll()
      .map:
        rooms =>
          val resolver = InteractionResolver(Map.empty, Map.empty)
          for
            difficulty <- Difficulty.values.toList
            seed       <- 1 to 40
          do
            DungeonBuilder(rooms, Random(seed.toLong)).build(difficulty)() match
              case Left(err) => fail(s"seed $seed at $difficulty failed to build: $err")
              case Right(dungeon) =>
                for
                  room <- dungeon.rooms.values
                  door <- room.entities.collect { case d: Door if d.doorKind == DoorKind.Normal => d }
                  if door.link.isInstanceOf[DoorLink.Resolved]
                do
                  val start = ExplorationState(PlayerFixtures.startingPlayer(ClassId.Warrior),
                                               dungeon.copy(currentRoomId = room.id),
                                               playerX = door.x,
                                               playerY = door.y
                  )
                  resolver.interact(start, door.id).state match
                    case arrived: ExplorationState =>
                      val target = arrived.dungeon.currentRoom
                      assert(
                        target.isWalkable(arrived.playerX, arrived.playerY),
                        s"seed $seed at $difficulty: through '${door.id}' of '${room.id}' the player arrives at " +
                          s"${(arrived.playerX, arrived.playerY)} in '${target.id}', which is not walkable"
                      )
                    case other => fail(s"unexpected state after walking through '${door.id}': $other")

  // A run begins in the first room of the dungeon, which is always a Combat room (see
  // InteractionResolver.startSpawnPoint). Whatever the wall thickness of a room's theme, the player must
  // not start inside a wall, so every Combat room of the real pool is checked.
  test("a run never begins inside a wall, in any room that can open a dungeon"):
    RoomLoader
      .loadAll()
      .map:
        rooms =>
          val resolver = InteractionResolver(Map.empty, Map.empty)
          rooms.values
            .filter(_.roomType == RoomType.Combat)
            .foreach:
              room =>
                val start = resolver.startSpawnPoint(room)
                assert(room.isWalkable(start._1, start._2),
                       s"a run opened by '${room.id}' would begin at $start, which is not walkable"
                )

  /** With the entities `blocking` selects standing on their tiles for good, every door of each room still
    * connects to the first one, and so does a free tile next to each entity `mustBeReachable` selects.
    * Reachability is measured from the first door: a door's approach tile is the first floor tile
    * stepping inward from it (a darkDungeon door sits behind a 2-thick wall, a dungeon one behind a
    * single row). Floor nobody needs to reach, like a decorative alcove behind the Sanctuary's halo,
    * is deliberately not checked.
    */
  private def assertDoorsStayConnected(rooms: Iterable[Room],
                                       blocking: Entity => Boolean,
                                       mustBeReachable: Entity => Boolean
  ): Unit =
    rooms.foreach:
      room =>
        def isDoor(e: Entity): Boolean = e match
          case _: Door | _: LockedDoor => true
          case _                       => false

        val blocked = room.entities.filter(blocking).flatMap(_.occupiedTiles).toSet
        val walkable = (for
          y <- 0 until room.height
          x <- 0 until room.width
          if room.tileAt(x, y) == Tile.Floor && !blocked.contains((x, y))
        yield (x, y)).toSet

        def neighbors(p: (Int, Int)): List[(Int, Int)] =
          List((p._1 + 1, p._2), (p._1 - 1, p._2), (p._1, p._2 + 1), (p._1, p._2 - 1))

        def componentOf(start: (Int, Int)): Set[(Int, Int)] =
          @annotation.tailrec
          def loop(frontier: List[(Int, Int)], seen: Set[(Int, Int)]): Set[(Int, Int)] = frontier match
            case Nil => seen
            case p :: rest =>
              val next = neighbors(p).filter(n => walkable.contains(n) && !seen.contains(n))
              loop(next ::: rest, seen ++ next)
          loop(List(start), Set(start))

        def inward(direction: Direction): (Int, Int) = direction match
          case Direction.Up    => (0, 1)
          case Direction.Down  => (0, -1)
          case Direction.Left  => (1, 0)
          case Direction.Right => (-1, 0)

        def approachTile(door: Entity): Option[(Int, Int)] =
          val (dx, dy) = door match
            case d: Door       => inward(d.direction)
            case d: LockedDoor => inward(d.direction)
            case _             => (0, 0)
          (1 to 3).map(k => (door.x + k * dx, door.y + k * dy)).find(p => room.tileAt(p._1, p._2) == Tile.Floor)

        val doors = room.entities.filter(isDoor)
        if doors.nonEmpty then
          val first = doors.head
          val firstApproach = approachTile(first).filter(walkable.contains)
          assert(firstApproach.isDefined, s"room '${room.id}': door '${first.id}' has no free approach tile")
          val main = componentOf(firstApproach.get)

          doors.tail.foreach:
            door =>
              assert(approachTile(door).exists(main.contains),
                     s"room '${room.id}': door '${door.id}' can no longer be reached from door '${first.id}'"
              )

          room.entities.filter(mustBeReachable).foreach:
            e =>
              val adjacent = e.occupiedTiles.flatMap(neighbors).filterNot(e.occupiedTiles.contains)
              assert(adjacent.exists(main.contains),
                     s"room '${room.id}': '${e.id}' can no longer be reached from door '${first.id}'"
              )

  // Chests, NPCs and the Sanctuary stay on the map for good (an opened chest keeps blocking its
  // tile), unlike enemies and shrines which disappear. An authored room must not let them seal off a
  // door or each other.
  test("chests, npcs and the sanctuary never seal off a door or each other"):
    def isPermanent(e: Entity): Boolean = e match
      case _: Chest | _: Npc | _: Sanctuary => true
      case _                                 => false
    RoomLoader.loadAll().map(rooms => assertDoorsStayConnected(rooms.values, isPermanent, isPermanent))

  // The vow of no ordinary kill must be possible: a fight is only ever optional, so an enemy, which
  // blocks its tile until it is dead, must not stand where the player has to pass to leave the room.
  // A Boss room is exempt, a Boss may be killed under this vow.
  test("no room forces a fight to get from one door to another, except a Boss room"):
    def isPermanent(e: Entity): Boolean = e match
      case _: Chest | _: Npc | _: Sanctuary => true
      case _                                 => false
    def blocks(e: Entity): Boolean = isPermanent(e) || e.isInstanceOf[Enemy]
    RoomLoader.loadAll().map:
      rooms =>
        assertDoorsStayConnected(rooms.values.filterNot(_.roomType == RoomType.Boss), blocks, _ => false)

  test("difficulty.json defines every difficulty, and the lowest one needs nothing"):
    for tiers <- DifficultyLoader.loadAll()
    yield
      assertEquals(tiers.keySet, Difficulty.values.toSet)
      assertEquals(tiers(Difficulty.Easy).requirement, None)

  test("every tier lists one theme per section its difficulty builds"):
    for tiers <- DifficultyLoader.loadAll()
    yield tiers.values.foreach:
      tier =>
        assertEquals(tier.themes.size,
                     tier.difficulty.biomeCount,
                     s"${tier.difficulty} builds ${tier.difficulty.biomeCount} sections"
        )

  test("every theme a tier lists can build its sections from rooms.json"):
    for
      tiers <- DifficultyLoader.loadAll()
      rooms <- RoomLoader.loadAll()
    yield
      def has(theme: String, roomType: RoomType): Boolean =
        rooms.values.exists(r => r.theme == theme && r.roomType == roomType)

      tiers.values.foreach:
        tier =>
          tier.themes.foreach:
            theme => assert(has(theme, RoomType.Boss), s"${tier.difficulty}: theme '$theme' has no Boss room")
          tier.themes.init.foreach:
            theme => assert(has(theme, RoomType.Rest), s"${tier.difficulty}: theme '$theme' has no Rest room")
          assert(has(tier.themes.last, RoomType.Sanctuary),
                 s"${tier.difficulty}: theme '${tier.themes.last}' has no Sanctuary room"
          )

  test("every scene of story.json asks for a chapter that exists in chapters.json"):
    for
      chapters <- ChapterLoader.loadAll()
      scenes   <- StoryLoader.loadAll()
    yield scenes.values.foreach:
      scene =>
        assert(chapters.contains(scene.minChapter),
               s"scene '${scene.id}' asks for chapter ${scene.minChapter}, which chapters.json does not define"
        )

  test("every relic a scene asks to have been read belongs to a Devoted of enemies.json"):
    for
      enemies <- EnemyLoader.loadAll()
      scenes  <- StoryLoader.loadAll()
    yield
      val devoted = enemies.values.flatMap(_.devoted).toSet
      scenes.values.flatMap(scene => scene.requiresRelicRead.map(scene.id -> _)).foreach:
        (sceneId, n) => assert(devoted.contains(n), s"scene '$sceneId' asks for the relic of Devoted $n, which no enemy is")

  test("the scene that gives an NPC's name away is a scene of that NPC"):
    for
      npcs   <- NpcLoader.loadAll()
      scenes <- StoryLoader.loadAll()
    yield npcs.values.foreach:
      npc =>
        npc.revealedBy.foreach:
          sceneId =>
            assert(scenes.get(sceneId).exists(_.npc == npc.id), s"'${npc.id}' is revealed by '$sceneId', which is not one of its scenes")

  test("every NPC has four scenes in a row, offers one vow and ends on a scene that needs it kept"):
    for scenes <- StoryLoader.loadAll()
    yield scenes.values.groupBy(_.npc).foreach:
      (npc, npcScenes) =>
        assertEquals(npcScenes.map(_.id).toSet, (1 to 4).map(n => s"$npc.s$n").toSet, s"$npc must have scenes s1 to s4")
        assertEquals(npcScenes.count(_.offersVow.isDefined), 1, s"$npc must offer exactly one vow")
        assert(npcScenes.find(_.id == s"$npc.s4").exists(_.requiresVowKept), s"the last scene of $npc must need its vow kept")
        (2 to 4).foreach(n => assertEquals(npcScenes.find(_.id == s"$npc.s$n").flatMap(_.requires), Some(s"$npc.s${n - 1}")))

  test("a dungeon built from the real pool follows its tier's themes and never meets a Boss twice"):
    for
      tiers <- DifficultyLoader.loadAll()
      rooms <- RoomLoader.loadAll()
    yield tiers.values.foreach:
      tier =>
        (0L until 30L).foreach:
          seed =>
            val dungeon = DungeonBuilder(rooms, Random(seed), tier.themes)
              .build(tier.difficulty)()
              .getOrElse(fail(s"${tier.difficulty} failed to build for seed $seed"))
            val bosses = dungeon.rooms.values.filter(_.roomType == RoomType.Boss).toList
            assertEquals(dungeon.currentRoom.theme, tier.themes.head, s"${tier.difficulty}, seed $seed")
            assertEquals(bosses.map(_.theme).sorted, tier.themes.sorted, s"${tier.difficulty}, seed $seed")
            assert(!bosses.exists(_.id.contains('#')),
                   s"${tier.difficulty}, seed $seed: the same Boss room was drawn twice"
            )
