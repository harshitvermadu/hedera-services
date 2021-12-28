package com.hedera.services.context.init;

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

import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.ledger.backing.BackingStore;
import com.hedera.services.state.StateAccessor;
import com.hedera.services.state.annotations.WorkingState;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.views.UniqueTokenViewsManager;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class StoreInitializationFlow {
	private static final Logger log = LogManager.getLogger(StoreInitializationFlow.class);

	private final TokenStore tokenStore;
	private final ScheduleStore scheduleStore;
	private final StateAccessor stateAccessor;
	private final AliasManager aliasManager;
	private final UniqueTokenViewsManager uniqTokenViewsManager;
	private final BackingStore<AccountID, MerkleAccount> backingAccounts;
	private final BackingStore<TokenID, MerkleToken> backingTokens;
	private final BackingStore<NftId, MerkleUniqueToken> backingNfts;
	private final BackingStore<Pair<AccountID, TokenID>, MerkleTokenRelStatus> backingTokenRels;

	@Inject
	public StoreInitializationFlow(
			final TokenStore tokenStore,
			final ScheduleStore scheduleStore,
			final AliasManager aliasManager,
			final @WorkingState StateAccessor stateAccessor,
			final UniqueTokenViewsManager uniqTokenViewsManager,
			final BackingStore<AccountID, MerkleAccount> backingAccounts,
			final BackingStore<TokenID, MerkleToken> backingTokens,
			final BackingStore<NftId, MerkleUniqueToken> backingNfts,
			final BackingStore<Pair<AccountID, TokenID>, MerkleTokenRelStatus> backingTokenRels
	) {
		this.tokenStore = tokenStore;
		this.scheduleStore = scheduleStore;
		this.backingAccounts = backingAccounts;
		this.backingTokens = backingTokens;
		this.stateAccessor = stateAccessor;
		this.backingNfts = backingNfts;
		this.backingTokenRels = backingTokenRels;
		this.aliasManager = aliasManager;
		this.uniqTokenViewsManager = uniqTokenViewsManager;
	}

	public void run() {
		backingTokenRels.rebuildFromSources();
		backingAccounts.rebuildFromSources();
		backingTokens.rebuildFromSources();
		backingNfts.rebuildFromSources();
		log.info("Backing stores rebuilt");

		tokenStore.rebuildViews();
		scheduleStore.rebuildViews();
		log.info("Store internal views rebuilt");

		uniqTokenViewsManager.rebuildNotice(stateAccessor.tokens(), stateAccessor.uniqueTokens());
		log.info("Unique token views rebuilt");

		aliasManager.rebuildAliasesMap(stateAccessor.accounts());
		log.info("Account aliases map rebuilt");
	}
}
