package com.hedera.services.throttling;

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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.throttling.annotations.HandleThrottle;
import com.hedera.services.throttling.annotations.HapiThrottle;
import com.swirlds.common.AddressBook;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;
import java.util.function.Supplier;

@Module
public final class ThrottlingModule {
	@Provides
	@Singleton
	@HapiThrottle
	public static FunctionalityThrottling provideHapiThrottling(
			final AliasManager aliasManager,
			final Supplier<AddressBook> addressBook,
			final GlobalDynamicProperties dynamicProperties
	) {
		final var delegate = new DeterministicThrottling(
				() -> addressBook.get().getSize(), aliasManager, dynamicProperties, false);
		return new HapiThrottling(delegate);
	}

	@Provides
	@Singleton
	@HandleThrottle
	public static FunctionalityThrottling provideHandleThrottling(
			final AliasManager aliasManager,
			final TransactionContext txnCtx,
			final GlobalDynamicProperties dynamicProperties
	) {
		final var delegate = new DeterministicThrottling(
				() -> 1, aliasManager, dynamicProperties, true);
		return new TxnAwareHandleThrottling(txnCtx, delegate);
	}

	private ThrottlingModule() {
		throw new UnsupportedOperationException("Dagger2 module");
	}
}
