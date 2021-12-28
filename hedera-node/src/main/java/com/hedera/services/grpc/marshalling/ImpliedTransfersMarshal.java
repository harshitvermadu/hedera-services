package com.hedera.services.grpc.marshalling;

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
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.ledger.PureTransferSemanticChecks;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.state.submerkle.FcAssessedCustomFee;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_ALIASES;
import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_CUSTOM_FEES;
import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_CUSTOM_FEE_META;
import static com.hedera.services.ledger.BalanceChange.changingFtUnits;
import static com.hedera.services.ledger.BalanceChange.changingHbar;
import static com.hedera.services.ledger.BalanceChange.changingNftOwnership;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

public class ImpliedTransfersMarshal {
	private final FeeAssessor feeAssessor;
	private final AliasManager aliasManager;
	private final CustomFeeSchedules customFeeSchedules;
	private final Supplier<AliasResolver> aliasResolverFactory;
	private final GlobalDynamicProperties dynamicProperties;
	private final PureTransferSemanticChecks checks;
	private final Predicate<CryptoTransferTransactionBody> aliasCheck;
	private final BalanceChangeManager.ChangeManagerFactory changeManagerFactory;
	private final Function<CustomFeeSchedules, CustomSchedulesManager> schedulesManagerFactory;

	public ImpliedTransfersMarshal(
			final FeeAssessor feeAssessor,
			final AliasManager aliasManager,
			final CustomFeeSchedules customFeeSchedules,
			final Supplier<AliasResolver> aliasResolverFactory,
			final GlobalDynamicProperties dynamicProperties,
			final PureTransferSemanticChecks checks,
			final Predicate<CryptoTransferTransactionBody> aliasCheck,
			final BalanceChangeManager.ChangeManagerFactory changeManagerFactory,
			final Function<CustomFeeSchedules, CustomSchedulesManager> schedulesManagerFactory
	) {
		this.checks = checks;
		this.aliasCheck = aliasCheck;
		this.aliasManager = aliasManager;
		this.feeAssessor = feeAssessor;
		this.aliasResolverFactory = aliasResolverFactory;
		this.customFeeSchedules = customFeeSchedules;
		this.dynamicProperties = dynamicProperties;
		this.changeManagerFactory = changeManagerFactory;
		this.schedulesManagerFactory = schedulesManagerFactory;
	}

	public ImpliedTransfers unmarshalFromGrpc(CryptoTransferTransactionBody op) {
		final var props = currentProps();

		var numAutoCreations = 0;
		Map<ByteString, EntityNum> resolvedAliases = NO_ALIASES;
		if (aliasCheck.test(op)) {
			final var aliasResolver = aliasResolverFactory.get();
			op = aliasResolver.resolve(op, aliasManager);
			if (aliasResolver.perceivedMissingAliases() > 0) {
				return ImpliedTransfers.invalid(props, aliasResolver.resolutions(), INVALID_ACCOUNT_ID);
			} else if (aliasResolver.perceivedInvalidCreations() > 0) {
				return ImpliedTransfers.invalid(props, aliasResolver.resolutions(), INVALID_ALIAS_KEY);
			} else {
				resolvedAliases = aliasResolver.resolutions();
				numAutoCreations = aliasResolver.perceivedAutoCreations();
			}
		}

		final var validity = checks.fullPureValidation(op.getTransfers(), op.getTokenTransfersList(), props);
		if (validity != OK) {
			return ImpliedTransfers.invalid(props, validity);
		}

		final List<BalanceChange> changes = new ArrayList<>();
		for (var aa : op.getTransfers().getAccountAmountsList()) {
			changes.add(changingHbar(aa));
		}
		if (!hasTokenChanges(op)) {
			return ImpliedTransfers.valid(
					props, changes, NO_CUSTOM_FEE_META, NO_CUSTOM_FEES, resolvedAliases, numAutoCreations);
		}

		/* Add in the HTS balance changes from the transaction */
		final var hbarOnly = changes.size();
		appendToken(op, changes);

		return assessCustomFeesAndValidate(hbarOnly, numAutoCreations, changes, resolvedAliases, props);
	}

	public ImpliedTransfers assessCustomFeesAndValidate(
			final int hbarOnly,
			final int numAutoCreations,
			final List<BalanceChange> changes,
			final Map<ByteString, EntityNum> resolvedAliases,
			final ImpliedTransfersMeta.ValidationProps props
	) {
		/* Construct the process objects for custom fee charging */
		final var changeManager = changeManagerFactory.from(changes, hbarOnly);
		final var schedulesManager = schedulesManagerFactory.apply(customFeeSchedules);

		/* And for each "assessable change" that can be charged a custom fee, delegate to our
		fee assessor to update the balance changes with the custom fee. */
		final List<FcAssessedCustomFee> fees = new ArrayList<>();
		var change = changeManager.nextAssessableChange();
		while (change != null) {
			final var status =
					feeAssessor.assess(change, schedulesManager, changeManager, fees, props);
			if (status != OK) {
				return ImpliedTransfers.invalid(props, schedulesManager.metaUsed(), status);
			}
			change = changeManager.nextAssessableChange();
		}

		return ImpliedTransfers.valid(
				props, changes, schedulesManager.metaUsed(), fees, resolvedAliases, numAutoCreations);
	}

	private void appendToken(CryptoTransferTransactionBody op, List<BalanceChange> changes) {
		/* First add all fungible changes, then NFT ownership changes. This ensures
		fractional fees are applied to the fungible value exchanges before royalty
		fees are assessed. */
		List<BalanceChange> ownershipChanges = null;
		for (var xfers : op.getTokenTransfersList()) {
			final var grpcTokenId = xfers.getToken();
			final var tokenId = Id.fromGrpcToken(grpcTokenId);
			for (var aa : xfers.getTransfersList()) {
				changes.add(changingFtUnits(tokenId, grpcTokenId, aa));
			}
			for (var oc : xfers.getNftTransfersList()) {
				if (ownershipChanges == null) {
					ownershipChanges = new ArrayList<>();
				}
				ownershipChanges.add(changingNftOwnership(tokenId, grpcTokenId, oc));
			}
		}
		if (ownershipChanges != null) {
			changes.addAll(ownershipChanges);
		}
	}

	private boolean hasTokenChanges(CryptoTransferTransactionBody op) {
		for (var tokenTransfers : op.getTokenTransfersList()) {
			if (tokenTransfers.getNftTransfersCount() > 0 || tokenTransfers.getTransfersCount() > 0) {
				return true;
			}
		}
		return false;
	}

	public ImpliedTransfersMeta.ValidationProps currentProps() {
		return new ImpliedTransfersMeta.ValidationProps(
				dynamicProperties.maxTransferListSize(),
				dynamicProperties.maxTokenTransferListSize(),
				dynamicProperties.maxNftTransfersLen(),
				dynamicProperties.maxCustomFeeDepth(),
				dynamicProperties.maxXferBalanceChanges(),
				dynamicProperties.areNftsEnabled());
	}
}
