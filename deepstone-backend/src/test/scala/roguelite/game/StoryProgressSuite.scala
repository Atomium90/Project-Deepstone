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

  test("a save that has not met the light has no ending, keeps its shop open and has lost no NPC"):
    val fresh = StoryProgress.empty
    assertEquals(fresh.ending, None)
    assertEquals(fresh.endingIsFinal, false)
    assertEquals(fresh.shopClosed, false)
    assertEquals(fresh.replacedNpc, None)

  test("entering the light is not final, and shuts nothing"):
    val entered = StoryProgress.empty.copy(ending = Some(Ending.Entered))
    assertEquals(entered.endingIsFinal, false)
    assertEquals(entered.shopClosed, false)
    assertEquals(entered.replacedNpc, None)

  test("someone taking the player's place is final, names who left, and leaves the shop open"):
    val replaced = StoryProgress.empty.copy(ending = Some(Ending.Replaced("npc.5")))
    assertEquals(replaced.endingIsFinal, true)
    assertEquals(replaced.replacedNpc, Some("npc.5"))
    assertEquals(replaced.shopClosed, false)

  test("refusing the light is final and shuts the shop for good"):
    val refused = StoryProgress.empty.copy(ending = Some(Ending.Refused))
    assertEquals(refused.endingIsFinal, true)
    assertEquals(refused.shopClosed, true)
    assertEquals(refused.replacedNpc, None)
