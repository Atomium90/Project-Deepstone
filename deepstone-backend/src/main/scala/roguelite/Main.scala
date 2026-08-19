package roguelite

import cats.effect.{ IO, IOApp }
import cats.syntax.semigroupk.*
import com.comcast.ip4s.{ host, port }
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.http4s.StaticFile
import org.typelevel.log4cats.slf4j.Slf4jLogger
import roguelite.engine.{ StateMachine, WebSocketRouter }
import roguelite.game.{
  AbilityLoader,
  AchievementLoader,
  ClassLoader,
  CombatResolver,
  EnemyLoader,
  ItemLoader,
  NpcDialogueLoader,
  RoomLoader,
  SetLoader,
  UpgradeLoader
}
import roguelite.db.Database

object Main extends IOApp.Simple:

  /** SQLite database file written alongside the running JAR. */
  private val DbPath = "deepstone.db"

  def run: IO[Unit] =
    // Database is a managed resource: schema init on open, connection pool released on exit.
    Database
      .resource(DbPath)
      .use:
        database =>
          for
            given org.typelevel.log4cats.Logger[IO] <- Slf4jLogger.create[IO]
            logger                                  <- Slf4jLogger.create[IO]

            _           <- logger.info("Loading game data...")
            roomPool    <- RoomLoader.loadAll()
            enemyStats  <- EnemyLoader.loadAll()
            itemDefs    <- ItemLoader.loadAll()
            classDefs   <- ClassLoader.loadAll()
            abilityDefs <- AbilityLoader.loadAll()
            upgradeDefs <- UpgradeLoader.loadAll()
            npcDialogueDefs <- NpcDialogueLoader.loadAll()
            achievementDefs <- AchievementLoader.loadAll()
            setDefs <- SetLoader.loadAll()
            _ <- logger.info(
              s"Loaded ${roomPool.size} rooms, ${enemyStats.size} enemy types, ${itemDefs.size} item types, " +
                s"${abilityDefs.size} abilities, ${upgradeDefs.size} upgrades, ${npcDialogueDefs.size} npc dialogues, " +
                s"${achievementDefs.size} achievements, ${setDefs.size} equipment sets."
            )

            resolver = CombatResolver(itemDefs = itemDefs, abilityDefs = abilityDefs, setDefs = setDefs)
            stateMachine = StateMachine(roomPool,
                                        enemyStats,
                                        itemDefs,
                                        classDefs,
                                        upgradeDefs,
                                        resolver,
                                        npcDialogueDefs = npcDialogueDefs,
                                        setDefs = setDefs
            )
            router = WebSocketRouter(stateMachine, database, itemDefs, upgradeDefs, abilityDefs,
                                     achievementDefs, setDefs
            )

            // Serves the frontend's built static files (copied into resources/static/ by
            // build-release.ps1 before packaging - see that script). Root serves index.html
            // explicitly since resourceServiceBuilder maps request paths directly onto
            // classpath resources and doesn't infer a directory index on its own.
            staticRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
              case req @ GET -> Root =>
                StaticFile.fromResource("/static/index.html", Some(req)).getOrElseF(NotFound())
            } <+> resourceServiceBuilder[IO]("/static").toRoutes

            _ <- EmberServerBuilder
              .default[IO]
              .withHost(host"127.0.0.1")
              .withPort(port"8080")
              .withHttpWebSocketApp(
                wsb => (router.routes(wsb) <+> staticRoutes).orNotFound
              )
              .build
              .useForever
              .onError(
                err => logger.error(err)("Server crashed")
              )
          yield ()
