package roguelite.game

import roguelite.engine.Difficulty

/** One chapter of the story, loaded from `data/chapters.json`. A victory opens the next chapter only
  * when it was won on `minDifficulty` or a harder one.
  */
case class ChapterDef(id: Int, minDifficulty: Difficulty)
