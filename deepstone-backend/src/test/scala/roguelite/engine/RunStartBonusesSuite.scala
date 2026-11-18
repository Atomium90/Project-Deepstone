package roguelite.engine

import munit.CatsEffectSuite
import roguelite.game.dungeon.{ Dungeon, Room, RoomType, Tile }
import roguelite.game.items.{ ItemLoader, Rarity, SetLoader }
import roguelite.game.progression.{ MetaProgression, PerkDef, PerkEffect, PerkLoader, UpgradeEffect, UpgradeLoader }

class RunStartBonusesSuite extends CatsEffectSuite:

  private val room = Room("r", RoomType.Combat, "dungeon", 1, 1, Vector(Vector(Tile.Floor)), Nil)

  private def start(difficulty: Difficulty = Difficulty.Easy, player: Player = PlayerFixtures.startingPlayer(ClassId.Warrior)) =
    ExplorationState(player, Dungeon.fromRooms(List(room)).toOption.get, playerX = 0, playerY = 0, difficulty = difficulty)

  private def metaWith(ids: String*): MetaProgression = MetaProgression(0, ids.toSet)

  private val bonuses =
    for
      upgrades <- UpgradeLoader.loadAll()
      items    <- ItemLoader.loadAll()
      sets     <- SetLoader.loadAll()
      perks    <- PerkLoader.loadAll()
    yield (upgrades, perks, RunStartBonuses(upgrades, items, sets, perks))

  test("a save that owns nothing starts the run as it is"):
    bonuses.map:
      (_, _, bonuses) =>
        val state = start()
        assertEquals(bonuses(state, metaWith()), state)

  test("every owned upgrade does what the catalog says, and an upgrade that only unlocks changes nothing"):
    bonuses.map:
      (upgrades, _, bonuses) =>
        val base = start().player
        upgrades.values.foreach:
          upgrade =>
            val player = bonuses(start(), metaWith(upgrade.id)).player
            upgrade.effect match
              case UpgradeEffect.MaxHpBoost(n) =>
                assertEquals(player.maxHp, base.maxHp + n, upgrade.id)
                assertEquals(player.hp, base.hp + n, upgrade.id)
              case UpgradeEffect.ExtraPotionSlot =>
                assertEquals(player.potionBelt.length, base.potionBelt.length + 1, upgrade.id)
              case UpgradeEffect.ExtraPotionCapacity =>
                assertEquals(player.potionCapacity, Player.UpgradedPotionCapacity, upgrade.id)
              case UpgradeEffect.FlatAttackBoost(n) =>
                assertEquals(player.bonusAttack, base.bonusAttack + n, upgrade.id)
              case UpgradeEffect.FlatDefenseBoost(n) =>
                assertEquals(player.bonusDefense, base.bonusDefense + n, upgrade.id)
              case UpgradeEffect.GuaranteedChestRarity(rarity: Rarity) =>
                assertEquals(player.chestRarityFloor, Some(rarity), upgrade.id)
              case UpgradeEffect.StartingItem(_) =>
                assertNotEquals(player, base, upgrade.id)
              case UpgradeEffect.UnlockClass(_) | UpgradeEffect.UnlockStartingKit(_) =>
                assertEquals(player, base, upgrade.id)

  test("a run that takes no blessing keeps its player whatever the save owns"):
    bonuses.map:
      (upgrades, _, bonuses) =>
        val state = start(Difficulty.Hardcore)
        assertEquals(bonuses(state, metaWith(upgrades.keys.toSeq*)), state)

  test("the max HP of a perk is worked out after the HP upgrades, so they cannot undo it"):
    bonuses.map:
      (upgrades, perks, bonuses) =>
        val malus = perks.values.filter(_.risky).flatMap:
          p => PerkDef.activeEffects(playerWith(p.id), perks).collectFirst { case PerkEffect.MaxHpPercent(n) => (p, n) }
        val (perk, percent) = malus.headOption.getOrElse(fail("expected a risky perk with a max HP effect"))
        val hpUpgrade = upgrades.values.collectFirst { case u if u.effect.isInstanceOf[UpgradeEffect.MaxHpBoost] => u }.get
        val boost     = hpUpgrade.effect.asInstanceOf[UpgradeEffect.MaxHpBoost].amount
        val base      = start().player
        val player    = bonuses(start(player = playerWith(perk.id)), metaWith(hpUpgrade.id)).player
        assertEquals(player.maxHp, math.round((base.maxHp + boost) * (100 + percent) / 100.0).toInt)
        assert(player.hp <= player.maxHp)

  private def playerWith(riskyPerkId: String): Player =
    PlayerFixtures.startingPlayer(ClassId.Warrior).copy(activeRiskyPerkId = Some(riskyPerkId))
