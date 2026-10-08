package roguelite

import munit.FunSuite

import java.net.BindException
import java.nio.file.{ AccessDeniedException, Paths }
import java.sql.SQLException

class StartupErrorsSuite extends FunSuite:

  private val file = Paths.get("saves", "deepstone.db")

  /** SQLite's driver nests its error inside the connection pool's, as the real one does. */
  private def poolError(sqliteMessage: String): Throwable =
    RuntimeException("Failed to initialize pool", SQLException(sqliteMessage))

  test("a start-up failure is shown as its own message"):
    assertEquals(StartupErrors.explain(StartupFailure("Close the other window.")), "Close the other window.")

  test("any other error is explained as unexpected, with its type and message but no trace"):
    val text = StartupErrors.explain(IllegalStateException("rooms.json is empty"))
    assert(text.contains("unexpected problem"), text)
    assert(text.contains("IllegalStateException: rooms.json is empty"), text)
    assert(!text.contains("\tat "), text)

  test("an error with no message still reads well"):
    assert(StartupErrors.explain(NullPointerException()).contains("no details"))

  test("a damaged save file says so and says to keep a copy"):
    List("[SQLITE_NOTADB] File is not a database", "database disk image is malformed", "corrupt").foreach:
      reason =>
        val text = StartupErrors.saveFile(poolError(reason), file).message
        assert(text.contains("looks damaged"), text)
        assert(text.contains(file.toString), text)
        assert(text.contains("keep a copy"), text)

  test("a save file held by another program says to close it"):
    val text = StartupErrors.saveFile(poolError("[SQLITE_BUSY] The database file is locked"), file).message
    assert(text.contains("in use by another program"), text)
    assert(text.contains(file.toString), text)

  test("a read-only save file says to make it writable"):
    val text = StartupErrors.saveFile(poolError("[SQLITE_READONLY] Attempt to write a readonly database"), file).message
    assert(text.contains("read-only"), text)

  test("a save file that cannot be opened points at the folder and at --db"):
    val text = StartupErrors.saveFile(poolError("[SQLITE_CANTOPEN] Unable to open the database file"), file).message
    assert(text.contains("cannot open its save file"), text)
    assert(text.contains("--db <file>"), text)

  test("an unrecognised reason is passed on rather than guessed at"):
    val text = StartupErrors.saveFile(poolError("[SQLITE_FULL] Insertion failed because database is full"), file).message
    assert(text.contains("cannot use its save file"), text)
    assert(text.contains("database is full"), text)

  test("a save folder that cannot be written to points at the cause and at --db"):
    val text = StartupErrors.saveFolder(AccessDeniedException("C:\\Users\\player\\AppData\\Roaming\\Deepstone")).message
    assert(text.contains("cannot write to its save folder"), text)
    assert(text.contains("AccessDeniedException"), text)
    assert(text.contains("--db <file>"), text)

  test("a port taken at the last moment is explained like one found taken"):
    val text = StartupErrors.port(BindException("Address already in use"), 8081).message
    assert(text.contains("Port 8081 is already in use"), text)
    assert(text.contains("--port <number>"), text)
