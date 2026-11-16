package roguelite.engine

import cats.effect.IO
import cats.syntax.foldable.*
import roguelite.db.Database
import roguelite.game.story.StoryProgress

/** Writes the story to the save. */
object StoryPersistence:

  /** Save what changed between two states of the story: the chapter and the last outcome, the scenes
    * newly played, every vow that is not as it was, the streak of deaths, the relics newly read, whether
    * Hardcore has just been opened and the cards newly earned or seen.
    */
  def save(database: Database, prev: StoryProgress, next: StoryProgress): IO[Unit] =
    for
      _ <- IO.whenA(next.chapter != prev.chapter || next.lastRun != prev.lastRun)(
        database.saveStoryState(next.chapter, next.lastRun)
      )
      _ <- (next.scenesSeen -- prev.scenesSeen).toList.traverse_(database.recordSceneSeen)
      _ <- next.vows.toList
        .filterNot((npc, record) => prev.vows.get(npc).contains(record))
        .traverse_((npc, record) => database.saveVow(npc, record))
      _ <- IO.whenA(next.deathStreak != prev.deathStreak)(database.saveDeathStreak(next.deathStreak))
      _ <- IO.whenA(next.hardcoreUnlocked && !prev.hardcoreUnlocked)(database.saveHardcoreUnlocked())
      _ <- next.ending.filter(prev.ending != Some(_)).traverse_(database.saveEnding)
      _ <- (next.relicsRead -- prev.relicsRead).toList.sorted.traverse_(database.recordRelicRead)
      _ <- next.cards.filterNot(card => prev.cards.exists(_.id == card.id)).traverse_(card => database.recordCardEarned(card.id))
      _ <- next.cards.filter(card => card.seen && !prev.cards.contains(card)).traverse_(card => database.markCardSeen(card.id))
    yield ()
