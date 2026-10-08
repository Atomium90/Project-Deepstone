package roguelite

/** A problem at start-up that the player can act on, with the explanation to show them.
  *
  * The message is plain language and says what to do. It is what gets printed, with no stack trace.
  */
final case class StartupFailure(message: String) extends RuntimeException(message)
