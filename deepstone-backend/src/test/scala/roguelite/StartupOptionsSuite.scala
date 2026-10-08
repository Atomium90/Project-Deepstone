package roguelite

import munit.FunSuite

class StartupOptionsSuite extends FunSuite:

  test("no argument runs the normal game"):
    assertEquals(StartupOptions.parse(Nil), StartupOptions(debugMode = false, ignoredArgs = Nil))

  test("--debug turns the debug mode on"):
    assertEquals(StartupOptions.parse(List("--debug")), StartupOptions(debugMode = true, ignoredArgs = Nil))

  test("an unknown argument is kept aside and does not turn the debug mode on"):
    assertEquals(StartupOptions.parse(List("--debgu")), StartupOptions(debugMode = false, ignoredArgs = List("--debgu")))

  test("a known and an unknown argument are told apart"):
    assertEquals(
      StartupOptions.parse(List("--other", "--debug")),
      StartupOptions(debugMode = true, ignoredArgs = List("--other"))
    )

  test("without --db no save file is named, which leaves the choice to the player's data folder"):
    assertEquals(StartupOptions.parse(Nil).databasePath, None)

  test("--db <path> picks the file of the save, and is not an unknown argument"):
    val options = StartupOptions.parse(List("--db", "target/e2e.db"))
    assertEquals(options.databasePath, Some("target/e2e.db"))
    assertEquals(options.ignoredArgs, Nil)

  test("--db and --debug work together, in either order"):
    val expected = StartupOptions(debugMode = true, ignoredArgs = Nil, databasePath = Some("other.db"))
    assertEquals(StartupOptions.parse(List("--db", "other.db", "--debug")), expected)
    assertEquals(StartupOptions.parse(List("--debug", "--db", "other.db")), expected)

  test("--db with no path is kept aside as unknown and the default save is used"):
    assertEquals(
      StartupOptions.parse(List("--db")),
      StartupOptions(debugMode = false, ignoredArgs = List("--db"))
    )

  test("--db followed by another flag does not take the flag as a path"):
    assertEquals(
      StartupOptions.parse(List("--db", "--debug")),
      StartupOptions(debugMode = true, ignoredArgs = List("--db"))
    )

  test("the last --db wins"):
    assertEquals(StartupOptions.parse(List("--db", "a.db", "--db", "b.db")).databasePath, Some("b.db"))

  test("without --port no port is named, which leaves the choice to the first free one"):
    assertEquals(StartupOptions.parse(Nil).port, None)

  test("--port <number> picks the port, and is not an unknown argument"):
    val options = StartupOptions.parse(List("--port", "9000"))
    assertEquals(options.port, Some(9000))
    assertEquals(options.ignoredArgs, Nil)

  test("the ends of the valid range are accepted"):
    assertEquals(StartupOptions.parse(List("--port", "1")).port, Some(1))
    assertEquals(StartupOptions.parse(List("--port", "65535")).port, Some(65535))

  test("a port that is not a number from 1 to 65535 is kept aside with its flag"):
    List("abc", "0", "65536", "-1", "80a", "+8080", "8080.5", "").foreach:
      value =>
        val options = StartupOptions.parse(List("--port", value))
        assertEquals(options.port, None, value)
        assertEquals(options.ignoredArgs, List("--port", value), value)

  test("--port with no value is kept aside"):
    assertEquals(StartupOptions.parse(List("--port")), StartupOptions(debugMode = false, ignoredArgs = List("--port")))

  test("--port, --db and --debug work together, in any order"):
    val expected =
      StartupOptions(debugMode = true, ignoredArgs = Nil, databasePath = Some("a.db"), port = Some(8080))
    assertEquals(StartupOptions.parse(List("--port", "8080", "--db", "a.db", "--debug")), expected)
    assertEquals(StartupOptions.parse(List("--debug", "--db", "a.db", "--port", "8080")), expected)

  test("the last --port wins"):
    assertEquals(StartupOptions.parse(List("--port", "9000", "--port", "9001")).port, Some(9001))

  test("unknown arguments keep the order they came in"):
    assertEquals(StartupOptions.parse(List("--one", "--debug", "--two")).ignoredArgs, List("--one", "--two"))
