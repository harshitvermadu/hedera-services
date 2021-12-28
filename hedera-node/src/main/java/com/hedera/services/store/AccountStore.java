package com.hedera.services.store;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.ledger.backing.BackingStore;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import static com.hedera.services.exceptions.ValidationUtils.validateFalse;
import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;

@Singleton
public class AccountStore {
	private final OptionValidator validator;
	private final GlobalDynamicProperties dynamicProperties;
	private final BackingStore<AccountID, MerkleAccount> accounts;

	@Inject
	public AccountStore(
			final OptionValidator validator,
			final GlobalDynamicProperties dynamicProperties,
			final BackingStore<AccountID, MerkleAccount> accounts
	) {
		this.validator = validator;
		this.dynamicProperties = dynamicProperties;
		this.accounts = accounts;
	}

	/**
	 * Returns a model of the requested account, with operations that can be used to
	 * implement business logic in a transaction.
	 *
	 * <b>IMPORTANT:</b> Changes to the returned model are not automatically persisted
	 * to state! The altered model must be passed to {@link AccountStore#commitAccount(Account)}
	 * in order for its changes to be applied to the Swirlds state, and included in the
	 * {@link com.hedera.services.state.submerkle.ExpirableTxnRecord} for the active transaction.
	 * <p>
	 * The method uses the {@link AccountStore#loadAccountOrFailWith(Id, ResponseCodeEnum)} by passing a `null` explicit
	 * response code
	 *
	 * @param id the account to load
	 * @return a usable model of the account
	 * @throws InvalidTransactionException if the requested account is missing, deleted, or expired and pending removal
	 */
	public Account loadAccount(Id id) {
		return this.loadAccountOrFailWith(id, null);
	}

	/**
	 * Attempts to load an account from state
	 * and throws the given code if an exception occurs due to an invalid account.
	 *
	 * <b>IMPORTANT:</b> Changes to the returned model are not automatically persisted
	 * to state! The altered model must be passed to {@link AccountStore#commitAccount(Account)}
	 * in order for its changes to be applied to the Swirlds state, and included in the
	 * {@link com.hedera.services.state.submerkle.ExpirableTxnRecord} for the active transaction.
	 *
	 * @param id   the account to load
	 * @param code the {@link ResponseCodeEnum} to fail with if the account is deleted/missing
	 * @return a usable model of the account if available
	 */
	public Account loadAccountOrFailWith(Id id, @Nullable ResponseCodeEnum code) {
		return this.loadEntityOrFailWith(id, code, INVALID_ACCOUNT_ID, ACCOUNT_DELETED);
	}

	/**
	 * Returns a model of the requested account, with operations that can be used to
	 * implement business logic in a transaction.
	 *
	 * @param id the account to load
	 * @return a usable model of the account
	 * @throws InvalidTransactionException if the requested contract is missing, deleted or is not smart contract
	 */
	public Account loadContract(Id id) {
		final var account = loadEntityOrFailWith(id, null, INVALID_CONTRACT_ID, CONTRACT_DELETED);
		validateTrue(account.isSmartContract(), INVALID_CONTRACT_ID);
		return account;
	}

	/**
	 * Returns a model of the requested entity. The method is to be used for loading both Accounts and Contracts as
	 * it does not validate the type of the entity. Additional validation is to be performed if the consumer must
	 * validate the type of the entity.
	 *
	 * @param id                   Id of the requested entity
	 * @param explicitResponseCode The explicit {@link ResponseCodeEnum} to be returned in the case of the entity not
	 *                             being found or deleted
	 * @param nonExistingCode      The {@link ResponseCodeEnum} to be used in the case of the entity being non-existing
	 * @param deletedCode          The {@link ResponseCodeEnum} to be used in the case of the entity being deleted
	 * @return usable model of the entity if available
	 */
	private Account loadEntityOrFailWith(Id id, @Nullable ResponseCodeEnum explicitResponseCode,
										 ResponseCodeEnum nonExistingCode, ResponseCodeEnum deletedCode) {
		final var merkleAccount = accounts.getImmutableRef(id.asGrpcAccount());

		validateUsable(merkleAccount, explicitResponseCode, nonExistingCode, deletedCode);

		final var account = new Account(id);
		account.setExpiry(merkleAccount.getExpiry());
		account.initBalance(merkleAccount.getBalance());
		account.setAssociatedTokens(merkleAccount.tokens().getIds().copy());
		account.setOwnedNfts(merkleAccount.getNftsOwned());
		account.setMaxAutomaticAssociations(merkleAccount.getMaxAutomaticAssociations());
		account.setAlreadyUsedAutomaticAssociations(merkleAccount.getAlreadyUsedAutoAssociations());
		if (merkleAccount.getProxy() != null) {
			account.setProxy(merkleAccount.getProxy().asId());
		}
		account.setReceiverSigRequired(merkleAccount.isReceiverSigRequired());
		account.setKey(merkleAccount.state().key());
		account.setMemo(merkleAccount.getMemo());
		account.setAutoRenewSecs(merkleAccount.getAutoRenewSecs());
		account.setDeleted(merkleAccount.isDeleted());
		account.setSmartContract(merkleAccount.isSmartContract());
		account.setAlias(merkleAccount.getAlias());

		return account;
	}

	/**
	 * Persists the given account to the Swirlds state.
	 *
	 * @param account the account to save
	 */
	public void commitAccount(Account account) {
		final var id = account.getId();
                final var grpcId = id.asGrpcAccount();
		final var mutableAccount = accounts.getRef(grpcId);
		mapModelToMutable(account, mutableAccount);
		mutableAccount.tokens().updateAssociationsFrom(account.getAssociatedTokens());
		accounts.put(grpcId, mutableAccount);
	}

	private void mapModelToMutable(Account model, MerkleAccount mutableAccount) {
		if (model.getProxy() != null) {
			mutableAccount.setProxy(model.getProxy().asEntityId());
		}
		mutableAccount.setExpiry(model.getExpiry());
		mutableAccount.setBalanceUnchecked(model.getBalance());
		mutableAccount.setNftsOwned(model.getOwnedNfts());
		mutableAccount.setMaxAutomaticAssociations(model.getMaxAutomaticAssociations());
		mutableAccount.setAlreadyUsedAutomaticAssociations(model.getAlreadyUsedAutomaticAssociations());
		mutableAccount.state().setAccountKey(model.getKey());
		mutableAccount.setReceiverSigRequired(model.isReceiverSigRequired());
		mutableAccount.setDeleted(model.isDeleted());
		mutableAccount.setAutoRenewSecs(model.getAutoRenewSecs());
		mutableAccount.setSmartContract(model.isSmartContract());
	}

	private void validateUsable(MerkleAccount merkleAccount, @Nullable ResponseCodeEnum explicitResponse,
								ResponseCodeEnum nonExistingCode, ResponseCodeEnum deletedCode) {
		validateTrue(merkleAccount != null, explicitResponse != null ? explicitResponse : nonExistingCode);
		validateFalse(merkleAccount.isDeleted(), explicitResponse != null ? explicitResponse : deletedCode);
		validateFalse(isExpired(merkleAccount.getBalance(), merkleAccount.getExpiry()),
				ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
	}

	private boolean isExpired(long balance, long expiry) {
		if (dynamicProperties.autoRenewEnabled() && balance == 0) {
			return !validator.isAfterConsensusSecond(expiry);
		} else {
			return false;
		}
	}

	public OptionValidator getValidator() {
		return validator;
	}
}
