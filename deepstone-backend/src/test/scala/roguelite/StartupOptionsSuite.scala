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

  test("the save is deepstone.db unless told otherwise"):
    assertEquals(StartupOptions.parse(Nil).databasePath, "deepstone.db")

  test("--db <path> picks the file of the save, and is not an unknown argument"):
    val options = StartupOptions.parse(List("--db", "target/e2e.db"))
    assertEquals(options.databasePath, "target/e2e.db")
    assertEquals(options.ignoredArgs, Nil)

  test("--db and --debug work together, in either order"):
    val expected = StartupOptions(debugMode = true, ignoredArgs = Nil, databasePath = "other.db")
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
    assertEquals(StartupOptions.parse(List("--db", "a.db", "--db", "b.db")).databasePath, "b.db")

  test("unknown arguments keep the order they came in"):
    assertEquals(StartupOptions.parse(List("--one", "--debug", "--two")).ignoredArgs, List("--one", "--two"))
