package roguelite.engine

import cats.effect.{ IO, Ref }
import munit.CatsEffectSuite
import org.http4s.{ Header, Headers, HttpApp, Method, Request, Response, Status, Uri }
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class RequestGuardSuite extends CatsEffectSuite:

  given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val guard = RequestGuard(port = 8080)

  test("the Host must be the server itself, in either spelling and any letter case"):
    assert(guard.hostAllowed(List("127.0.0.1:8080")))
    assert(guard.hostAllowed(List("localhost:8080")))
    assert(guard.hostAllowed(List("LocalHost:8080")))

  test("a Host naming another domain, another port or no port is refused"):
    assert(!guard.hostAllowed(List("evil.example:8080")))
    assert(!guard.hostAllowed(List("evil.example")))
    assert(!guard.hostAllowed(List("127.0.0.1:9999")))
    assert(!guard.hostAllowed(List("127.0.0.1")))
    assert(!guard.hostAllowed(List("127.0.0.1:8080.evil.example")))

  test("a request with no Host, or with several, is refused"):
    assert(!guard.hostAllowed(Nil))
    assert(!guard.hostAllowed(List("127.0.0.1:8080", "evil.example")))

  test("no Origin is accepted: only a web page can be turned against the player"):
    assert(guard.originAllowed(Nil))

  test("the game's own page and the Vite dev server are allowed origins"):
    assert(guard.originAllowed(List("http://127.0.0.1:8080")))
    assert(guard.originAllowed(List("http://localhost:8080")))
    assert(guard.originAllowed(List("http://localhost:5173")))
    assert(guard.originAllowed(List("http://127.0.0.1:5173")))

  test("an Origin from another site, the null origin or another scheme or port is refused"):
    assert(!guard.originAllowed(List("http://evil.example")))
    assert(!guard.originAllowed(List("http://evil.example:8080")))
    assert(!guard.originAllowed(List("null")))
    assert(!guard.originAllowed(List("https://127.0.0.1:8080")))
    assert(!guard.originAllowed(List("http://127.0.0.1:9999")))
    assert(!guard.originAllowed(List("http://127.0.0.1:8080/")))
    assert(!guard.originAllowed(List("")))

  test("several Origin headers are refused, even when one of them is allowed"):
    assert(!guard.originAllowed(List("http://127.0.0.1:8080", "http://evil.example")))

  test("the guard follows the port it is given"):
    val other = RequestGuard(port = 9000)
    assert(other.hostAllowed(List("127.0.0.1:9000")))
    assert(!other.hostAllowed(List("127.0.0.1:8080")))
    assert(other.originAllowed(List("http://localhost:9000")))
    assert(!other.originAllowed(List("http://localhost:8080")))

  test("the extra origins replace the dev server ones"):
    val custom = RequestGuard(port = 8080, extraOrigins = Set("http://localhost:3000"))
    assert(custom.originAllowed(List("http://localhost:3000")))
    assert(!custom.originAllowed(List("http://localhost:5173")))
    assert(custom.originAllowed(List("http://127.0.0.1:8080")))

  private def request(headers: (String, String)*): Request[IO] =
    Request[IO](
      Method.GET,
      Uri.unsafeFromString("/ws"),
      headers = Headers(
        headers.map(
          (name, value) => Header.ToRaw.rawToRaw(Header.Raw(CIString(name), value))
        )*
      )
    )

  /** An app that counts the requests it receives, to tell a refusal from an answer. */
  private def countingApp(calls: Ref[IO, Int]): HttpApp[IO] =
    HttpApp[IO](
      _ => calls.update(_ + 1).as(Response[IO](Status.Ok))
    )

  private def run(req: Request[IO]): IO[(Status, Int)] =
    for
      calls  <- Ref.of[IO, Int](0)
      resp   <- guard(countingApp(calls)).run(req)
      count  <- calls.get
    yield (resp.status, count)

  test("a request from the game's own page goes through to the app"):
    run(request("Host" -> "127.0.0.1:8080", "Origin" -> "http://127.0.0.1:8080"))
      .assertEquals((Status.Ok, 1))

  test("a request with no Origin goes through to the app"):
    run(request("Host" -> "localhost:8080")).assertEquals((Status.Ok, 1))

  test("a request from another site is answered 403 and never reaches the app"):
    run(request("Host" -> "127.0.0.1:8080", "Origin" -> "http://evil.example"))
      .assertEquals((Status.Forbidden, 0))

  test("a request under a rebound domain is answered 403 and never reaches the app"):
    run(request("Host" -> "evil.example:8080", "Origin" -> "http://evil.example:8080"))
      .assertEquals((Status.Forbidden, 0))

  test("a request with no Host is answered 403 and never reaches the app"):
    run(request("Origin" -> "http://127.0.0.1:8080")).assertEquals((Status.Forbidden, 0))

  test("the null origin is answered 403"):
    run(request("Host" -> "127.0.0.1:8080", "Origin" -> "null")).assertEquals((Status.Forbidden, 0))
