package com.hedera.services.sysfiles.domain.throttling;

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

import com.hedera.services.throttles.DeterministicThrottle;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.hedera.services.sysfiles.validation.ErrorCodeUtils.exceptionMsgFor;
import static com.hedera.services.throttles.DeterministicThrottle.capacityRequiredFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUCKET_CAPACITY_OVERFLOW;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUCKET_HAS_NO_THROTTLE_GROUPS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OPERATION_REPEATED_IN_BUCKET_GROUPS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLE_GROUP_HAS_ZERO_OPS_PER_SEC;
import static java.util.Collections.disjoint;

public final class ThrottleBucket {
	private int burstPeriod;
	private long burstPeriodMs;
	private String name;
	private List<ThrottleGroup> throttleGroups = new ArrayList<>();

	private static final String BUCKET_PREFIX = "Bucket ";

	public long getBurstPeriodMs() {
		return burstPeriodMs;
	}

	public void setBurstPeriodMs(final long burstPeriodMs) {
		this.burstPeriodMs = burstPeriodMs;
	}

	public int getBurstPeriod() {
		return burstPeriod;
	}

	public void setBurstPeriod(final int burstPeriod) {
		this.burstPeriod = burstPeriod;
	}

	public String getName() {
		return name;
	}

	public void setName(final String name) {
		this.name = name;
	}

	public List<ThrottleGroup> getThrottleGroups() {
		return throttleGroups;
	}

	public static ThrottleBucket fromProto(final com.hederahashgraph.api.proto.java.ThrottleBucket bucket) {
		final var pojo = new ThrottleBucket();
		pojo.name = bucket.getName();
		pojo.burstPeriodMs = bucket.getBurstPeriodMs();
		pojo.throttleGroups.addAll(bucket.getThrottleGroupsList().stream()
				.map(ThrottleGroup::fromProto)
				.toList());
		return pojo;
	}

	public com.hederahashgraph.api.proto.java.ThrottleBucket toProto() {
		return com.hederahashgraph.api.proto.java.ThrottleBucket.newBuilder()
				.setName(name)
				.setBurstPeriodMs(impliedBurstPeriodMs())
				.addAllThrottleGroups(throttleGroups.stream()
						.map(ThrottleGroup::toProto)
						.toList())
				.build();
	}

	private long impliedBurstPeriodMs() {
		return burstPeriodMs > 0 ? burstPeriodMs : 1_000L * burstPeriod;
	}

	/**
	 * Returns a deterministic throttle scoped to (1/networkSize) of the nominal milliOpsPerSec
	 * in each throttle group; and a list that maps each relevant {@code HederaFunctionality}
	 * to the number of logical operations it requires from the throttle.
	 *
	 * @param networkSize
	 * 		network size
	 * @return a throttle with (1/networkSize) the capacity of this bucket, and a list of how many logical
	 * 		operations each assigned function will use from the throttle
	 * @throws IllegalStateException
	 * 		if this bucket was constructed with invalid throttle groups
	 */
	public Pair<DeterministicThrottle, List<Pair<HederaFunctionality, Integer>>> asThrottleMapping(
			final int networkSize
	) {
		if (throttleGroups.isEmpty()) {
			throw new IllegalStateException(exceptionMsgFor(
					BUCKET_HAS_NO_THROTTLE_GROUPS,
					BUCKET_PREFIX + name + " includes no throttle groups!"));
		}

		assertMinimalOpsPerSec();

		final var logicalMtps = requiredLogicalMilliTpsToAccommodateAllGroups();
		if (logicalMtps < 0) {
			throw new IllegalStateException(exceptionMsgFor(
					BUCKET_CAPACITY_OVERFLOW,
					BUCKET_PREFIX + name + " overflows with given throttle groups!"));
		}

		return mappingWith(logicalMtps, networkSize);
	}

	private Pair<DeterministicThrottle, List<Pair<HederaFunctionality, Integer>>> mappingWith(
			final long mtps,
			final int n
	) {
		final var throttle = throttleFor(mtps, n);
		final var totalCapacityUnits = throttle.capacity();

		final Set<HederaFunctionality> seenSoFar = new HashSet<>();
		final List<Pair<HederaFunctionality, Integer>> opsReqs = new ArrayList<>();
		for (final var throttleGroup : throttleGroups) {
			updateOpsReqs(n, mtps, totalCapacityUnits, throttleGroup, seenSoFar, opsReqs);
		}

		return Pair.of(throttle, opsReqs);
	}

	private void updateOpsReqs(
			final int n,
			final long mtps,
			final long totalCapacity,
			final ThrottleGroup group,
			final Set<HederaFunctionality> seenSoFar,
			final List<Pair<HederaFunctionality, Integer>> opsReqs
	) {
		final var opsReq = (int) (mtps / group.impliedMilliOpsPerSec());
		final var capacityReq = capacityRequiredFor(opsReq);
		if (capacityReq < 0 || capacityReq > totalCapacity) {
			throw new IllegalStateException(exceptionMsgFor(
					NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION,
					BUCKET_PREFIX + name + " contains an unsatisfiable milliOpsPerSec with " + n + " nodes!"));
		}

		final var functions = group.getOperations();
		if (disjoint(seenSoFar, functions)) {
			final Set<HederaFunctionality> listedSoFar = new HashSet<>();
			for (final var function : functions) {
				if (!listedSoFar.contains(function)) {
					opsReqs.add(Pair.of(function, opsReq));
					listedSoFar.add(function);
				}
			}
			seenSoFar.addAll(functions);
		} else {
			throw new IllegalStateException(exceptionMsgFor(
					OPERATION_REPEATED_IN_BUCKET_GROUPS,
					BUCKET_PREFIX + name + " assigns an operation to multiple groups!"));
		}
	}

	private DeterministicThrottle throttleFor(final long mtps, final int n) {
		try {
			return DeterministicThrottle.withMtpsAndBurstPeriodMsNamed(mtps / n, impliedBurstPeriodMs(), name);
		} catch (final IllegalArgumentException unsatisfiable) {
			if (unsatisfiable.getMessage().startsWith("Cannot free")) {
				throw new IllegalStateException(exceptionMsgFor(
						BUCKET_CAPACITY_OVERFLOW,
						BUCKET_PREFIX + name + " overflows with given throttle groups!"));
			} else {
				throw new IllegalStateException(exceptionMsgFor(
						NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION,
						BUCKET_PREFIX + name + " contains an unsatisfiable milliOpsPerSec with " + n + " nodes!"));
			}
		}
	}

	private void assertMinimalOpsPerSec() {
		for (final var group : throttleGroups) {
			if (group.impliedMilliOpsPerSec() == 0) {
				throw new IllegalStateException(exceptionMsgFor(
						THROTTLE_GROUP_HAS_ZERO_OPS_PER_SEC,
						BUCKET_PREFIX + name + " contains a group with zero milliOpsPerSec!"));
			}
		}
	}

	private long requiredLogicalMilliTpsToAccommodateAllGroups() {
		var lcm = throttleGroups.get(0).impliedMilliOpsPerSec();
		for (int i = 1, n = throttleGroups.size(); i < n; i++) {
			lcm = lcm(lcm, throttleGroups.get(i).impliedMilliOpsPerSec());
		}
		return lcm;
	}

	private long lcm(final long a, final long b) {
		return (a * b) / gcd(Math.min(a, b), Math.max(a, b));
	}

	private long gcd(final long a, final long b) {
		return (a == 0) ? b : gcd(b % a, a);
	}
}
