package roguelite.engine

/** What a client can make the server hold in memory, with a ceiling on each.
  *
  * The server is for one player on their own machine, so none of these is ever reached in play.
  * They exist so that a misbehaving page or program cannot grow the server's memory without bound.
  */
object ServerLimits:

  /** Largest message a client may send, in bytes. Ember refuses a bigger one as it reads it, before
    * the whole message is in memory. The biggest real action is a few hundred bytes.
    */
  val MaxWebSocketMessageBytes: Int = 8 * 1024

  /** Open connections, WebSocket and static files together. A connection beyond this waits for a
    * free slot. One page keeps a handful open (its files load in parallel, plus the WebSocket).
    */
  val MaxConnections: Int = 64

  /** Frames queued for one client. When a client stops reading, the queue fills and the server
    * stops reading that client's actions, instead of piling up answers.
    */
  val OutgoingQueueSize: Int = 64
