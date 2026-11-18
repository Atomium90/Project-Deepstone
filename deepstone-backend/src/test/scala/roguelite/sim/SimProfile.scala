package roguelite.sim

import roguelite.engine.{ ClassId, Difficulty }

/** How far into the game a save is, as what the hub shop has sold it. A profile is a fixed set of
  * upgrades, not a play history: it says what a save looks like at a stage, not how it got there (the
  * economy of getting there is a separate question).
  *
  * @param defaultDifficulty
  *   The tier a save at this stage would be playing, when none is asked for.
  */
enum SimProfile(val label: String, val defaultDifficulty: Difficulty, val description: String):
  case Fresh extends SimProfile("fresh", Difficulty.Easy, "nothing bought: no upgrade, no starting kit")
  case Early
      extends SimProfile("early", Difficulty.Easy, "the class's starting kit, the first HP upgrade and a starting potion")
  case Mid
      extends SimProfile("mid", Difficulty.Normal, "early, plus the second HP upgrade, the third belt slot, bigger potion stacks and weapon mastery")
  case Late extends SimProfile("late", Difficulty.Hard, "every upgrade of the shop that asks for no victory first")
  case Post extends SimProfile("post", Difficulty.Hard, "every upgrade of the shop, the late-game ones that ask for Hard victories included")

  /** The ids of the upgrades a save at this stage owns, for a run as `classId` (the access to the
    * class itself is added by the hub, whatever the stage).
    */
  def upgrades(world: SimWorld, classId: ClassId): Set[String] = this match
    case Fresh => Set.empty
    case Early => SimProfile.EarlyUpgrades ++ world.kitUpgrade(classId)
    case Mid   => SimProfile.EarlyUpgrades ++ SimProfile.MidUpgrades ++ world.kitUpgrade(classId)
    case Late  => world.upgradeDefs.filter((_, u) => u.requires.isEmpty).keySet
    case Post  => world.upgradeDefs.keySet

object SimProfile:

  /** The upgrades the profiles name, so a test can check they all exist in the shop. */
  val EarlyUpgrades: Set[String] = Set("hp_boost_1", "potion_start")
  val MidUpgrades: Set[String]   = Set("hp_boost_2", "extra_slot", "extra_potion_capacity", "weapon_mastery")

  def parse(text: String): Either[String, SimProfile] =
    SimProfile.values.find(_.label.equalsIgnoreCase(text)).toRight(s"Unknown profile '$text' (${SimProfile.values.map(_.label).mkString(", ")})")

/** Everything that decides one kind of run: who plays, on which tier, with what the save owns and
  * which perks are taken. The seed is not part of it, it belongs to the run.
  */
final case class SimSetup(
    classId: ClassId,
    difficulty: Difficulty,
    profile: SimProfile,
    perkId: Option[String] = None,
    riskyPerkId: Option[String] = None
):

  /** This setup, or why it cannot be played as asked. */
  def validate(world: SimWorld): Either[String, SimSetup] =
    val perk  = perkId.map(id => world.perkDefs.get(id).toRight(s"Unknown perk '$id'").filterOrElse(!_.risky, s"'$id' is a risky perk: use --risky"))
    val risky = riskyPerkId.map(id => world.perkDefs.get(id).toRight(s"Unknown perk '$id'").filterOrElse(_.risky, s"'$id' is not a risky perk: use --perk"))
    for
      _ <- perk.fold(Right(()))(_.map(_ => ()))
      _ <- risky.fold(Right(()))(_.map(_ => ()))
      _ <- Either.cond(riskyPerkId.isEmpty || difficulty == Difficulty.Hard, (), "A risky perk can only be taken on Hard")
    yield this

  def describe: String =
    val perks = List(perkId.map(p => s"perk $p"), riskyPerkId.map(p => s"risky perk $p")).flatten
    s"${classId.toString.toLowerCase}, ${difficulty.toString.toLowerCase}, ${profile.label} save (${profile.description})" +
      (if perks.isEmpty then "" else perks.mkString(", ", ", ", ""))
