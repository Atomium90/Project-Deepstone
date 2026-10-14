package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode

/** Loads the NPC scenes from `data/story.json` on the classpath.
  *
  * Mirrors [[PerkLoader]]: resource reading and error wrapping are handled by
  * [[JsonResourceLoader]], this object only supplies the parsing. The links between scenes are
  * checked here too, since a scene that points at nothing would silently never play.
  */
object StoryLoader extends JsonResourceLoader[SceneDef, String]:

  protected val resourcePath = "data/story.json"

  protected def keyOf(entry: SceneDef): String = entry.id

  protected def parseEntries(json: String): Either[String, List[SceneDef]] =
    decode[List[SceneJson]](json)
      .leftMap(_.getMessage)
      .flatMap(js => js.traverse(toScene))
      .flatMap(rejectDuplicateIds)
      .flatMap(checkLinks)

  private def toScene(j: SceneJson): Either[String, SceneDef] =
    for
      _        <- Either.cond(j.npc.nonEmpty, (), s"Scene '${j.id}' has no npc")
      _        <- Either.cond(j.id.startsWith(s"${j.npc}."), (), s"Scene id '${j.id}' must start with its npc id '${j.npc}.'")
      _        <- Either.cond(j.minChapter >= 1, (), s"Scene '${j.id}' must ask for chapter 1 or later")
      _        <- Either.cond(j.requiresRelicRead.forall(_ >= 1), (), s"Scene '${j.id}' must ask for a relic of Devoted 1 or later")
      offersVow <- j.offersVow match
        case None    => Right(None)
        case Some(v) => parseVowType(v.`type`).map(Some(_))
    yield SceneDef(j.id, j.npc, j.minChapter, j.requires, offersVow, j.requiresVowKept.getOrElse(false), j.requiresRelicRead)

  private def parseVowType(name: String): Either[String, VowType] =
    name match
      case "NoPotion"       => Right(VowType.NoPotion)
      case "NoOrdinaryKill" => Right(VowType.NoOrdinaryKill)
      case "DefeatElite"    => Right(VowType.DefeatElite)
      case "GainTwoLevels"  => Right(VowType.GainTwoLevels)
      case "FindRareItem"   => Right(VowType.FindRareItem)
      case other            => Left(s"Unknown vow type: '$other'")

  private def rejectDuplicateIds(scenes: List[SceneDef]): Either[String, List[SceneDef]] =
    scenes.groupBy(_.id).collectFirst { case (id, defs) if defs.size > 1 => id } match
      case Some(id) => Left(s"Scene '$id' is defined more than once")
      case None     => Right(scenes)

  /** Every `requires` names another scene of the same NPC and leads back to a scene with none (no
    * loop), a scene needing a kept vow belongs to an NPC that offers one, and an NPC offers one vow.
    */
  private def checkLinks(scenes: List[SceneDef]): Either[String, List[SceneDef]] =
    val byId = scenes.map(s => s.id -> s).toMap

    def checkRequires(scene: SceneDef): Either[String, Unit] =
      scene.requires match
        case None => Right(())
        case Some(target) =>
          byId.get(target) match
            case None => Left(s"Scene '${scene.id}' requires unknown scene '$target'")
            case Some(other) if other.npc != scene.npc =>
              Left(s"Scene '${scene.id}' requires a scene of another npc ('$target')")
            case Some(_) => Right(())

    @annotation.tailrec
    def leadsToStart(scene: SceneDef, seen: Set[String]): Boolean =
      if seen.contains(scene.id) then false
      else
        scene.requires.flatMap(byId.get) match
          case None         => true
          case Some(parent) => leadsToStart(parent, seen + scene.id)

    def checkVows(npc: String, npcScenes: List[SceneDef]): Either[String, Unit] =
      val offered = npcScenes.flatMap(_.offersVow).distinct
      for
        _ <- Either.cond(offered.size <= 1, (), s"Npc '$npc' offers more than one kind of vow")
        _ <- Either.cond(
          offered.nonEmpty || !npcScenes.exists(_.requiresVowKept),
          (),
          s"Npc '$npc' has a scene that needs a kept vow, but offers none"
        )
      yield ()

    for
      _ <- scenes.traverse(checkRequires)
      _ <- scenes.traverse(s => Either.cond(leadsToStart(s, Set.empty), (), s"Scene '${s.id}' requires itself, through a loop"))
      _ <- scenes.groupBy(_.npc).toList.traverse((npc, npcScenes) => checkVows(npc, npcScenes))
    yield scenes

  // -----------------------------------------------------------------------
  // Internal JSON DTOs
  // -----------------------------------------------------------------------

  private case class VowJson(`type`: String)

  private case class SceneJson(id: String,
                               npc: String,
                               minChapter: Int,
                               requires: Option[String],
                               offersVow: Option[VowJson],
                               requiresVowKept: Option[Boolean],
                               requiresRelicRead: Option[Int]
  )

  private given Decoder[VowJson] = Decoder.instance:
    (c: HCursor) => c.get[String]("type").map(VowJson.apply)

  private given Decoder[SceneJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id              <- c.get[String]("id")
        npc             <- c.get[String]("npc")
        minChapter      <- c.get[Int]("minChapter")
        requires        <- c.get[Option[String]]("requires")
        offersVow       <- c.get[Option[VowJson]]("offersVow")
        requiresVowKept <- c.get[Option[Boolean]]("requiresVowKept")
        requiresRelicRead <- c.get[Option[Int]]("requiresRelicRead")
      yield SceneJson(id, npc, minChapter, requires, offersVow, requiresVowKept, requiresRelicRead)
