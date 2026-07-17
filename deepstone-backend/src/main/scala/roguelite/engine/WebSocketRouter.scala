package roguelite.engine

import cats.effect.IO
import cats.effect.std.Queue
import fs2.{ Pipe, Stream }
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.{ Close, Ping, Pong, Text }
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*
import roguelite.db.Database
import roguelite.game.Item
import roguelite.game.AbilityDef

/** Builds the HTTP routes for the game server.
  *
  * Exposes a single WebSocket endpoint at `GET /ws`. Every connection gets its own
  * [[GameSession]] backed by the shared [[Database]] and reference data maps.
  */
class WebSocketRouter(stateMachine: StateMachine,
                      database: Database,
                      itemDefs: Map[String, Item],
                      abilityDefs: Map[ClassId, AbilityDef]
)(using logger: Logger[IO]):

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "ws" =>
        for
          session <- GameSession.create(stateMachine, database, itemDefs, abilityDefs)
          // Unbounded queue used to push outgoing frames from the receive handler
          outgoing <- Queue.unbounded[IO, WebSocketFrame]
          // Seed the queue with the initial state so the client receives it immediately on connect, before sending any action
          initial <- session.currentUpdate.map(
            u => Text(MessageProtocol.encodeUpdate(u))
          )
          _ <- outgoing.offer(initial)
          response <- wsb.build(send = buildSend(outgoing),
                                receive = receiveFrames(session, outgoing)
          )
        yield response

  private val HEARTBEAT_DURATION: FiniteDuration = 30.seconds

  /** Send stream: game frames from the queue merged with a 30-second heartbeat.
    *
    * The heartbeat sends a [[Ping]] frame periodically. Browsers respond automatically with a
    * [[Pong]], resetting Ember's idle timer and keeping the connection alive regardless of player
    * inactivity.
    */
  private def buildSend(outgoing: Queue[IO, WebSocketFrame]): Stream[IO, WebSocketFrame] =
    val gameFrames = Stream.fromQueueUnterminated(outgoing)
    val heartbeat  = Stream.awakeEvery[IO](HEARTBEAT_DURATION).as(Ping())
    gameFrames.merge(heartbeat)

  /** Process incoming WebSocket frames and push responses onto the outgoing queue. */
  private def receiveFrames(session: GameSession,
                            outgoing: Queue[IO, WebSocketFrame]
  ): Pipe[IO, WebSocketFrame, Unit] =
    _.evalMap:
      case Text(body, _) =>
        val response: IO[WebSocketFrame] =
          MessageProtocol.decodeAction(body) match {
            case Right(action) =>
              session
                .handle(action)
                .map(
                  u => Text(MessageProtocol.encodeUpdate(u))
                )
                .handleErrorWith:
                  err =>
                    logger.error(err)("Error resolving action") *>
                      session.currentUpdate.map(
                        u => Text(MessageProtocol.encodeUpdate(u))
                      )
            case Left(err) =>
              logger.warn(s"Failed to decode action: $err") *>
                session.currentUpdate.map(
                  u => Text(MessageProtocol.encodeUpdate(u))
                )
          }
        response.flatMap(outgoing.offer)

      case Pong(_) =>
        IO.unit // Heartbeat acknowledged -> connection is alive

      case Close(_) =>
        logger.info("Client disconnected")

      case other =>
        logger.warn(s"Unexpected WebSocket frame: ${other.getClass.getSimpleName}")
