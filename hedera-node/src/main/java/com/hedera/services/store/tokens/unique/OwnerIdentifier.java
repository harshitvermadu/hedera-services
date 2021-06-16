package com.hedera.services.store.tokens.unique;

/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.utils.invertible_fchashmap.Identifiable;

import java.util.Objects;

public class OwnerIdentifier implements Identifiable {

	private final EntityId owner;

	public OwnerIdentifier(EntityId owner) {
		this.owner = owner;
	}

	/* Object */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || OwnerIdentifier.class != o.getClass()) {
			return false;
		}

		var that = (OwnerIdentifier) o;
		return this.owner.equals(that.owner);
	}

	@Override
	public int hashCode() {
		return Objects.hash(owner);
	}

}
