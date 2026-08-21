package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.Difficulty

/** Loads achievement definitions from `data/achievements.json` on the classpath. Resource reading
  * and error wrapping are handled by [[JsonResourceLoader]].
  */
object AchievementLoader extends JsonResourceLoader[AchievementDef, String]:

  protected val resourcePath = "data/achievements.json"

  protected def keyOf(entry: AchievementDef): String = entry.id

  protected def parseEntries(json: String): Either[String, List[AchievementDef]] =
    decode[List[AchievementDefJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toAchievementDef))

  private def toAchievementDef(j: AchievementDefJson): Either[String, AchievementDef] =
    parseCondition(j.condition).map:
      condition =>
        AchievementDef(
          id = j.id,
          label = j.label,
          description = j.description,
          displayOrder = j.displayOrder,
          condition = condition
        )

  private def parseCondition(c: AchievementConditionJson): Either[String, AchievementCondition] =
    c.`type` match
      case "FirstKill" =>
        Right(AchievementCondition.FirstKill)
      case "DefeatBoss" =>
        Right(AchievementCondition.DefeatBoss)
      case "ReachLevel" =>
        c.level.toRight("ReachLevel is missing 'level' field").map(AchievementCondition.ReachLevel.apply)
      case "NoDamageVictory" =>
        Right(AchievementCondition.NoDamageVictory)
      case "FillInventory" =>
        Right(AchievementCondition.FillInventory)
      case "TotalShardsSpent" =>
        c.amount
          .toRight("TotalShardsSpent is missing 'amount' field")
          .map(AchievementCondition.TotalShardsSpent.apply)
      case "UnlockDoorWithKey" =>
        Right(AchievementCondition.UnlockDoorWithKey)
      case "RevealSecretDoor" =>
        Right(AchievementCondition.RevealSecretDoor)
      case "RunsCompleted" =>
        c.count.toRight("RunsCompleted is missing 'count' field").map(AchievementCondition.RunsCompleted.apply)
      case "RunsWon" =>
        c.count.toRight("RunsWon is missing 'count' field").map(AchievementCondition.RunsWon.apply)
      case "WinStreak" =>
        c.count.toRight("WinStreak is missing 'count' field").map(AchievementCondition.WinStreak.apply)
      case "AllUpgradesUnlocked" =>
        Right(AchievementCondition.AllUpgradesUnlocked)
      case "LootRarity" =>
        c.rarity
          .toRight("LootRarity is missing 'rarity' field")
          .flatMap(parseRarity)
          .map(AchievementCondition.LootRarity.apply)
      case "FourPieceSetActive" =>
        Right(AchievementCondition.FourPieceSetActive)
      case "FillPotionBelt" =>
        Right(AchievementCondition.FillPotionBelt)
      case "FillPotionStack" =>
        Right(AchievementCondition.FillPotionStack)
      case "WinOnDifficulty" =>
        c.difficulty
          .toRight("WinOnDifficulty is missing 'difficulty' field")
          .flatMap(parseDifficulty)
          .map(AchievementCondition.WinOnDifficulty.apply)
      case other =>
        Left(s"Unknown achievement condition type: '$other'")

  private def parseRarity(s: String): Either[String, Rarity] =
    Rarity.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown rarity: '$s'")

  private def parseDifficulty(s: String): Either[String, Difficulty] =
    Difficulty.values
      .find(_.toString.toLowerCase == s.toLowerCase)
      .toRight(s"Unknown difficulty: '$s'")

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class AchievementConditionJson(
      `type`: String,
      level: Option[Int] = None,
      amount: Option[Int] = None,
      count: Option[Int] = None,
      rarity: Option[String] = None,
      difficulty: Option[String] = None
  )

  private case class AchievementDefJson(
      id: String,
      label: String,
      description: String,
      displayOrder: Int,
      condition: AchievementConditionJson
  )

  private given Decoder[AchievementConditionJson] = Decoder.instance:
    (c: HCursor) =>
      for
        t          <- c.get[String]("type")
        level      <- c.get[Option[Int]]("level")
        amount     <- c.get[Option[Int]]("amount")
        count      <- c.get[Option[Int]]("count")
        rarity     <- c.get[Option[String]]("rarity")
        difficulty <- c.get[Option[String]]("difficulty")
      yield AchievementConditionJson(t, level, amount, count, rarity, difficulty)

  private given Decoder[AchievementDefJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id           <- c.get[String]("id")
        label        <- c.get[String]("label")
        description  <- c.get[String]("description")
        displayOrder <- c.get[Int]("displayOrder")
        condition    <- c.get[AchievementConditionJson]("condition")
      yield AchievementDefJson(id, label, description, displayOrder, condition)
