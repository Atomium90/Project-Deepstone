package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.{ Difficulty, Direction }

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

  test("every npc id placed in rooms.json exists in npcs.json"):
    for
      npcs  <- NpcDialogueLoader.loadAll()
      rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach:
      room =>
        room.entities.collect { case n: Npc => n }.foreach:
          n =>
            assert(npcs.contains(n.id), s"room '${room.id}' places npc '${n.id}' with no matching entry in npcs.json")

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

  // Chests, NPCs and the Sanctuary stay on the map for good (an opened chest keeps blocking its
  // tile), unlike enemies and shrines which disappear. An authored room must not let them seal off a
  // door or each other. Reachability is measured from the first door: a door's approach tile is the
  // first floor tile stepping inward from it (a darkDungeon door sits behind a 2-thick wall, a
  // dungeon one behind a single row), and every other door's approach tile, plus a free tile next to
  // every chest, npc and the Sanctuary, must still connect to it. Floor nobody needs to reach, like a
  // decorative alcove behind the Sanctuary's halo, is deliberately not checked.
  test("chests, npcs and the sanctuary never seal off a door or each other"):
    RoomLoader.loadAll().map:
      rooms =>
        rooms.values.foreach:
          room =>
            def isPermanent(e: Entity): Boolean = e match
              case _: Chest | _: Npc | _: Sanctuary => true
              case _                                 => false
            def isDoor(e: Entity): Boolean = e match
              case _: Door | _: LockedDoor => true
              case _                       => false

            val permanent = room.entities.filter(isPermanent).flatMap(_.occupiedTiles).toSet
            val walkable = (for
              y <- 0 until room.height
              x <- 0 until room.width
              if room.tileAt(x, y) == Tile.Floor && !permanent.contains((x, y))
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

              room.entities.filter(isPermanent).foreach:
                e =>
                  val adjacent = e.occupiedTiles.flatMap(neighbors).filterNot(e.occupiedTiles.contains)
                  assert(adjacent.exists(main.contains),
                         s"room '${room.id}': '${e.id}' can no longer be reached from door '${first.id}'"
                  )
