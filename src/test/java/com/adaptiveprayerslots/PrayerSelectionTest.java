package com.adaptiveprayerslots;

import java.util.EnumSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PrayerSelectionTest
{
	private static final MeleeFallbackChain DIRECT = MeleeFallbackChain.ULTIMATE_ONLY;
	private static final MeleeFallbackChain CHAIN = MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE;

	@Test
	public void selectsHighPrayersAtTheirRequirements()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			PrayerChoice high = swap.getHighPrayer();
			assertEquals(high, select(swap, DIRECT, high.getPrayerLevel(), high.getDefenceLevel(), null));
			assertEquals(swap.getDefaultFallback(),
				select(swap, DIRECT, high.getPrayerLevel() - 1, high.getDefenceLevel(), null));
			assertEquals(swap.getDefaultFallback(),
				select(swap, DIRECT, high.getPrayerLevel(), high.getDefenceLevel() - 1, null));
		}
	}

	@Test
	public void directMeleeChainSkipsUsableChivalry()
	{
		assertEquals(PrayerChoice.ULTIMATE_STRENGTH, select(PrayerSwap.MELEE, DIRECT, 69, 70, null));
	}

	@Test
	public void alternateMeleeChainUsesPriorityAndBothChivalryRequirements()
	{
		assertEquals(PrayerChoice.PIETY, select(PrayerSwap.MELEE, CHAIN, 70, 70, null));
		assertEquals(PrayerChoice.CHIVALRY, select(PrayerSwap.MELEE, CHAIN, 69, 70, null));
		assertEquals(PrayerChoice.CHIVALRY, select(PrayerSwap.MELEE, CHAIN, 60, 65, null));
		assertEquals(PrayerChoice.ULTIMATE_STRENGTH, select(PrayerSwap.MELEE, CHAIN, 59, 65, null));
		assertEquals(PrayerChoice.ULTIMATE_STRENGTH, select(PrayerSwap.MELEE, CHAIN, 60, 64, null));
	}

	@Test
	public void absentPermanentUnlockUsesFallbackOutsideLms()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			assertEquals(swap.getDefaultFallback(), PrayerSelection.select(
				swap, CHAIN, false, 99, 99, unlock -> false, null, prayer -> false));
		}
	}

	@Test
	public void eachHighPrayerRequiresItsOwnUnlock()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			PrayerUnlock required = swap.getHighPrayer().getUnlock();
			assertEquals(swap.getHighPrayer(), PrayerSelection.select(
				swap, DIRECT, false, 99, 99, unlock -> unlock == required, null, prayer -> false));
			assertEquals(swap.getDefaultFallback(), PrayerSelection.select(
				swap, DIRECT, false, 99, 99, unlock -> unlock != required, null, prayer -> false));
		}
	}

	@Test
	public void lmsIgnoresPermanentUnlocksButStillRequiresLevels()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			assertEquals(swap.getHighPrayer(), PrayerSelection.select(
				swap, DIRECT, true, 99, 99, unlock ->
				{
					throw new AssertionError("LMS must not read permanent unlocks");
				}, null, prayer -> false));
			assertEquals(swap.getDefaultFallback(), PrayerSelection.select(
				swap, DIRECT, true, 99, 50, unlock -> false, null, prayer -> false));
		}
		assertEquals(PrayerChoice.CHIVALRY, PrayerSelection.select(
			PrayerSwap.MELEE, CHAIN, true, 60, 65, unlock -> false, null, prayer -> false));
	}

	@Test
	public void belowFallbackRequirementLeavesSlotUncontrolled()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			PrayerChoice fallback = swap.getDefaultFallback();
			assertNull(select(swap, CHAIN, fallback.getPrayerLevel() - 1, 99, null));
			assertNull(select(swap, CHAIN, 99, 0, null));
			assertEquals(fallback, select(swap, CHAIN, fallback.getPrayerLevel(), 1, null));
		}
	}

	@Test
	public void unavailableSlotIsReleasedAndCanBecomeEligibleAgain()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			PrayerChoice current = select(swap, DIRECT, 99, 99, null);
			current = select(swap, DIRECT, 1, 1, current);
			assertNull(current);
			assertEquals(swap.getHighPrayer(), select(swap, DIRECT, 99, 99, current));
		}
	}

	@Test
	public void rememberedFallbackSurvivesUpgradeUntilDeactivated()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			PrayerChoice fallback = swap.getDefaultFallback();
			assertEquals(fallback, select(swap, DIRECT, 99, 99, fallback, fallback));
			assertEquals(swap.getHighPrayer(), select(swap, DIRECT, 99, 99, fallback));
		}
	}

	@Test
	public void startupAndReorderResumeDiscoverActiveFallbackWithoutMemory()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			PrayerChoice fallback = swap.getDefaultFallback();
			assertEquals(fallback, select(swap, DIRECT, 99, 99, null, fallback));
			assertEquals(swap.getHighPrayer(), select(swap, DIRECT, 99, 99, null));
		}
		assertEquals(PrayerChoice.CHIVALRY,
			select(PrayerSwap.MELEE, CHAIN, 99, 99, null, PrayerChoice.CHIVALRY));
	}

	@Test
	public void discoversActiveFallbackEvenWhenRememberedChoiceWasHigh()
	{
		assertEquals(PrayerChoice.ULTIMATE_STRENGTH,
			select(PrayerSwap.MELEE, DIRECT, 99, 99, PrayerChoice.PIETY, PrayerChoice.ULTIMATE_STRENGTH));
	}

	@Test
	public void chivalryChainPreservesActiveUltimateThroughBothUpgrades()
	{
		PrayerChoice fallback = PrayerChoice.ULTIMATE_STRENGTH;
		assertEquals(fallback, select(PrayerSwap.MELEE, CHAIN, 60, 65, fallback, fallback));
		assertEquals(PrayerChoice.CHIVALRY, select(PrayerSwap.MELEE, CHAIN, 60, 65, fallback));
		assertEquals(PrayerChoice.CHIVALRY,
			select(PrayerSwap.MELEE, CHAIN, 70, 70, PrayerChoice.CHIVALRY, PrayerChoice.CHIVALRY));
		assertEquals(PrayerChoice.PIETY, select(PrayerSwap.MELEE, CHAIN, 70, 70, PrayerChoice.CHIVALRY));
	}

	@Test
	public void removingChivalryFromChainDoesNotPreserveIt()
	{
		assertEquals(PrayerChoice.PIETY,
			select(PrayerSwap.MELEE, DIRECT, 99, 99, PrayerChoice.CHIVALRY, PrayerChoice.CHIVALRY));
		assertEquals(PrayerChoice.ULTIMATE_STRENGTH,
			select(PrayerSwap.MELEE, DIRECT, 60, 65, null, PrayerChoice.CHIVALRY));
	}

	@Test
	public void activeHighPrayerDoesNotPreventDowngrade()
	{
		for (PrayerSwap swap : PrayerSwap.values())
		{
			assertEquals(swap.getDefaultFallback(), select(
				swap, DIRECT, 99, 50, swap.getHighPrayer(), swap.getHighPrayer()));
		}
	}

	@Test
	public void unrelatedActivePrayersDoNotAffectSlot()
	{
		assertEquals(PrayerChoice.PIETY,
			select(PrayerSwap.MELEE, DIRECT, 99, 99, PrayerChoice.EAGLE_EYE, PrayerChoice.EAGLE_EYE));
		assertEquals(PrayerChoice.RIGOUR,
			select(PrayerSwap.RANGED, CHAIN, 99, 99, null, PrayerChoice.CHIVALRY));
	}

	@Test
	public void noEligiblePrayerTakesPrecedenceOverStaleActiveState()
	{
		assertNull(select(PrayerSwap.MELEE, DIRECT, 1, 1,
			PrayerChoice.ULTIMATE_STRENGTH, PrayerChoice.ULTIMATE_STRENGTH));
	}

	private PrayerChoice select(
		PrayerSwap swap, MeleeFallbackChain chain, int prayer, int defence,
		PrayerChoice previous, PrayerChoice... activePrayers)
	{
		EnumSet<PrayerChoice> active = EnumSet.noneOf(PrayerChoice.class);
		for (PrayerChoice choice : activePrayers)
		{
			active.add(choice);
		}
		return PrayerSelection.select(swap, chain, false, prayer, defence,
			unlock -> true, previous, active::contains);
	}
}
