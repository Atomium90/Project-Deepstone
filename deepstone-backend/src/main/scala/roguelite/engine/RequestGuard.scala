package roguelite.engine

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.{ HttpApp, Request, Response, Status }
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

/** Keeps the local server for the player's own browser tab.
  *
  * The server listens on the player's machine only, but any web page open in the same browser can
  * still reach `127.0.0.1`, and a hostile domain can point its DNS name at it (DNS rebinding). Two
  * checks close that door:
  *   - the `Host` header must name the server itself (`127.0.0.1` or `localhost` on its port), which
  *     a rebound domain never does;
  *   - an `Origin` header, which browsers always send on a WebSocket handshake, must be the game's
  *     own page (or the Vite dev server). A page from another site, and the opaque `null` origin,
  *     are refused.
  *
  * A request with no `Origin` is accepted: it does not come from a web page (a command line tool, a
  * test client), and only a page can be used against the player.
  *
  * @param port
  *   The port the server listens on.
  * @param extraOrigins
  *   Origins allowed besides the server's own, the Vite dev server by default.
  */
final class RequestGuard(port: Int, extraOrigins: Set[String] = RequestGuard.DevOrigins):

  private val allowedHosts: Set[String] = Set(s"127.0.0.1:$port", s"localhost:$port")

  private val allowedOrigins: Set[String] =
    Set(s"http://127.0.0.1:$port", s"http://localhost:$port") ++ extraOrigins.map(_.toLowerCase)

  /** `hosts` are the values of the request's `Host` headers: exactly one, naming this server. */
  def hostAllowed(hosts: List[String]): Boolean = hosts match
    case host :: Nil => allowedHosts.contains(host.toLowerCase)
    case _           => false

  /** `origins` are the values of the request's `Origin` headers: none, or exactly one that is allowed. */
  def originAllowed(origins: List[String]): Boolean = origins match
    case Nil           => true
    case origin :: Nil => allowedOrigins.contains(origin.toLowerCase)
    case _             => false

  /** Answers 403 to a request that fails either check, without reaching `app`. */
  def apply(app: HttpApp[IO])(using logger: Logger[IO]): HttpApp[IO] =
    Kleisli:
      (req: Request[IO]) =>
        val hosts   = headerValues(req, "Host")
        val origins = headerValues(req, "Origin")
        if hostAllowed(hosts) && originAllowed(origins) then app.run(req)
        else
          logger.warn(
            s"Refused a request to ${req.uri.path}: Host ${describe(hosts)}, Origin ${describe(origins)}"
          ) *> IO.pure(Response[IO](Status.Forbidden).withEntity("Forbidden"))

  private def headerValues(req: Request[IO], name: String): List[String] =
    req.headers.get(CIString(name)).map(_.toList.map(_.value)).getOrElse(Nil)

  /** Header values come from the client: shortened before they reach the log. */
  private def describe(values: List[String]): String =
    if values.isEmpty then "none" else values.map(_.take(80)).mkString("'", "', '", "'")

object RequestGuard:

  /** The Vite dev server, which serves the page while the backend only holds the WebSocket. */
  val DevOrigins: Set[String] = Set("http://localhost:5173", "http://127.0.0.1:5173")
