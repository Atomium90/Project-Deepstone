package roguelite

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{host, port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger as HttpLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import roguelite.engine.{StateMachine, WebSocketRouter}
import roguelite.game.{CombatResolver, Dungeon, DungeonBuilder, EnemyLoader, RoomLoader}

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      given org.typelevel.log4cats.Logger[IO] <- Slf4jLogger.create[IO]
      logger                                  <- Slf4jLogger.create[IO]

      _        <- logger.info("Loading game data...")
      roomPool <- RoomLoader.loadAll()
      enemyStats <- EnemyLoader.loadAll()
      _        <- logger.info(s"Loaded ${roomPool.size} rooms, ${enemyStats.size} enemy types.")

      // Build a randomized dungeon from the pool each time the server starts.
      // 4 rooms: 1 entrance combat room + 2 middle rooms + 1 boss room.
      builder = DungeonBuilder(roomPool)
      dungeon <- IO.fromEither(
        builder
          .build(totalRooms = 4)
          .left
          .map(
            err => RuntimeException(s"Failed to build dungeon: $err")
          )
      )
      _ <- logger.info(s"Built dungeon: ${dungeon.rooms.keys.mkString(" → ")}")
      
      resolver = CombatResolver()
      stateMachine = StateMachine(dungeon, enemyStats, resolver)
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
