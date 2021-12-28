package com.hedera.services.throttles;

/*-
 * ‌
 * Hedera Services API Utilities
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static com.hedera.services.legacy.proto.utils.CommonUtils.productWouldOverflow;

/**
 * A throttle with milli-TPS resolution that exists in a deterministic timeline.
 */
public class DeterministicThrottle {
	private static final Instant NEVER = null;
	private static final String NO_NAME = null;

	private final String name;
	private final BucketThrottle delegate;
	private Instant lastDecisionTime;

	public static DeterministicThrottle withTps(final int tps) {
		return new DeterministicThrottle(BucketThrottle.withTps(tps), NO_NAME);
	}

	public static DeterministicThrottle withTpsNamed(final int tps, final String name) {
		return new DeterministicThrottle(BucketThrottle.withTps(tps), name);
	}

	public static DeterministicThrottle withMtps(final long mtps) {
		return new DeterministicThrottle(BucketThrottle.withMtps(mtps), NO_NAME);
	}

	public static DeterministicThrottle withMtpsNamed(final long mtps, final String name) {
		return new DeterministicThrottle(BucketThrottle.withMtps(mtps), name);
	}

	public static DeterministicThrottle withTpsAndBurstPeriod(final int tps, final int burstPeriod) {
		return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriod(tps, burstPeriod), NO_NAME);
	}

	public static DeterministicThrottle withTpsAndBurstPeriodNamed(
			final int tps,
			final int burstPeriod,
			final String name
	) {
		return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriod(tps, burstPeriod), name);
	}

	public static DeterministicThrottle withMtpsAndBurstPeriod(final long mtps, final int burstPeriod) {
		return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod), NO_NAME);
	}

	public static DeterministicThrottle withMtpsAndBurstPeriodNamed(
			final long mtps,
			final int burstPeriod,
			final String name) {
		return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod), name);
	}

	public static DeterministicThrottle withTpsAndBurstPeriodMs(final int tps, final long burstPeriodMs) {
		return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriodMs(tps, burstPeriodMs), NO_NAME);
	}

	public static DeterministicThrottle withTpsAndBurstPeriodMsNamed(
			final int tps,
			final long burstPeriodMs,
			final String name) {
		return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriodMs(tps, burstPeriodMs), name);
	}

	public static DeterministicThrottle withMtpsAndBurstPeriodMs(final long mtps, final long burstPeriodMs) {
		return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriodMs(mtps, burstPeriodMs), NO_NAME);
	}

	public static DeterministicThrottle withMtpsAndBurstPeriodMsNamed(
			final long mtps,
			final long burstPeriodMs,
			final String name) {
		return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriodMs(mtps, burstPeriodMs), name);
	}

	private DeterministicThrottle(final BucketThrottle delegate, final String name) {
		this.name = name;
		this.delegate = delegate;
		lastDecisionTime = NEVER;
	}

	public static long capacityRequiredFor(final int nTransactions) {
		if (productWouldOverflow(nTransactions, BucketThrottle.capacityUnitsPerTxn())) {
			return -1;
		}
		return nTransactions * BucketThrottle.capacityUnitsPerTxn();
	}

	public boolean allow(final int n) {
		return allow(n, Instant.now());
	}

	public boolean allow(final int n, final Instant now) {
		long elapsedNanos = 0L;
		if (lastDecisionTime != NEVER) {
			elapsedNanos = Duration.between(lastDecisionTime, now).toNanos();
			if (elapsedNanos < 0L) {
				throw new IllegalArgumentException(
						"Throttle timeline must advance, but " + now + " is not after " + lastDecisionTime + "!");
			}
		}

		lastDecisionTime = now;
		return delegate.allow(n, elapsedNanos);
	}

	public void reclaimLastAllowedUse() {
		delegate.reclaimLastAllowedUse();
	}

	public String name() {
		return name;
	}

	public long mtps() {
		return delegate.mtps();
	}

	public long used() {
		return delegate.bucket().capacityUsed();
	}

	public long capacity() {
		return delegate.bucket().totalCapacity();
	}

	public UsageSnapshot usageSnapshot() {
		final var bucket = delegate.bucket();
		return new UsageSnapshot(bucket.capacityUsed(), lastDecisionTime);
	}

	public void resetUsageTo(final UsageSnapshot usageSnapshot) {
		final var bucket = delegate.bucket();
		lastDecisionTime = usageSnapshot.lastDecisionTime();
		bucket.resetUsed(usageSnapshot.used());
	}

	/* NOTE: The Object methods below are only overridden to improve
	readability of unit tests; Instances of this class are not used
        in hash-based collections */
	@Override
	public boolean equals(final Object obj) {
		if (obj == null || this.getClass() != obj.getClass()) {
			return false;
		}

		final var that = (DeterministicThrottle) obj;

		return this.delegate.bucket().totalCapacity() == that.delegate.bucket().totalCapacity()
				&& this.delegate.mtps() == that.delegate.mtps();
	}

	@Override
	public int hashCode() {
		return Objects.hash(delegate.bucket().totalCapacity(), delegate.mtps(), name, lastDecisionTime);
	}

	@Override
	public String toString() {
		final var sb = new StringBuilder("DeterministicThrottle{");
		if (name != null) {
			sb.append("name='").append(name).append("', ");
		}
		return sb
				.append("mtps=").append(delegate.mtps()).append(", ")
				.append("capacity=").append(capacity()).append(" (used=").append(used()).append(")")
				.append(lastDecisionTime == NEVER ? "" : (", last decision @ " + lastDecisionTime))
				.append("}")
				.toString();
	}

	public static record UsageSnapshot(long used, Instant lastDecisionTime) {
		@Override
		public String toString() {
			final var sb = new StringBuilder("DeterministicThrottle.UsageSnapshot{");
			return sb
					.append("used=").append(used)
					.append(", last decision @ ")
					.append(lastDecisionTime == NEVER ? "<N/A>" : lastDecisionTime)
					.append("}")
					.toString();
		}
	}

	BucketThrottle delegate() {
		return delegate;
	}

	Instant lastDecisionTime() {
		return lastDecisionTime;
	}
}
