package roguelite

import cats.effect.{ ExitCode, IO, IOApp }
import cats.effect.std.Console
import cats.syntax.semigroupk.*
import com.comcast.ip4s.{ host, Port }
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.http4s.StaticFile
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import roguelite.engine.{ RequestGuard, SecurityHeaders, ServerLimits, StateMachine, WebSocketRouter }
import roguelite.game.{
  AbilityLoader,
  AchievementLoader,
  ClassLoader,
  CombatResolver,
  EnemyLoader,
  ItemLoader,
  NpcDialogueLoader,
  PerkLoader,
  RoomLoader,
  SetLoader,
  UpgradeLoader
}
import roguelite.db.Database

import java.nio.file.{ Path, Paths }

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    server(StartupOptions.parse(args))
      .as(ExitCode.Success)
      .recoverWith:
        case failure: StartupFailure => Console[IO].errorln(failure.message).as(ExitCode.Error)

  private def server(options: StartupOptions): IO[Unit] =
    for
      serverPort <- PortSelection.choose(options.port, PortSelection.isFreeOnLoopback)
      savePath   <- resolveSavePath(options)
      _          <- serve(options, savePath, serverPort)
    yield ()

  /** The save is in the player's data folder unless --db names another file. */
  private def resolveSavePath(options: StartupOptions): IO[Path] =
    for
      given Logger[IO] <- Slf4jLogger.create[IO]
      dataDirectory =
        SaveLocation.dataDirectory(sys.props("os.name"), sys.env, Paths.get(sys.props("user.home")))
      savePath <- SaveLocation.resolve(options.databasePath, Paths.get("").toAbsolutePath, dataDirectory)
      _        <- Logger[IO].info(s"Save file: $savePath")
    yield savePath

  private def serve(options: StartupOptions, savePath: Path, serverPort: Int): IO[Unit] =
    // Database is a managed resource: schema init on open, connection pool released on exit.
    Database
      .resource(savePath.toString)
      .use:
        database =>
          for
            given org.typelevel.log4cats.Logger[IO] <- Slf4jLogger.create[IO]
            logger                                  <- Slf4jLogger.create[IO]

            _ <- IO.whenA(options.ignoredArgs.nonEmpty)(
              logger.warn(s"Ignoring unknown arguments: ${options.ignoredArgs.mkString(" ")}")
            )
            _ <- IO.whenA(options.debugMode)(
              logger.info(s"Debug mode on (${StartupOptions.DebugFlag}): Debug Rooms are available from the hub.")
            )
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
            perkDefs <- PerkLoader.loadAll()
            _ <- logger.info(
              s"Loaded ${roomPool.size} rooms, ${enemyStats.size} enemy types, ${itemDefs.size} item types, " +
                s"${abilityDefs.size} abilities, ${upgradeDefs.size} upgrades, ${npcDialogueDefs.size} npc dialogues, " +
                s"${achievementDefs.size} achievements, ${setDefs.size} equipment sets, ${perkDefs.size} run perks."
            )

            resolver = CombatResolver(itemDefs = itemDefs, abilityDefs = abilityDefs, setDefs = setDefs,
                                      perkDefs = perkDefs
            )
            stateMachine = StateMachine(roomPool,
                                        enemyStats,
                                        itemDefs,
                                        classDefs,
                                        upgradeDefs,
                                        resolver,
                                        npcDialogueDefs = npcDialogueDefs,
                                        setDefs = setDefs,
                                        perkDefs = perkDefs
            )
            router = WebSocketRouter(stateMachine, database, itemDefs, upgradeDefs, abilityDefs,
                                     achievementDefs, setDefs = setDefs, perkDefs = perkDefs,
                                     debugMode = options.debugMode
            )

            // Serves the frontend's built static files (copied into resources/static/ by
            // build-release.ps1 before packaging - see that script). Root serves index.html
            // explicitly since resourceServiceBuilder maps request paths directly onto
            // classpath resources and doesn't infer a directory index on its own.
            staticRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
              case req @ GET -> Root =>
                StaticFile.fromResource("/static/index.html", Some(req)).getOrElseF(NotFound())
            } <+> resourceServiceBuilder[IO]("/static").toRoutes

            // Every request, WebSocket handshake and static file alike, goes through the guard
            // first: a page from another site never reaches the game.
            guard = RequestGuard(serverPort)

            listenPort <- IO.fromOption(Port.fromInt(serverPort))(
              IllegalStateException(s"Not a valid port: $serverPort")
            )
            _ <- EmberServerBuilder
              .default[IO]
              .withHost(host"127.0.0.1")
              .withPort(listenPort)
              .withMaxWebSocketMessageSize(ServerLimits.MaxWebSocketMessageBytes)
              .withMaxConnections(ServerLimits.MaxConnections)
              .withHttpWebSocketApp(
                wsb =>
                  SecurityHeaders(serverPort)(
                    guard((router.routes(wsb) <+> staticRoutes).orNotFound)
                  )
              )
              .build
              .use(
                _ =>
                  logger.info(s"Deepstone is running. Open http://localhost:$serverPort in your browser.") *>
                    IO.never
              )
              .onError(
                err => logger.error(err)("Server crashed")
              )
          yield ()
