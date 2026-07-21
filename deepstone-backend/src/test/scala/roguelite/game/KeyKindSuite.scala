package roguelite.game

import munit.FunSuite
import roguelite.engine.Direction

class KeyKindSuite extends FunSuite:

  def door(id: String = "d1", doorTag: Option[String] = None): LockedDoor =
    LockedDoor(id, x = 0, y = 0, direction = Direction.Down, targetRoomId = "r2", doorTag = doorTag)

  test("Generic unlocks any door"):
    assert(KeyKind.canUnlock(KeyKind.Generic, door()))
    assert(KeyKind.canUnlock(KeyKind.Generic, door(doorTag = Some("fire"))))

  test("Universal unlocks any door"):
    assert(KeyKind.canUnlock(KeyKind.Universal, door()))
    assert(KeyKind.canUnlock(KeyKind.Universal, door(doorTag = Some("fire"))))

  test("Specific only unlocks the door with the matching id"):
    assert(KeyKind.canUnlock(KeyKind.Specific("d1"), door(id = "d1")))
    assert(!KeyKind.canUnlock(KeyKind.Specific("other"), door(id = "d1")))

  test("Typed only unlocks a door tagged with the matching doorTag"):
    assert(KeyKind.canUnlock(KeyKind.Typed("fire"), door(doorTag = Some("fire"))))
    assert(!KeyKind.canUnlock(KeyKind.Typed("water"), door(doorTag = Some("fire"))))

  test("Typed never unlocks an untagged door"):
    assert(!KeyKind.canUnlock(KeyKind.Typed("fire"), door(doorTag = None)))
