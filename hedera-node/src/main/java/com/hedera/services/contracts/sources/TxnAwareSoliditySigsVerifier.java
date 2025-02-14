package com.hedera.services.contracts.sources;

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
import com.hedera.services.keys.HederaKeyActivation;
import com.hedera.services.keys.SyncActivationCheck;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.PlatformSigOps;
import com.hedera.services.sigs.factories.ReusableBodySigningFactory;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.merkle.map.MerkleMap;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.hedera.services.keys.HederaKeyActivation.ONLY_IF_SIG_IS_VALID;
import static com.hedera.services.keys.HederaKeyActivation.isActive;
import static com.hedera.services.utils.EntityNum.fromAccountId;

public class TxnAwareSoliditySigsVerifier implements SoliditySigsVerifier {
	private final SyncVerifier syncVerifier;
	private final TransactionContext txnCtx;
	private final SyncActivationCheck check;
	private final Supplier<MerkleMap<EntityNum, MerkleAccount>> accounts;

	public TxnAwareSoliditySigsVerifier(
			SyncVerifier syncVerifier,
			TransactionContext txnCtx,
			SyncActivationCheck check,
			Supplier<MerkleMap<EntityNum, MerkleAccount>> accounts
	) {
		this.txnCtx = txnCtx;
		this.accounts = accounts;
		this.syncVerifier = syncVerifier;
		this.check = check;
	}

	@Override
	public boolean allRequiredKeysAreActive(Set<AccountID> touched) {
		var payer = txnCtx.activePayer();
		var requiredKeys = touched.stream()
				.filter(id -> !payer.equals(id))
				.flatMap(this::keyRequirement)
				.toList();
		if (requiredKeys.isEmpty()) {
			return true;
		} else {
			final var accessor = txnCtx.accessor();
			return check.allKeysAreActive(
					requiredKeys,
					syncVerifier,
					accessor,
					PlatformSigOps::createCryptoSigsFrom,
					accessor.getPkToSigsFn(),
					ReusableBodySigningFactory::new,
					(key, sigsFn) -> isActive(key, sigsFn, ONLY_IF_SIG_IS_VALID),
					HederaKeyActivation::pkToSigMapFrom);
		}
	}

	private Stream<JKey> keyRequirement(AccountID id) {
		return Optional.ofNullable(accounts.get().get(fromAccountId(id)))
				.filter(account -> !account.isSmartContract())
				.filter(MerkleAccount::isReceiverSigRequired)
				.map(MerkleAccount::getAccountKey)
				.stream();
	}
}
