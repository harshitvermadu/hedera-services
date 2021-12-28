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

import com.google.common.base.MoreObjects;

public record ThrottleReqOpsScaleFactor(int numerator, int denominator) {
	public static ThrottleReqOpsScaleFactor from(String literal) {
		final var splitIndex = literal.indexOf(':');
		if (splitIndex == -1) {
			throw new IllegalArgumentException("Missing ':' in scale literal '" + literal + "'");
		}
		final var n = Integer.parseInt(literal.substring(0, splitIndex));
		final var d = Integer.parseInt(literal.substring(splitIndex + 1));
		if (n < 0 || d < 0) {
			throw new IllegalArgumentException("Negative number in scale literal '" + literal + "'");
		}
		if (d == 0) {
			throw new IllegalArgumentException("Division by zero in scale literal '" + literal + "'");
		}
		return new ThrottleReqOpsScaleFactor(n, d);
	}

	public int scaling(int nominalOps) {
		final int maxUnscaledOps = Integer.MAX_VALUE / numerator;
		if (nominalOps > maxUnscaledOps) {
			return Integer.MAX_VALUE / denominator;
		}
		return Math.max(1, nominalOps * numerator / denominator);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(ThrottleReqOpsScaleFactor.class)
				.add("scale", numerator + ":" + denominator)
				.toString();
	}

	@Override
	public int hashCode() {
		var result = Integer.hashCode(numerator);
		return 31 * result + Integer.hashCode(denominator);
	}
}
