package com.hedera.services.txns.validation;

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
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.TransferList;
import com.swirlds.merkle.map.MerkleMap;

import java.time.Instant;

/**
 * Defines a type able to divine the validity of various options that can appear in HAPI gRPC transactions.
 */

public interface OptionValidator {
	boolean hasGoodEncoding(Key key);
	boolean isValidExpiry(Timestamp expiry);
	boolean isThisNodeAccount(AccountID id);
	boolean isValidTxnDuration(long duration);
	boolean isAfterConsensusSecond(long now);
	boolean isValidAutoRenewPeriod(Duration autoRenewPeriod);
	boolean isAcceptableTransfersLength(TransferList accountAmounts);
	JKey attemptDecodeOrThrow(Key k);

	ResponseCodeEnum memoCheck(String cand);
	ResponseCodeEnum rawMemoCheck(byte[] cand);
	ResponseCodeEnum rawMemoCheck(byte[] cand, boolean hasZeroByte);
	ResponseCodeEnum tokenNameCheck(String name);
	ResponseCodeEnum tokenSymbolCheck(String symbol);

	boolean isPermissibleTotalNfts(long proposedTotal);
	ResponseCodeEnum nftMetadataCheck(byte[] metadata);
	ResponseCodeEnum maxBatchSizeMintCheck(int length);
	ResponseCodeEnum maxBatchSizeWipeCheck(int length);
	ResponseCodeEnum maxBatchSizeBurnCheck(int length);
	ResponseCodeEnum maxNftTransfersLenCheck(int length);
	ResponseCodeEnum nftMaxQueryRangeCheck(long start, long end);

	ResponseCodeEnum queryableTopicStatus(TopicID id, MerkleMap<EntityNum, MerkleTopic> topics);

	JKey attemptToDecodeOrThrow(Key key, ResponseCodeEnum code);

	default ResponseCodeEnum queryableAccountStatus(AccountID id, MerkleMap<EntityNum, MerkleAccount> accounts) {
		return queryableAccountStatus(EntityNum.fromAccountId(id), accounts);
	}

	default ResponseCodeEnum queryableAccountStatus(EntityNum entityNum, MerkleMap<EntityNum, MerkleAccount> accounts) {
		return PureValidation.queryableAccountStatus(entityNum, accounts);
	}

	default ResponseCodeEnum queryableContractStatus(ContractID cid, MerkleMap<EntityNum, MerkleAccount> contracts) {
		return PureValidation.queryableContractStatus(cid, contracts);
	}

	default ResponseCodeEnum queryableFileStatus(FileID fid, StateView view) {
		return PureValidation.queryableFileStatus(fid, view);
	}

	default Instant asCoercedInstant(Timestamp when) {
		return PureValidation.asCoercedInstant(when);
	}

	default boolean isPlausibleAccount(AccountID id) {
		return id.getAccountNum() > 0 &&
				id.getRealmNum() >= 0 &&
				id.getShardNum() >= 0;
	}

	default boolean isPlausibleTxnFee(long amount) {
		return amount >= 0;
	}

	default ResponseCodeEnum chronologyStatus(TxnAccessor accessor, Instant consensusTime) {
		return PureValidation.chronologyStatus(
				consensusTime,
				asCoercedInstant(accessor.getTxnId().getTransactionValidStart()),
				accessor.getTxn().getTransactionValidDuration().getSeconds());
	}

	default ResponseCodeEnum chronologyStatusForTxn(
			Instant validAfter,
			long forSecs,
			Instant estimatedConsensusTime
	) {
		return PureValidation.chronologyStatus(estimatedConsensusTime, validAfter, forSecs);
	}
}
