package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.Difficulty

/** Loads the difficulty tier catalog from `data/difficulty.json` on the classpath.
  *
  * Mirrors [[PerkLoader]]: resource reading and error wrapping are handled by
  * [[JsonResourceLoader]], this object only supplies the parsing.
  */
object DifficultyLoader extends JsonResourceLoader[DifficultyTierDef, Difficulty]:

  protected val resourcePath = "data/difficulty.json"

  protected def keyOf(entry: DifficultyTierDef): Difficulty = entry.difficulty

  protected def parseEntries(json: String): Either[String, List[DifficultyTierDef]] =
    decode[List[TierJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toTier))
      .flatMap(rejectDuplicates)

  private def toTier(j: TierJson): Either[String, DifficultyTierDef] =
    for
      difficulty <- parseDifficulty(j.difficulty)
      requirement <- j.requirement match
        case None    => Right(None)
        case Some(r) => toRequirement(difficulty, r).map(Some(_))
      _ <- Either.cond(j.themes.nonEmpty, (), s"Difficulty '${j.difficulty}' lists no theme")
      storyLocked = j.storyLocked.getOrElse(false)
      _ <- Either.cond(!storyLocked || requirement.isEmpty, (), s"Difficulty '${j.difficulty}' is opened by the story, so it has no requirement")
    yield DifficultyTierDef(difficulty, requirement, j.themes, storyLocked)

  private def toRequirement(difficulty: Difficulty, r: RequirementJson): Either[String, TierRequirement] =
    for
      winsAt <- parseDifficulty(r.winsAt)
      _      <- Either.cond(winsAt.ordinal < difficulty.ordinal, (), s"$difficulty must be unlocked by a lower tier, not $winsAt")
      _      <- Either.cond(r.wins >= 1, (), s"$difficulty requires at least 1 win")
      _      <- Either.cond(r.shardsSpent >= 0, (), s"$difficulty cannot require negative Shards spent")
    yield TierRequirement(winsAt, r.wins, r.shardsSpent)

  /** A tier defined twice would silently keep only the last entry once keyed by difficulty. */
  private def rejectDuplicates(tiers: List[DifficultyTierDef]): Either[String, List[DifficultyTierDef]] =
    tiers.groupBy(_.difficulty).collectFirst { case (d, defs) if defs.size > 1 => d } match
      case Some(d) => Left(s"Difficulty $d is defined more than once")
      case None    => Right(tiers)

  private def parseDifficulty(s: String): Either[String, Difficulty] =
    Difficulty.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown difficulty: '$s'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class RequirementJson(winsAt: String, wins: Int, shardsSpent: Int)

  private case class TierJson(difficulty: String,
                              requirement: Option[RequirementJson],
                              themes: List[String],
                              storyLocked: Option[Boolean]
  )

  private given Decoder[RequirementJson] = Decoder.instance:
    (c: HCursor) =>
      for
        winsAt      <- c.get[String]("winsAt")
        wins        <- c.get[Int]("wins")
        shardsSpent <- c.get[Int]("shardsSpent")
      yield RequirementJson(winsAt, wins, shardsSpent)

  private given Decoder[TierJson] = Decoder.instance:
    (c: HCursor) =>
      for
        difficulty  <- c.get[String]("difficulty")
        requirement <- c.get[Option[RequirementJson]]("requirement")
        themes      <- c.get[List[String]]("themes")
        storyLocked <- c.get[Option[Boolean]]("storyLocked")
      yield TierJson(difficulty, requirement, themes, storyLocked)
