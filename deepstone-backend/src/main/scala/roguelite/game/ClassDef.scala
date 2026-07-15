package roguelite.game

import roguelite.engine.ClassId

/** Static definition of a playable class, loaded from classes.json at startup.
  *
  * Instances are immutable reference data shared across all runs. When a run starts, the
  * [[roguelite.engine.StateMachine]] uses the matching [[ClassDef]] to build the initial
  * [[roguelite.engine.Player]] and resolve the starting kit from the item prototype map.
  *
  * @param classId
  *   The enum value identifying this class.
  * @param hp
  *   Starting (and maximum) HP for this class.
  * @param resourceMax
  *   Maximum resource pool (Rage / Focus / Mana).
  * @param resourceStart
  *   Resource at the start of each run (0 for Warrior, full for Archer and Mage).
  * @param affinityTags
  *   Item type tags that grant the x2 damage/defense multiplier in combat. Matched against
  *   [[Weapon.typeTag]] and [[Armor.typeTag]] in [[CombatResolver]].
  * @param startingKit
  *   Ordered list of item typeIds added to the player's inventory at run start. Unknown typeIds are
  *   silently skipped; inventory-full is not expected with 3 items / 6 slots.
  */
case class ClassDef(
    classId: ClassId,
    hp: Int,
    resourceMax: Int,
    resourceStart: Int,
    affinityTags: Set[String],
    startingKit: List[String]
)
