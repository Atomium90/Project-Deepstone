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
