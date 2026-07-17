package roguelite.engine

/** Hand-rolled starting-player builder for tests.
  *
  * Mirrors the values in classes.json. Kept in test sources (not main) since production code
  * always builds the starting Player from ClassDef via StateMachine.applyActionPure; this exists
  * only so tests don't need to load classes.json through ClassLoader for simple fixtures.
  */
object PlayerFixtures:
  def startingPlayer(classId: ClassId): Player = classId match {
    case ClassId.Warrior =>
      Player(classId = ClassId.Warrior,
             hp = 120,
             maxHp = 120,
             resourceCurrent = 0,
             resourceMax = 100,
             level = 1,
             xp = 0,
             metaCurrency = 0,
             affinityTags = Set("heavy")
      )
    case ClassId.Archer =>
      Player(classId = ClassId.Archer,
             hp = 90,
             maxHp = 90,
             resourceCurrent = 50,
             resourceMax = 50,
             level = 1,
             xp = 0,
             metaCurrency = 0,
             affinityTags = Set("ranged")
      )
    case ClassId.Mage =>
      Player(classId = ClassId.Mage,
             hp = 70,
             maxHp = 70,
             resourceCurrent = 80,
             resourceMax = 80,
             level = 1,
             xp = 0,
             metaCurrency = 0,
             affinityTags = Set("magic")
      )
  }
