package roguelite

import cats.effect.IO
import org.typelevel.log4cats.Logger

import java.nio.file.{ Files, Path, Paths, StandardCopyOption }

/** Where the save file lives.
  *
  * Without `--db`, the save goes in the player's own data folder, so it stays the same wherever the
  * game is unzipped or started from. It used to be written in whatever folder the game was
  * launched from, which split a save across folders and lost it when the game moved.
  */
object SaveLocation:

  val FileName = "deepstone.db"

  /** SQLite's side files, which belong to the save while a write is in progress or was cut short. */
  private val SideFileSuffixes = List("-journal", "-wal", "-shm")

  /** Name of the copy in progress, next to the save it will become. */
  private val PartialSuffix = ".copying"

  /** The folder for the game's data on this system: `%APPDATA%\Deepstone` on Windows,
    * `~/Library/Application Support/Deepstone` on macOS, `$XDG_DATA_HOME/deepstone` (or
    * `~/.local/share/deepstone`) elsewhere.
    */
  def dataDirectory(osName: String, env: Map[String, String], home: Path): Path =
    val os = osName.toLowerCase
    def fromEnv(name: String): Option[Path] = env.get(name).filter(_.nonEmpty).map(Paths.get(_))
    if os.startsWith("windows") then
      fromEnv("APPDATA").getOrElse(home.resolve("AppData").resolve("Roaming")).resolve("Deepstone")
    else if os.contains("mac") || os.contains("darwin") then
      home.resolve("Library").resolve("Application Support").resolve("Deepstone")
    else fromEnv("XDG_DATA_HOME").getOrElse(home.resolve(".local").resolve("share")).resolve("deepstone")

  /** The save file to open.
    *
    * An explicit `--db` path is used as given, untouched. Otherwise the file is in `dataDirectory`,
    * which is created if needed. A save left by an earlier version in `workingDirectory` is copied
    * there the first time, never moved: the old file stays where it was, and a save already in the
    * data folder is never overwritten.
    */
  def resolve(explicit: Option[String], workingDirectory: Path, dataDirectory: Path)(using
      logger: Logger[IO]
  ): IO[Path] =
    explicit match
      case Some(path) => IO.pure(Paths.get(path))
      case None =>
        val target = dataDirectory.resolve(FileName)
        val legacy = workingDirectory.resolve(FileName)
        for
          _       <- IO.blocking(Files.createDirectories(dataDirectory))
          migrate <- IO.blocking(!Files.exists(target) && Files.exists(legacy))
          _ <- IO.whenA(migrate)(
            copyWithSideFiles(legacy, target) *>
              logger.info(s"Copied your save from $legacy to $target. The old file is left where it was.")
          )
        yield target

  /** The save appears under its real name only once it is whole: the side files go first, the file
    * itself is copied under a temporary name and then renamed. A copy cut short therefore leaves no
    * save behind, and the next start copies again instead of finding half of one.
    */
  private def copyWithSideFiles(from: Path, to: Path): IO[Unit] =
    IO.blocking:
      SideFileSuffixes.foreach:
        suffix =>
          val side = Paths.get(from.toString + suffix)
          if Files.exists(side) then
            Files.copy(side, Paths.get(to.toString + suffix), StandardCopyOption.REPLACE_EXISTING)
      val partial = Paths.get(to.toString + PartialSuffix)
      Files.copy(from, partial, StandardCopyOption.REPLACE_EXISTING)
      Files.move(partial, to, StandardCopyOption.ATOMIC_MOVE)
