package roguelite

import ch.qos.logback.classic.spi.{ ILoggingEvent, IThrowableProxy }
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

/** Keeps the console free of the line Ember logs when a player closes the tab.
  *
  * Ember reports every WebSocket that ends because the other side vanished as an error with a
  * stack trace, which reads as a crash to a player. Only that case is dropped: the same message
  * with any other cause (a message over the size limit, a client that stopped answering) still
  * shows.
  */
class ClientDisconnectFilter extends Filter[ILoggingEvent]:

  override def decide(event: ILoggingEvent): FilterReply =
    if ClientDisconnectFilter.isClientGone(event) then FilterReply.DENY else FilterReply.NEUTRAL

object ClientDisconnectFilter:

  private val EmberLoggerPrefix = "org.http4s.ember.server"

  private val Message = "WebSocket connection terminated with exception"

  /** The exceptions a connection that was closed by the other side ends with. */
  private val ConnectionClosedExceptions = Set(
    "java.io.IOException",
    "java.net.SocketException",
    "java.nio.channels.ClosedChannelException",
    "java.nio.channels.AsynchronousCloseException"
  )

  /** The exception and its causes, outermost first: a reset connection often arrives wrapped. */
  private def chain(proxy: IThrowableProxy | Null): Iterator[IThrowableProxy] =
    Iterator.iterate(proxy)(_.getCause).takeWhile(_ != null).map(_.nn)

  def isClientGone(event: ILoggingEvent): Boolean =
    event.getLoggerName.startsWith(EmberLoggerPrefix) &&
      event.getFormattedMessage == Message &&
      chain(event.getThrowableProxy).exists(proxy => ConnectionClosedExceptions.contains(proxy.getClassName))
