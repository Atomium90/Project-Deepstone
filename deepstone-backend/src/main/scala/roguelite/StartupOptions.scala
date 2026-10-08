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
  */
final case class StartupOptions(debugMode: Boolean,
                                ignoredArgs: List[String],
                                databasePath: Option[String] = None
)

object StartupOptions:

  val DebugFlag = "--debug"

  val DatabaseFlag = "--db"

  def parse(args: List[String]): StartupOptions =
    @tailrec
    def loop(rest: List[String], options: StartupOptions): StartupOptions = rest match
      case Nil => options.copy(ignoredArgs = options.ignoredArgs.reverse)
      case DebugFlag :: tail => loop(tail, options.copy(debugMode = true))
      // A path never starts with "--", so "--db --debug" is a --db with no path, not a database
      // called "--debug": the flag is kept aside as unknown, and --debug still turns debug on.
      case DatabaseFlag :: path :: tail if !path.startsWith("--") =>
        loop(tail, options.copy(databasePath = Some(path)))
      case other :: tail => loop(tail, options.copy(ignoredArgs = other :: options.ignoredArgs))

    loop(args, StartupOptions(debugMode = false, ignoredArgs = Nil))
