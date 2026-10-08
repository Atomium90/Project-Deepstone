package roguelite

import scala.annotation.tailrec

/** What the command line asked of the server.
  *
  * @param debugMode
  *   Turns on the dev-only Debug Rooms tooling: the hub lists the rooms converted into
  *   `debug-rooms/` and loads one on click. Without it the server runs the normal game, with nothing
  *   of that tooling exposed.
  * @param ignoredArgs
  *   Every argument that is not a known option, kept so the server can warn about a typo instead of
  *   silently running without the option the user meant.
  * @param databasePath
  *   The SQLite file the save lives in when `--db <path>` names one. Without it the save is in the
  *   player's data folder (see [[SaveLocation]]). The end to end tests point it at a file of their
  *   own, so they never play on a real save.
  * @param port
  *   The port `--port <number>` asked for. Without it the server takes the first free port from 8080
  *   (see [[PortSelection]]); the dev setup passes 8080, as its page is wired to that port.
  */
final case class StartupOptions(debugMode: Boolean,
                                ignoredArgs: List[String],
                                databasePath: Option[String] = None,
                                port: Option[Int] = None
)

object StartupOptions:

  val DebugFlag = "--debug"

  val DatabaseFlag = "--db"

  val PortFlag = "--port"

  /** A port number as typed: digits only, between 1 and 65535. */
  private def validPort(text: String): Option[Int] =
    Option.when(text.matches("\\d{1,5}"))(text.toInt).filter(port => port >= 1 && port <= 65535)

  def parse(args: List[String]): StartupOptions =
    @tailrec
    def loop(rest: List[String], options: StartupOptions): StartupOptions = rest match
      case Nil => options.copy(ignoredArgs = options.ignoredArgs.reverse)
      case DebugFlag :: tail => loop(tail, options.copy(debugMode = true))
      // A path never starts with "--", so "--db --debug" is a --db with no path, not a database
      // called "--debug": the flag is kept aside as unknown, and --debug still turns debug on.
      case DatabaseFlag :: path :: tail if !path.startsWith("--") =>
        loop(tail, options.copy(databasePath = Some(path)))
      // A port that is not a number from 1 to 65535 is kept aside with its flag, like a typo.
      case PortFlag :: value :: tail if validPort(value).isDefined =>
        loop(tail, options.copy(port = validPort(value)))
      case other :: tail => loop(tail, options.copy(ignoredArgs = other :: options.ignoredArgs))

    loop(args, StartupOptions(debugMode = false, ignoredArgs = Nil))
