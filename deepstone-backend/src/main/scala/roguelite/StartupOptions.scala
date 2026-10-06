package roguelite

/** What the command line asked of the server.
  *
  * @param debugMode
  *   Turns on the dev-only Debug Rooms tooling: the hub lists the rooms converted into
  *   `debug-rooms/` and loads one on click. Without it the server runs the normal game, with nothing
  *   of that tooling exposed.
  * @param ignoredArgs
  *   Every argument that is not a known option, kept so the server can warn about a typo instead of
  *   silently running without the option the user meant.
  */
final case class StartupOptions(debugMode: Boolean, ignoredArgs: List[String])

object StartupOptions:

  val DebugFlag = "--debug"

  def parse(args: List[String]): StartupOptions =
    StartupOptions(debugMode = args.contains(DebugFlag), ignoredArgs = args.filterNot(_ == DebugFlag))
