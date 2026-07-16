package roguelite

import cats.effect.{ IO, IOApp }
import com.comcast.ip4s.{ host, port }
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger
import roguelite.engine.{ StateMachine, WebSocketRouter }
import roguelite.game.{
  ClassLoader,
  CombatResolver,
  DungeonBuilder,
  EnemyLoader,
  ItemLoader,
  RoomLoader
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

            _          <- logger.info("Loading game data...")
            roomPool   <- RoomLoader.loadAll()
            enemyStats <- EnemyLoader.loadAll()
            itemDefs   <- ItemLoader.loadAll()
            classDefs  <- ClassLoader.loadAll()
            _ <- logger.info(
              s"Loaded ${roomPool.size} rooms, ${enemyStats.size} enemy types, ${itemDefs.size} item types."
            )

            dungeon <- IO.fromEither(
              DungeonBuilder(roomPool)
                .build(totalRooms = 4)
                .left
                .map(
                  err => RuntimeException(s"Failed to build dungeon: $err")
                )
            )
            _ <- logger.info(s"Built dungeon: ${dungeon.rooms.keys.mkString(" → ")}")

            resolver     = CombatResolver(itemDefs = itemDefs)
            stateMachine = StateMachine(dungeon, enemyStats, itemDefs, classDefs, resolver)
            router       = WebSocketRouter(stateMachine, database, itemDefs)

            _ <- EmberServerBuilder
              .default[IO]
              .withHost(host"0.0.0.0")
              .withPort(port"8080")
              .withHttpWebSocketApp(
                wsb => router.routes(wsb).orNotFound
              )
              .build
              .useForever
              .onError(
                err => logger.error(err)("Server crashed")
              )
          yield ()
