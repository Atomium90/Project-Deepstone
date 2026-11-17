package roguelite

import roguelite.game.core.LogLine

/** Assertions on the log of a transition, by key: the server sends no words, so a test checks which
  * line was written and with what values, never an English sentence.
  */
object LogLines:
  extension (log: List[LogLine])
    def keys: List[String] = log.map(_.key)

    def hasKey(key: String): Boolean = log.exists(_.key == key)

    /** A line with this key whose params include every one given, ids as text and figures as numbers. */
    def has(key: String, params: (String, String | Int)*): Boolean =
      log.exists(line =>
        line.key == key && params.forall((name, value) => line.params.get(name).contains(LogLine.valueOf(value)))
      )

    /** A line that has no key yet (or a dev-only one) and carries its English text as it is. */
    def rawContains(text: String): Boolean = log.exists(_.rawText.exists(_.contains(text)))
