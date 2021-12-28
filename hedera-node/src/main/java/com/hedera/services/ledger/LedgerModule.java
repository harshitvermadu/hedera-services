package com.hedera.services.ledger;

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

import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.backing.BackingAccounts;
import com.hedera.services.ledger.backing.BackingStore;
import com.hedera.services.ledger.backing.BackingTokens;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.state.EntityCreator;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.views.UniqueTokenViewsManager;
import com.hedera.services.txns.crypto.AutoCreationLogic;
import com.hedera.services.txns.crypto.TopLevelAutoCreation;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

@Module
public abstract class LedgerModule {
	@Binds
	@Singleton
	public abstract AutoCreationLogic bindAutoCreationLogic(TopLevelAutoCreation topLevelAutoCreation);

	@Binds
	@Singleton
	public abstract BackingStore<AccountID, MerkleAccount> bindBackingAccounts(BackingAccounts backingAccounts);

	@Binds
	@Singleton
	public abstract BackingStore<TokenID, MerkleToken> bindBackingTokens(BackingTokens backingTokens);

	@Provides
	@Singleton
	public static HederaLedger provideHederaLedger(
			final TokenStore tokenStore,
			final ScheduleStore scheduleStore,
			final EntityCreator creator,
			final EntityIdSource ids,
			final OptionValidator validator,
			final SideEffectsTracker sideEffectsTracker,
			final UniqueTokenViewsManager uniqueTokenViewsManager,
			final AccountRecordsHistorian recordsHistorian,
			final GlobalDynamicProperties dynamicProperties,
			final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger,
			final AutoCreationLogic autoCreationLogic,
			final TransferLogic transferLogic
	) {
		final var ledger = new HederaLedger(
				tokenStore,
				ids,
				creator,
				validator,
				sideEffectsTracker,
				recordsHistorian,
				dynamicProperties,
				accountsLedger,
				transferLogic,
				autoCreationLogic);
		ledger.setTokenViewsManager(uniqueTokenViewsManager);
		scheduleStore.setAccountsLedger(accountsLedger);
		scheduleStore.setHederaLedger(ledger);
		return ledger;
	}
}
