package com.hedera.services.fees.calculation.crypto.queries;

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

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.calculation.QueryResourceUsageEstimator;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.usage.crypto.CryptoOpsUsage;
import com.hedera.services.usage.crypto.ExtantCryptoContext;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;

@Singleton
public final class GetAccountInfoResourceUsage implements QueryResourceUsageEstimator {
	private final CryptoOpsUsage cryptoOpsUsage;
	private final AliasManager aliasManager;

	@Inject
	public GetAccountInfoResourceUsage(final CryptoOpsUsage cryptoOpsUsage, final AliasManager aliasManager) {
		this.cryptoOpsUsage = cryptoOpsUsage;
		this.aliasManager = aliasManager;
	}

	@Override
	public boolean applicableTo(final Query query) {
		return query.hasCryptoGetInfo();
	}

	@Override
	public FeeData usageGiven(final Query query, final StateView view, final Map<String, Object> ignoreCtx) {
		final var op = query.getCryptoGetInfo();

		final var account = op.getAccountID();
		final var info = view.infoForAccount(account, aliasManager);
		/* Given the test in {@code GetAccountInfoAnswer.checkValidity}, this can only be empty
		 * under the extraordinary circumstance that the desired account expired during the query
		 * answer flow (which will now fail downstream with an appropriate status code); so
		 * just return the default {@code FeeData} here. */
		if (info.isEmpty()) {
			return FeeData.getDefaultInstance();
		}
		final var details = info.get();
		final var ctx = ExtantCryptoContext.newBuilder()
				.setCurrentKey(details.getKey())
				.setCurrentMemo(details.getMemo())
				.setCurrentExpiry(details.getExpirationTime().getSeconds())
				.setCurrentlyHasProxy(details.hasProxyAccountID())
				.setCurrentNumTokenRels(details.getTokenRelationshipsCount())
				.setCurrentMaxAutomaticAssociations(details.getMaxAutomaticTokenAssociations())
				.build();
		return cryptoOpsUsage.cryptoInfoUsage(query, ctx);
	}
}
