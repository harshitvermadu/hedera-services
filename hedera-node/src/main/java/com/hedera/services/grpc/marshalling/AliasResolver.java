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
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.hedera.services.utils.EntityIdUtils.isAlias;
import static com.hedera.services.utils.EntityNum.MISSING_NUM;
import static com.hedera.services.utils.MiscUtils.isSerializedProtoKey;

public class AliasResolver {
	private int perceivedMissing = 0;
	private int perceivedCreations = 0;
	private int perceivedInvalidCreations = 0;
	private Map<ByteString, EntityNum> resolutions = new HashMap<>();

	private enum Result {
		KNOWN_ALIAS, UNKNOWN_ALIAS, REPEATED_UNKNOWN_ALIAS
	}

	public CryptoTransferTransactionBody resolve(
			final CryptoTransferTransactionBody op,
			final AliasManager aliasManager
	) {
		final var resolvedOp = CryptoTransferTransactionBody.newBuilder();

		final var resolvedAdjusts = resolveHbarAdjusts(op.getTransfers(), aliasManager);
		resolvedOp.setTransfers(resolvedAdjusts);

		final var resolvedTokenAdjusts = resolveTokenAdjusts(op.getTokenTransfersList(), aliasManager);
		resolvedOp.addAllTokenTransfers(resolvedTokenAdjusts);

		return resolvedOp.build();
	}

	public Map<ByteString, EntityNum> resolutions() {
		return resolutions;
	}

	public int perceivedMissingAliases() {
		return perceivedMissing;
	}

	public int perceivedAutoCreations() {
		return perceivedCreations;
	}

	public int perceivedInvalidCreations() {
		return perceivedInvalidCreations;
	}

	public static boolean usesAliases(final CryptoTransferTransactionBody op) {
		for (var adjust : op.getTransfers().getAccountAmountsList()) {
			if (isAlias(adjust.getAccountID())) {
				return true;
			}
		}
		for (var tokenAdjusts : op.getTokenTransfersList()) {
			for (var ownershipChange : tokenAdjusts.getNftTransfersList()) {
				if (isAlias(ownershipChange.getSenderAccountID()) || isAlias(ownershipChange.getReceiverAccountID())) {
					return true;
				}
			}
			for (var tokenAdjust : tokenAdjusts.getTransfersList()) {
				if (isAlias(tokenAdjust.getAccountID())) {
					return true;
				}
			}
		}
		return false;
	}

	private List<TokenTransferList> resolveTokenAdjusts(
			final List<TokenTransferList> opTokenAdjusts,
			final AliasManager aliasManager
	) {
		final List<TokenTransferList> resolvedTokenAdjusts = new ArrayList<>();
		for (var tokenAdjust : opTokenAdjusts) {
			final var resolvedTokenAdjust = TokenTransferList.newBuilder();

			resolvedTokenAdjust.setToken(tokenAdjust.getToken());
			for (final var adjust : tokenAdjust.getTransfersList()) {
				final var result = resolveInternalFungible(aliasManager, adjust, resolvedTokenAdjust::addTransfers);
				if (result != Result.KNOWN_ALIAS) {
					perceivedMissing++;
				}
			}
			for (final var change : tokenAdjust.getNftTransfersList()) {
				final var resolvedChange = change.toBuilder().setSerialNumber(change.getSerialNumber());

				final var senderResult = resolveInternal(
						aliasManager, change.getSenderAccountID(), resolvedChange::setSenderAccountID);
				if (senderResult != Result.KNOWN_ALIAS) {
					perceivedMissing++;
				}
				final var receiverResult = resolveInternal(
						aliasManager, change.getReceiverAccountID(), resolvedChange::setReceiverAccountID);
				if (receiverResult != Result.KNOWN_ALIAS) {
					perceivedMissing++;
				}
				resolvedTokenAdjust.addNftTransfers(resolvedChange.build());
			}

			resolvedTokenAdjusts.add(resolvedTokenAdjust.build());
		}
		return resolvedTokenAdjusts;
	}

	private TransferList resolveHbarAdjusts(final TransferList opAdjusts, final AliasManager aliasManager) {
		final var resolvedAdjusts = TransferList.newBuilder();
		for (var adjust : opAdjusts.getAccountAmountsList()) {
			final var result = resolveInternalFungible(aliasManager, adjust, resolvedAdjusts::addAccountAmounts);
			if (result == Result.UNKNOWN_ALIAS) {
				if (adjust.getAmount() > 0) {
					final var alias = adjust.getAccountID().getAlias();
					if (isSerializedProtoKey(alias)) {
						perceivedCreations++;
					} else {
						perceivedInvalidCreations++;
					}
				} else {
					perceivedMissing++;
				}
			} else if (result == Result.REPEATED_UNKNOWN_ALIAS) {
				perceivedInvalidCreations++;
			}
		}
		return resolvedAdjusts.build();
	}

	private Result resolveInternal(
			final AliasManager aliasManager,
			final AccountID idOrAlias,
			final Consumer<AccountID> resolvingAction
	) {
		AccountID resolvedId = idOrAlias;
		var result = Result.KNOWN_ALIAS;
		if (isAlias(idOrAlias)) {
			final var alias = idOrAlias.getAlias();
			final var resolution = aliasManager.lookupIdBy(alias);
			if (resolution != MISSING_NUM) {
				resolvedId = resolution.toGrpcAccountId();
			} else {
				result = resolutions.containsKey(alias) ? Result.REPEATED_UNKNOWN_ALIAS : Result.UNKNOWN_ALIAS;
			}
			resolutions.put(alias, resolution);
		}
		resolvingAction.accept(resolvedId);
		return result;
	}

	private Result resolveInternalFungible(
			final AliasManager aliasManager,
			final AccountAmount adjust,
			final Consumer<AccountAmount> resolvingAction
	) {
		AccountAmount resolvedAdjust = adjust;
		var result = Result.KNOWN_ALIAS;
		if (isAlias(adjust.getAccountID())) {
			final var alias = adjust.getAccountID().getAlias();
			final var resolution = aliasManager.lookupIdBy(alias);
			if (resolution == MISSING_NUM) {
				result = resolutions.containsKey(alias) ? Result.REPEATED_UNKNOWN_ALIAS : Result.UNKNOWN_ALIAS;
			} else {
				resolvedAdjust = adjust.toBuilder().setAccountID(resolution.toGrpcAccountId()).build();
			}
			resolutions.put(alias, resolution);
		}
		resolvingAction.accept(resolvedAdjust);
		return result;
	}
}
