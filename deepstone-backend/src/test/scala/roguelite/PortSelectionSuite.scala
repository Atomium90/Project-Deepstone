package roguelite

import cats.effect.{ IO, Ref }
import munit.CatsEffectSuite

import java.net.{ InetAddress, ServerSocket }

class PortSelectionSuite extends CatsEffectSuite:

  /** A probe that says "busy" for the given ports and records every port it was asked about. */
  private def probe(busy: Set[Int]): IO[(Int => IO[Boolean], IO[List[Int]])] =
    Ref.of[IO, List[Int]](Nil).map:
      asked => (port => asked.update(port :: _).as(!busy(port)), asked.get.map(_.reverse))

  test("without --port the candidates are the ten ports from 8080"):
    assertEquals(PortSelection.candidates(None), List.range(8080, 8090))

  test("with --port the only candidate is that port"):
    assertEquals(PortSelection.candidates(Some(9000)), List(9000))

  test("a requested port that is free is the one chosen"):
    probe(Set.empty).flatMap:
      (isFree, _) => PortSelection.choose(Some(9000), isFree).assertEquals(9000)

  test("a requested port that is taken is never swapped for another: start-up fails and says what to do"):
    probe(Set(9000)).flatMap:
      (isFree, asked) =>
        PortSelection.choose(Some(9000), isFree).attempt.flatMap:
          result =>
            val failure = result.left.toOption.collect { case f: StartupFailure => f }
            assert(failure.exists(_.message.contains("Port 9000 is already in use")), result.toString)
            assert(failure.exists(_.message.contains("--port <number>")))
            asked.assertEquals(List(9000))

  test("without --port the default port is taken when it is free, and nothing else is probed"):
    probe(Set.empty).flatMap:
      (isFree, asked) =>
        PortSelection.choose(None, isFree).assertEquals(8080) *> asked.assertEquals(List(8080))

  test("without --port a taken default moves on to the next free port, in order"):
    probe(Set(8080, 8081, 8082)).flatMap:
      (isFree, asked) =>
        PortSelection.choose(None, isFree).assertEquals(8083) *>
          asked.assertEquals(List(8080, 8081, 8082, 8083))

  test("without --port and with all ten ports taken, start-up fails and names the range"):
    probe(PortSelection.candidates(None).toSet).flatMap:
      (isFree, _) =>
        PortSelection.choose(None, isFree).attempt.map:
          result =>
            val failure = result.left.toOption.collect { case f: StartupFailure => f }
            assert(failure.exists(_.message.contains("Ports 8080 to 8089 are all in use")), result.toString)

  test("the real probe sees a port held on the loopback address as taken, and a released one as free"):
    IO.blocking(new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))).flatMap:
      holder =>
        val port = holder.getLocalPort
        for
          whileHeld <- PortSelection.isFreeOnLoopback(port)
          _         <- IO.blocking(holder.close())
          afterwards <- PortSelection.isFreeOnLoopback(port)
        yield
          assert(!whileHeld, "a held port must be reported as taken")
          assert(afterwards, "a released port must be reported as free")
