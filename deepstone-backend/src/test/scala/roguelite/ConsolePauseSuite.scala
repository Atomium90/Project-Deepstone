package roguelite

import cats.effect.{ IO, Ref }
import munit.CatsEffectSuite

class ConsolePauseSuite extends CatsEffectSuite:

  /** Runs `pause` and returns the order in which the prompt and the read happened. */
  private def run(interactive: Boolean): IO[List[String]] =
    Ref.of[IO, List[String]](Nil).flatMap:
      steps =>
        ConsolePause
          .pause(interactive, prompt = steps.update("prompt" :: _), read = steps.update("read" :: _))
          .productR(steps.get.map(_.reverse))

  test("attached to a console, it asks for Enter and then waits for it"):
    run(interactive = true).assertEquals(List("prompt", "read"))

  test("with no console, it asks nothing and waits for nothing"):
    run(interactive = false).assertEquals(Nil)

  // Under a build tool or a CI job there is no console, so this must come back at once. Run from a
  // terminal that has one, the real prompt would wait for a key, so the check is skipped there.
  test("waitForEnter returns at once when the program has no console"):
    IO.delay(System.console() == null).flatMap:
      noConsole => if noConsole then ConsolePause.waitForEnter else IO.unit
