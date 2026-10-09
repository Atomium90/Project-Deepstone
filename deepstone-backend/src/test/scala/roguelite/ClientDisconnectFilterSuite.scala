package roguelite

import ch.qos.logback.classic.{ Level, LoggerContext }
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.spi.FilterReply
import munit.FunSuite

import java.io.IOException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeoutException

class ClientDisconnectFilterSuite extends FunSuite:

  private val context     = new LoggerContext()
  private val emberLogger = "org.http4s.ember.server.EmberServerBuilderCompanionPlatform"
  private val message     = "WebSocket connection terminated with exception"

  private def event(loggerName: String, text: String, error: Throwable | Null): LoggingEvent =
    new LoggingEvent("test", context.getLogger(loggerName), Level.ERROR, text, error, null)

  private def decide(event: LoggingEvent): FilterReply = new ClientDisconnectFilter().decide(event)

  test("a connection that ended because the player closed the tab is dropped from the console"):
    assertEquals(decide(event(emberLogger, message, new IOException("Connection reset by peer"))), FilterReply.DENY)
    assertEquals(decide(event(emberLogger, message, new ClosedChannelException())), FilterReply.DENY)

  test("a reset connection wrapped in another exception is dropped too, as Ember reports it"):
    val wrapped = new RuntimeException("Multiple exceptions were thrown", new SocketException("Connection reset"))
    assertEquals(decide(event(emberLogger, message, wrapped)), FilterReply.DENY)

  test("a wrapper around an unrelated cause still shows"):
    val wrapped = new RuntimeException("Multiple exceptions were thrown", new TimeoutException("60 seconds"))
    assertEquals(decide(event(emberLogger, message, wrapped)), FilterReply.NEUTRAL)

  test("the same message with another cause still shows, such as a message over the size limit or a silent client"):
    assertEquals(decide(event(emberLogger, message, new TimeoutException("60 seconds"))), FilterReply.NEUTRAL)
    assertEquals(decide(event(emberLogger, message, new IllegalStateException("Frame length 9000 exceeds limit"))), FilterReply.NEUTRAL)

  test("the same message with no exception at all still shows"):
    assertEquals(decide(event(emberLogger, message, null)), FilterReply.NEUTRAL)

  test("any other line from Ember or from the game still shows, even with a connection exception"):
    assertEquals(decide(event(emberLogger, "Something else happened", new IOException("Connection reset"))), FilterReply.NEUTRAL)
    assertEquals(decide(event("roguelite.Main", message, new IOException("Connection reset"))), FilterReply.NEUTRAL)
