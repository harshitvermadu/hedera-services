package com.hedera.services.txns.token.process;

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
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.state.submerkle.FcTokenAssociation;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;

import javax.annotation.Nullable;
import java.util.List;

import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;

/**
 * A process object to help discriminate the stages of token creation.
 */
public class Creation {
	@FunctionalInterface
	public interface CreationFactory {
		Creation processFrom(
				AccountStore accountStore,
				TypedTokenStore tokenStore,
				GlobalDynamicProperties dynamicProperties,
				TokenCreateTransactionBody op);
	}

	@FunctionalInterface
	public interface TokenModelFactory {
		Token createFrom(
				final Id tokenId,
				final TokenCreateTransactionBody op,
				final Account treasury,
				@Nullable final Account autoRenewAccount,
				final long consensusNow);
	}

	@FunctionalInterface
	public interface NewRelsListing {
		List<TokenRelationship> listFrom(Token provisionalToken, int maxTokensPerAccount);
	}

	private Id provisionalId;
	private Token provisionalToken;
	private Account treasury;
	private Account autoRenew;
	private List<TokenRelationship> newRels;

	private final AccountStore accountStore;
	private final TypedTokenStore tokenStore;
	private final GlobalDynamicProperties dynamicProperties;
	private final TokenCreateTransactionBody op;

	public Creation(
			AccountStore accountStore,
			TypedTokenStore tokenStore,
			GlobalDynamicProperties dynamicProperties,
			TokenCreateTransactionBody op
	) {
		this.op = op;
		this.tokenStore = tokenStore;
		this.accountStore = accountStore;
		this.dynamicProperties = dynamicProperties;
	}

	public void loadModelsWith(
			AccountID sponsor,
			EntityIdSource ids,
			OptionValidator validator
	) {
		final var hasValidOrNoExplicitExpiry = !op.hasExpiry() || validator.isValidExpiry(op.getExpiry());
		validateTrue(hasValidOrNoExplicitExpiry, INVALID_EXPIRATION_TIME);

		final var treasuryId = Id.fromGrpcAccount(op.getTreasury());
		treasury = accountStore.loadAccountOrFailWith(treasuryId, INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
		autoRenew = null;
		if (op.hasAutoRenewAccount()) {
			final var autoRenewId = Id.fromGrpcAccount(op.getAutoRenewAccount());
			autoRenew = accountStore.loadAccountOrFailWith(autoRenewId, INVALID_AUTORENEW_ACCOUNT);
		}

		provisionalId = Id.fromGrpcToken(ids.newTokenId(sponsor));
	}

	public void doProvisionallyWith(long now, TokenModelFactory modelFactory, NewRelsListing listing) {
		final var maxCustomFees = dynamicProperties.maxCustomFeesAllowed();
		validateTrue(op.getCustomFeesCount() <= maxCustomFees, CUSTOM_FEES_LIST_TOO_LONG);

		provisionalToken = modelFactory.createFrom(provisionalId, op, treasury, autoRenew, now);
		provisionalToken.getCustomFees().forEach(fee ->
				fee.validateAndFinalizeWith(provisionalToken, accountStore, tokenStore));
		newRels = listing.listFrom(provisionalToken, dynamicProperties.maxTokensPerAccount());
		if (op.getInitialSupply() > 0) {
			provisionalToken.mint(newRels.get(0), op.getInitialSupply(), true);
		}
		provisionalToken.getCustomFees().forEach(FcCustomFee::nullOutCollector);
	}

	public void persist() {
		tokenStore.persistNew(provisionalToken);
		tokenStore.commitTokenRelationships(newRels);
		newRels.forEach(rel -> accountStore.commitAccount(rel.getAccount()));
	}

	public List<FcTokenAssociation> newAssociations() {
		return newRels.stream().map(TokenRelationship::asAutoAssociation).toList();
	}

	/* --- Only used by unit tests --- */
	void setProvisionalId(Id provisionalId) {
		this.provisionalId = provisionalId;
	}

	void setProvisionalToken(Token provisionalToken) {
		this.provisionalToken = provisionalToken;
	}

	void setTreasury(Account treasury) {
		this.treasury = treasury;
	}

	void setAutoRenew(Account autoRenew) {
		this.autoRenew = autoRenew;
	}

	void setNewRels(List<TokenRelationship> newRels) {
		this.newRels = newRels;
	}

	Id getProvisionalId() {
		return provisionalId;
	}

	Account getTreasury() {
		return treasury;
	}

	Account getAutoRenew() {
		return autoRenew;
	}
}
