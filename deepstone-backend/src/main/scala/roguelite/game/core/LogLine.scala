package roguelite.game.core

/** A figure or a name that goes into a [[LogLine]]. A name is an id (an enemy type, an item type, an
  * ability id...), never a display name: the client looks the id up in its own language file.
  */
enum LogValue:
  case Text(value: String)
  case Num(value: Int)

/** One line of the log the player reads, as data: a key for the client's language file and the
  * values that go into the text (`log.combat.strike` with the enemy's type id and the damage). The
  * server never writes the words, so every language reads the same line.
  *
  * A line that has not been given a key yet is a [[LogLine.raw]] one: its text is the English the
  * code used to write, shown as it is. They are being replaced one resolver at a time.
  */
final case class LogLine(key: String, params: Map[String, LogValue] = Map.empty):

  /** The text of a raw line, None for a keyed one. */
  def rawText: Option[String] =
    if key == LogLine.RawKey then params.get("text").collect { case LogValue.Text(t) => t } else None

object LogLine:
  /** The key of a line that carries its own English text in the `text` param. */
  val RawKey = "log.raw"

  def raw(text: String): LogLine = LogLine(RawKey, Map("text" -> LogValue.Text(text)))

  /** A keyed line: `LogLine.of("log.combat.strike", "enemy" -> "goblin", "damage" -> 12)`. */
  def of(key: String, params: (String, String | Int)*): LogLine =
    LogLine(key, params.map((name, value) => name -> valueOf(value)).toMap)

  /** The [[LogValue]] of a text or a figure. */
  def valueOf(value: String | Int): LogValue = value match
    case s: String => LogValue.Text(s)
    case n: Int    => LogValue.Num(n)
