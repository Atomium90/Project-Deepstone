package roguelite

import cats.effect.IO
import cats.effect.std.Console

/** Keeps the window open after a failure, so the message can be read.
  *
  * Started by a double-click, the game runs in a console window that closes the moment the program
  * ends, which would take the explanation with it. When the game is attached to a console it waits
  * for Enter first. Started from a build tool, a script or a pipe there is no console to wait on and
  * nothing is asked.
  */
object ConsolePause:

  def waitForEnter: IO[Unit] =
    IO.delay(System.console() != null).flatMap:
      attached =>
        pause(
          attached,
          prompt = Console[IO].println("Press Enter to close this window."),
          read = IO.blocking(scala.io.StdIn.readLine()).void
        )

  /** Prompts, then waits, but only when `interactive`. */
  def pause(interactive: Boolean, prompt: IO[Unit], read: IO[Unit]): IO[Unit] =
    IO.whenA(interactive)(prompt *> read)
