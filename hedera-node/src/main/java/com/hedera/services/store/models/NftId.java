package com.hedera.services.store.models;

/*-
 * ‌
 * Hedera Services Node
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

import com.hederahashgraph.api.proto.java.TokenID;

import static com.hedera.services.context.properties.StaticPropertiesHolder.STATIC_PROPERTIES;

public record NftId(long shard, long realm, long num, long serialNo) {
	public TokenID tokenId() {
		return TokenID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setTokenNum(num)
				.build();
	}

	public static NftId withDefaultShardRealm(final long num, final long serialNo) {
		return new NftId(STATIC_PROPERTIES.getShard(), STATIC_PROPERTIES.getRealm(), num, serialNo);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(shard);
		result = 31 * result + Long.hashCode(realm);
		result = 31 * result + Long.hashCode(num);
		return 31 * result + Long.hashCode(serialNo);
	}
}
