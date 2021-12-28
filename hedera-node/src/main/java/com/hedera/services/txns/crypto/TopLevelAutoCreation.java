package com.hedera.services.txns.crypto;

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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.records.InProgressChildRecord;
import com.hedera.services.state.EntityCreator;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.FcAssessedCustomFee;
import com.hedera.services.store.contracts.precompile.SyntheticTxnFactory;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.hedera.services.context.BasicTransactionContext.EMPTY_KEY;
import static com.hedera.services.records.TxnAwareRecordsHistorian.DEFAULT_SOURCE_ID;
import static com.hedera.services.utils.MiscUtils.asFcKeyUnchecked;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

/**
 * Responsible for creating accounts during a crypto transfer that sends hbar to a previously unused alias.
 */
@Singleton
public class TopLevelAutoCreation implements AutoCreationLogic {
	private static final List<FcAssessedCustomFee> NO_CUSTOM_FEES = Collections.emptyList();

	private final StateView currentView;
	private final AliasManager aliasManager;
	private final EntityIdSource ids;
	private final EntityCreator creator;
	private final TransactionContext txnCtx;
	private final SyntheticTxnFactory syntheticTxnFactory;
	private final List<InProgressChildRecord> pendingCreations = new ArrayList<>();
	private final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger;

	private FeeCalculator feeCalculator;

	public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
	public static final String AUTO_MEMO = "auto-created account";

	@Inject
	public TopLevelAutoCreation(
			final SyntheticTxnFactory syntheticTxnFactory,
			final EntityCreator creator,
			final EntityIdSource ids,
			final AliasManager aliasManager,
			final StateView currentView,
			final TransactionContext txnCtx,
			final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger
	) {
		this.ids = ids;
		this.txnCtx = txnCtx;
		this.creator = creator;
		this.currentView = currentView;
		this.syntheticTxnFactory = syntheticTxnFactory;
		this.aliasManager = aliasManager;
		this.accountsLedger = accountsLedger;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setFeeCalculator(final FeeCalculator feeCalculator) {
		this.feeCalculator = feeCalculator;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void reset() {
		pendingCreations.clear();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean reclaimPendingAliases() {
		if (!pendingCreations.isEmpty()) {
			for (final var pendingCreation : pendingCreations) {
				final var alias = pendingCreation.recordBuilder().getAlias();
				aliasManager.unlink(alias);
			}
			return true;
		} else {
			return false;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void submitRecordsTo(final AccountRecordsHistorian recordsHistorian) {
		for (final var pendingCreation : pendingCreations) {
			final var syntheticCreation = pendingCreation.syntheticBody();
			final var childRecord = pendingCreation.recordBuilder();
			recordsHistorian.trackPrecedingChildRecord(DEFAULT_SOURCE_ID, syntheticCreation, childRecord);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Pair<ResponseCodeEnum, Long> createFromTrigger(final BalanceChange change) {
		final var alias = change.alias();
		final var key = asPrimitiveKeyUnchecked(alias);
		final var syntheticCreation = syntheticTxnFactory.createAccount(key, 0L);
		final var fee = autoCreationFeeFor(syntheticCreation);
		if (fee > change.units()) {
			return Pair.of(change.codeForInsufficientBalance(), 0L);
		}
		change.adjustUnits(-fee);
		change.setNewBalance(change.units());

		final var sideEffects = new SideEffectsTracker();
		final var newAccountId = ids.newAccountId(syntheticCreation.getTransactionID().getAccountID());
		accountsLedger.create(newAccountId);
		change.replaceAliasWith(newAccountId);
		final var customizer = new HederaAccountCustomizer()
				.key(asFcKeyUnchecked(key))
				.memo(AUTO_MEMO)
				.autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
				.isReceiverSigRequired(false)
				.isSmartContract(false)
				.alias(alias);
		customizer.customize(newAccountId, accountsLedger);

		sideEffects.trackAutoCreation(newAccountId, alias);
		final var childRecord = creator.createSuccessfulSyntheticRecord(NO_CUSTOM_FEES, sideEffects, AUTO_MEMO);
		final var inProgress = new InProgressChildRecord(DEFAULT_SOURCE_ID, syntheticCreation, childRecord);
		pendingCreations.add(inProgress);
		/* If the transaction fails, we will get an opportunity to remove this alias in reclaimPendingAliases() */
		aliasManager.link(alias, EntityNum.fromAccountId(newAccountId));

		return Pair.of(OK, fee);
	}

	private long autoCreationFeeFor(final TransactionBody.Builder cryptoCreateTxn) {
		final var signedTxn = SignedTransaction.newBuilder()
				.setBodyBytes(cryptoCreateTxn.build().toByteString())
				.setSigMap(SignatureMap.getDefaultInstance())
				.build();
		final var txn = Transaction.newBuilder()
				.setSignedTransactionBytes(signedTxn.toByteString())
				.build();

		final var accessor = SignedTxnAccessor.uncheckedFrom(txn);
		final var fees = feeCalculator.computeFee(accessor, EMPTY_KEY, currentView, txnCtx.consensusTime());
		return fees.getServiceFee() + fees.getNetworkFee() + fees.getNodeFee();
	}

	private Key asPrimitiveKeyUnchecked(final ByteString alias) {
		try {
			return Key.parseFrom(alias);
		} catch (InvalidProtocolBufferException internal) {
			throw new IllegalStateException(internal);
		}
	}

	/* --- Only used by unit tests -- */
	List<InProgressChildRecord> getPendingCreations() {
		return pendingCreations;
	}
}
