package roguelite

import java.net.BindException
import java.nio.file.Path

/** What to tell the player when the game cannot start.
  *
  * Each message says what went wrong in plain language and what to do about it, with no stack
  * trace. The technical detail stays in the log.
  */
object StartupErrors:

  /** The folder for the save could not be created, or an older save could not be copied into it. */
  def saveFolder(error: Throwable): StartupFailure =
    StartupFailure(
      s"Deepstone cannot write to its save folder (${describe(error)}). Check that the folder is " +
        "not read-only and that the disk is not full, or keep the save elsewhere with --db <file>."
    )

  /** The save file could not be opened. SQLite words the reason in its message, wherever it is nested. */
  def saveFile(error: Throwable, file: Path): StartupFailure =
    val details = causes(error).flatMap(cause => Option(cause.getMessage)).mkString(" ").toLowerCase
    def mentions(words: String*): Boolean = words.exists(details.contains)
    val message =
      if mentions("not a database", "malformed", "corrupt") then
        s"The save file $file looks damaged. Move it away to start a new game, and keep a copy of it: " +
          "it may still be recoverable."
      else if mentions("locked", "busy") then
        s"The save file $file is in use by another program, maybe another Deepstone window. Close it " +
          "and start again."
      else if mentions("readonly", "read-only") then
        s"The save file $file is read-only. Make it writable, or keep the save elsewhere with --db <file>."
      else if mentions("unable to open", "cantopen") then
        s"Deepstone cannot open its save file $file. Check that its folder exists and that you can " +
          "write to it, or keep the save elsewhere with --db <file>."
      else s"Deepstone cannot use its save file $file (${describe(error)})."
    StartupFailure(message)

  /** The port was taken between the moment it was found free and the moment the server bound it. */
  def port(error: BindException, port: Int): StartupFailure =
    StartupFailure(PortSelection.unavailableMessage(Some(port)))

  /** The text to print for whatever stopped the game. */
  def explain(error: Throwable): String = error match
    case StartupFailure(message) => message
    case other => s"Deepstone stopped because of an unexpected problem: ${describe(other)}."

  /** The error, and the cause it wraps when there is one: a pool or a framework often hides the real reason. */
  private def describe(error: Throwable): String =
    def line(e: Throwable): String = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("no details")}"
    val root = causes(error).last
    if root eq error then line(error) else s"${line(error)}, caused by ${line(root)}"

  /** The error and its causes, outermost first. */
  private def causes(error: Throwable): List[Throwable] =
    Iterator.iterate(error)(_.getCause).takeWhile(_ != null).toList
