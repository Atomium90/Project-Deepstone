package roguelite

import cats.effect.IO
import cats.syntax.foldable.*

import java.io.IOException
import java.net.{ InetAddress, ServerSocket }
import scala.util.Using

/** Which port the server listens on.
  *
  * 8080 is a common port, so another program may already hold it on the player's machine. Without
  * `--port` the server takes the first free port from 8080 up. With `--port` it takes that port or
  * says it cannot: a port the player asked for is never swapped for another.
  */
object PortSelection:

  val DefaultPort = 8080

  /** How many ports are tried when none was asked for, the default included. */
  private val AttemptCount = 10

  def candidates(requested: Option[Int]): List[Int] = requested match
    case Some(port) => List(port)
    case None       => List.range(DefaultPort, DefaultPort + AttemptCount)

  /** The first candidate `isFree` accepts, or a [[StartupFailure]] saying what to do. */
  def choose(requested: Option[Int], isFree: Int => IO[Boolean]): IO[Int] =
    candidates(requested)
      .collectFirstSomeM(port => isFree(port).map(Option.when(_)(port)))
      .flatMap(IO.fromOption(_)(StartupFailure(unavailableMessage(requested))))

  def unavailableMessage(requested: Option[Int]): String = requested match
    case Some(port) =>
      s"Port $port is already in use by another program. Close that program, or start Deepstone " +
        "on another port with --port <number>."
    case None =>
      s"Ports $DefaultPort to ${DefaultPort + AttemptCount - 1} are all in use by other programs. " +
        "Close one of them, or choose a free port with --port <number>."

  /** Whether the port can be taken on the loopback address, which is the only one the server uses. */
  def isFreeOnLoopback(port: Int): IO[Boolean] =
    IO.blocking:
      try
        Using.resource(new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")))(_ => true)
      catch case _: IOException => false
