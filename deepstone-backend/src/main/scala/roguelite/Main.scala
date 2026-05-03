package roguelite

import cats.effect.{ IO, IOApp }
import com.comcast.ip4s.{ host, port }
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger as HttpLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import roguelite.engine.{ StateMachine, WebSocketRouter }
import roguelite.game.{ Dungeon, RoomLoader }

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      given org.typelevel.log4cats.Logger[IO] <- Slf4jLogger.create[IO]
      logger                                  <- Slf4jLogger.create[IO]

      _        <- logger.info("Loading room data...")
      roomPool <- RoomLoader.loadAll()
      _        <- logger.info(s"Loaded ${roomPool.size} rooms.")

      // Build the starting dungeon from the first three rooms in the pool.
      // This will be replaced by a DungeonBuilder later on.
      startingRooms = roomPool.values.toList.sortBy(_.id).take(3)
      dungeon <- IO.fromEither(
        Dungeon
          .fromRooms(startingRooms)
          .left
          .map(
            err => RuntimeException(s"Failed to build dungeon: $err")
          )
      )

      stateMachine = StateMachine(dungeon)
      router       = WebSocketRouter(stateMachine)

      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"8080")
        .withHttpWebSocketApp:
          wsb =>
            HttpLogger
              .httpRoutes(logHeaders = false, logBody = false)(router.routes(wsb))
              .orNotFound
        .build
        .useForever
        .onError(
          err => logger.error(err)("Server crashed")
        )
    yield ()
