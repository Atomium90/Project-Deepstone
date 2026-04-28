package roguelite.engine

import cats.effect.IO
import fs2.Pipe
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.{Close, Text}
import org.typelevel.log4cats.Logger

/** Builds the HTTP routes for the game server.
 *
 * Currently exposes a single WebSocket endpoint at `GET /ws`.
 * Every connection gets its own [[GameSession]].
 */
class WebSocketRouter(stateMachine: StateMachine)(using logger: Logger[IO]):

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "ws" =>
        GameSession.create(stateMachine).flatMap: session =>
          // Send the current state immediately on connect
          val onConnect: IO[WebSocketFrame] =
            session.currentUpdate.map(update => Text(MessageProtocol.encodeUpdate(update)))

          // Incoming frames from the client → handle action → produce response frame
          val handleFrame: Pipe[IO, WebSocketFrame, WebSocketFrame] =
            incoming =>
              val initial = fs2.Stream.eval(onConnect)
              val responses = incoming.evalMap:
                case Text(body, _) =>
                  MessageProtocol.decodeAction(body) match {
                    case Right(action) =>
                      session.handle(action).map(update => Text(MessageProtocol.encodeUpdate(update)))
                    case Left(err) =>
                      logger.warn(s"Failed to decode action: $err") *>
                        session.currentUpdate.map(update => Text(MessageProtocol.encodeUpdate(update)))
                  }

                case Close(_) =>
                  logger.info("Client disconnected") *>
                    IO.pure(Close())
                case other =>
                  logger.warn(s"Unexpected frame type: $other") *>
                    session.currentUpdate.map(update => Text(MessageProtocol.encodeUpdate(update)))
              initial ++ responses

          wsb.build(handleFrame)
