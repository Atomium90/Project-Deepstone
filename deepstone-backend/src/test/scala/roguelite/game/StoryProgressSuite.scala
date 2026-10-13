package roguelite.game

import munit.FunSuite

class StoryProgressSuite extends FunSuite:

  test("a save with no card has nothing to show"):
    assertEquals(StoryProgress.empty.pendingCard, None)

  test("an earned card is pending until it is seen"):
    val progress = StoryProgress.empty.withCardEarned("prologue")
    assertEquals(progress.pendingCard, Some("prologue"))
    assertEquals(progress.withCardSeen("prologue").pendingCard, None)

  test("cards are shown in the order they were earned, one at a time"):
    val progress = StoryProgress.empty.withCardEarned("ch1").withCardEarned("prologue")
    assertEquals(progress.pendingCard, Some("ch1"))
    assertEquals(progress.withCardSeen("ch1").pendingCard, Some("prologue"))

  test("earning a card again changes nothing, whether it was seen or not"):
    val earned = StoryProgress.empty.withCardEarned("ch1")
    assertEquals(earned.withCardEarned("ch1"), earned)
    val seen = earned.withCardSeen("ch1")
    assertEquals(seen.withCardEarned("ch1"), seen)

  test("a relic read is remembered, once however many times it is read"):
    val progress = StoryProgress.empty.withRelicRead(4).withRelicRead(4).withRelicRead(1)
    assertEquals(progress.relicsRead, Set(1, 4))

  test("seeing a card that was never earned changes nothing"):
    val progress = StoryProgress.empty.withCardEarned("ch1")
    assertEquals(progress.withCardSeen("ch2"), progress)
