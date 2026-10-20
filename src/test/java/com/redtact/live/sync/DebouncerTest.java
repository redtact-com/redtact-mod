package com.redtact.live.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DebouncerTest {
	@Test
	void nothingToSendWithoutChanges() {
		Debouncer d = new Debouncer(3000, 10000);
		assertFalse(d.isDirty());
		assertFalse(d.due(100_000));
		assertNull(d.take());
	}

	@Test
	void firesAfterQuietPeriod() {
		Debouncer d = new Debouncer(3000, 10000);
		d.markChanged(1000);
		assertTrue(d.isDirty());
		assertFalse(d.due(3999));
		assertTrue(d.due(4000));
	}

	@Test
	void eachChangeRestartsQuietPeriod() {
		Debouncer d = new Debouncer(3000, 10000);
		d.markChanged(0);
		d.markChanged(2000);
		assertFalse(d.due(4000));
		assertTrue(d.due(5000));
	}

	/** クロックのように変化が止まらなくても、最初の変化から maxDelay で送る。 */
	@Test
	void continuousChangesFireAtMaxDelay() {
		Debouncer d = new Debouncer(3000, 10000);

		for (long t = 0; t < 10_000; t += 500) {
			d.markChanged(t);
			assertFalse(d.due(t), "t=" + t);
		}

		d.markChanged(10_000);
		assertTrue(d.due(10_000));
	}

	@Test
	void takeClearsAndLaterChangesStartNewWindow() {
		Debouncer d = new Debouncer(3000, 10000);
		d.markChanged(0);
		d.markChanged(1000);
		Debouncer.Pending p = d.take();
		assertEquals(new Debouncer.Pending(0, 1000), p);
		assertFalse(d.isDirty());
		d.markChanged(20_000);
		assertFalse(d.due(22_000));
		assertTrue(d.due(23_000));
	}

	/** 429 などで送れなかったら戻して、待ってから送る。 */
	@Test
	void restoreKeepsChangesAndWaits() {
		Debouncer d = new Debouncer(3000, 10000);
		d.markChanged(0);
		Debouncer.Pending p = d.take();
		d.restore(p, 4000, 1000);
		assertTrue(d.isDirty());
		assertFalse(d.due(4999), "待ちのあいだは送らない");
		assertTrue(d.due(5000));
	}

	@Test
	void restoreMergesWithNewerChanges() {
		Debouncer d = new Debouncer(3000, 10000);
		d.markChanged(0);
		Debouncer.Pending p = d.take();
		d.markChanged(4500); // 送っているあいだに変わった
		d.restore(p, 5000, 1000);
		assertEquals(new Debouncer.Pending(0, 4500), d.take());
	}

	@Test
	void restoreOfNullOnlyPostpones() {
		Debouncer d = new Debouncer(3000, 10000);
		d.restore(null, 0, 1000);
		assertFalse(d.isDirty());
		d.markChanged(0);
		assertTrue(d.due(3500));
	}

	@Test
	void postponeDelaysWithoutDroppingChanges() {
		Debouncer d = new Debouncer(3000, 10000);
		d.markChanged(0);
		d.postpone(3000, 5000);
		assertFalse(d.due(7999));
		assertTrue(d.due(8000));
		d.clear();
		assertFalse(d.isDirty());
	}

	@Test
	void timingsAreValidatedAndAdjustable() {
		assertThrows(IllegalArgumentException.class, () -> new Debouncer(5000, 1000));
		Debouncer d = new Debouncer(3000, 10000);
		d.setTimings(500, 2000);
		d.markChanged(0);
		assertTrue(d.due(500));
		assertEquals(100, d.sinceLastChange(100));
	}
}
