package roguelite

import cats.effect.{ IO, IOApp }
import com.comcast.ip4s.{ host, port }
import org.http4s.ember.server.EmberServerBuilder
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
            _ <- logger.info(
              s"Loaded ${roomPool.size} rooms, ${enemyStats.size} enemy types, ${itemDefs.size} item types, " +
                s"${abilityDefs.size} abilities, ${upgradeDefs.size} upgrades, ${npcDialogueDefs.size} npc dialogues, " +
                s"${achievementDefs.size} achievements."
            )

            resolver = CombatResolver(itemDefs = itemDefs, abilityDefs = abilityDefs)
            stateMachine = StateMachine(roomPool,
                                        enemyStats,
                                        itemDefs,
                                        classDefs,
                                        upgradeDefs,
                                        resolver,
                                        npcDialogueDefs = npcDialogueDefs
            )
            router = WebSocketRouter(stateMachine, database, itemDefs, upgradeDefs, abilityDefs, achievementDefs)

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
