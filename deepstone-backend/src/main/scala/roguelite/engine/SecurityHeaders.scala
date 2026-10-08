package roguelite.engine

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.{ Header, HttpApp }
import org.typelevel.ci.CIString

/** Browser hardening headers, put on every response of the server.
  *
  * The page is the game and nothing else, so the policy is closed: it loads its own files only,
  * runs no inline script and no `eval`, talks to nobody but this server, and cannot be framed by
  * another site.
  */
object SecurityHeaders:

  /** The one place to widen the policy if the game ever needs a new kind of resource.
    *
    * Styles keep `'unsafe-inline'` because the Svelte components set `style="..."` attributes;
    * scripts do not, which is what stops an injected script from running. `connect-src` names the
    * WebSocket explicitly since not every browser counts `'self'` as covering `ws://`.
    */
  def contentSecurityPolicy(port: Int): String =
    List(
      "default-src 'self'",
      "script-src 'self'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self'",
      "media-src 'self'",
      "font-src 'self'",
      s"connect-src 'self' ws://127.0.0.1:$port ws://localhost:$port",
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'none'",
      "frame-ancestors 'none'"
    ).mkString("; ")

  /** Header name and value, in the order they are added. */
  def headers(port: Int): List[(String, String)] =
    List(
      "Content-Security-Policy" -> contentSecurityPolicy(port),
      // The browser must trust the declared Content-Type and never guess a script from a file.
      "X-Content-Type-Options" -> "nosniff",
      // Older browsers ignore frame-ancestors but understand this one.
      "X-Frame-Options" -> "DENY",
      "Referrer-Policy" -> "no-referrer",
      "Permissions-Policy" -> "camera=(), microphone=(), geolocation=()"
    )

  /** Sets the headers on every response of `app`, replacing a header of the same name. */
  def apply(port: Int)(app: HttpApp[IO]): HttpApp[IO] =
    val added = headers(port).map(
      (name, value) => Header.ToRaw.rawToRaw(Header.Raw(CIString(name), value))
    )
    Kleisli:
      req => app.run(req).map(_.putHeaders(added*))
