package roguelite.engine

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{ Header, HttpApp, Method, Request, Response, Status, Uri }
import org.typelevel.ci.CIString

class SecurityHeadersSuite extends CatsEffectSuite:

  private val get = Request[IO](Method.GET, Uri.unsafeFromString("/"))

  private def headerValues(response: Response[IO], name: String): List[String] =
    response.headers.get(CIString(name)).map(_.toList.map(_.value)).getOrElse(Nil)

  /** The policy as directive name -> its sources, split the way a browser reads it. */
  private def directives(port: Int): Map[String, List[String]] =
    SecurityHeaders
      .contentSecurityPolicy(port)
      .split("; ")
      .map(_.split(" ").toList)
      .map(parts => parts.head -> parts.tail)
      .toMap

  test("every response carries the five headers"):
    val app = SecurityHeaders(8080)(HttpApp.pure(Response[IO](Status.Ok)))
    app.run(get).map:
      response =>
        SecurityHeaders.headers(8080).foreach:
          (name, value) => assertEquals(headerValues(response, name), List(value), name)
        assertEquals(SecurityHeaders.headers(8080).size, 5)

  test("an error response carries them too"):
    val app = SecurityHeaders(8080)(HttpApp.pure(Response[IO](Status.NotFound)))
    app.run(get).map:
      response =>
        assertEquals(response.status, Status.NotFound)
        assertEquals(headerValues(response, "X-Content-Type-Options"), List("nosniff"))
        assertEquals(headerValues(response, "Content-Security-Policy").size, 1)

  test("a header the app already set is replaced, not doubled"):
    val inner = HttpApp.pure(
      Response[IO](Status.Ok).putHeaders(Header.ToRaw.rawToRaw(Header.Raw(CIString("X-Frame-Options"), "SAMEORIGIN")))
    )
    SecurityHeaders(8080)(inner).run(get).map:
      response => assertEquals(headerValues(response, "X-Frame-Options"), List("DENY"))

  test("the body and status of the app's response are untouched"):
    val inner = HttpApp.pure(Response[IO](Status.Created).withEntity("hello"))
    SecurityHeaders(8080)(inner).run(get).flatMap:
      response =>
        assertEquals(response.status, Status.Created)
        response.as[String].assertEquals("hello")

  test("everything loads from the game's own site by default"):
    assertEquals(directives(8080)("default-src"), List("'self'"))

  test("no inline script and no eval: script-src is the site's own files only"):
    assertEquals(directives(8080)("script-src"), List("'self'"))

  test("inline styles are allowed, as the Svelte components set style attributes"):
    assertEquals(directives(8080)("style-src"), List("'self'", "'unsafe-inline'"))

  test("the page can reach this server's WebSocket on its port and nobody else"):
    assertEquals(
      directives(8080)("connect-src"),
      List("'self'", "ws://127.0.0.1:8080", "ws://localhost:8080")
    )
    assert(directives(9000)("connect-src").contains("ws://127.0.0.1:9000"))
    assert(!directives(9000)("connect-src").contains("ws://127.0.0.1:8080"))

  test("no other site can frame the game, and plugins, forms and a new base URL are closed"):
    val policy = directives(8080)
    assertEquals(policy("frame-ancestors"), List("'none'"))
    assertEquals(policy("object-src"), List("'none'"))
    assertEquals(policy("form-action"), List("'none'"))
    assertEquals(policy("base-uri"), List("'self'"))
