package com.hedera.test.factories.txns;

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

import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransferList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hedera.test.factories.txns.TinyBarsFromTo.tinyBarsFromTo;
import static java.util.stream.Collectors.toList;

public class CryptoTransferFactory extends SignedTxnFactory<CryptoTransferFactory> {
	public static final List<TinyBarsFromTo> DEFAULT_TRANSFERS = List.of(
			tinyBarsFromTo(DEFAULT_PAYER_ID, DEFAULT_NODE_ID, 1_000L));

	boolean usesTokenBuilders = false;
	boolean adjustmentsAreSet = false;
	List<AccountAmount> hbarAdjustments = new ArrayList<>();
	Map<TokenID, List<AccountAmount>> adjustments = new HashMap<>();
	Map<NftID, List<AccountID>> ownershipChanges = new HashMap<>();
	CryptoTransferTransactionBody.Builder xfers = CryptoTransferTransactionBody.newBuilder();

	private List<TinyBarsFromTo> transfers = DEFAULT_TRANSFERS;

	private CryptoTransferFactory() {
	}

	public static CryptoTransferFactory newSignedCryptoTransfer() {
		return new CryptoTransferFactory();
	}

	public CryptoTransferFactory transfers(TinyBarsFromTo... transfers) {
		this.transfers = List.of(transfers);
		return this;
	}

	public CryptoTransferFactory adjustingHbars(AccountID aId, long amount) {
		usesTokenBuilders = true;
		hbarAdjustments.add(AccountAmount.newBuilder().setAccountID(aId).setAmount(amount).build());
		return this;
	}

	public CryptoTransferFactory changingOwner(NftID nft, AccountID sender, AccountID receiver) {
		usesTokenBuilders = true;
		ownershipChanges.put(nft, List.of(sender, receiver));
		return this;
	}

	public CryptoTransferFactory adjusting(AccountID aId, TokenID tId, long amount) {
		usesTokenBuilders = true;
		adjustments.computeIfAbsent(tId, ignore -> new ArrayList<>())
				.add(AccountAmount.newBuilder()
						.setAccountID(aId)
						.setAmount(amount)
						.build());
		return this;
	}

	@Override
	protected CryptoTransferFactory self() {
		return this;
	}

	@Override
	protected long feeFor(Transaction signedTxn, int numPayerKeys) {
		return 0;
	}

	@Override
	protected void customizeTxn(TransactionBody.Builder txn) {
		if (!usesTokenBuilders) {
			CryptoTransferTransactionBody.Builder op = CryptoTransferTransactionBody.newBuilder();
			op.setTransfers(TransferList.newBuilder().addAllAccountAmounts(transfersAsAccountAmounts()));
			txn.setCryptoTransfer(op);
		} else {
			if (!adjustmentsAreSet) {
				adjustments.entrySet().stream()
						.forEach(entry -> xfers.addTokenTransfers(TokenTransferList.newBuilder()
								.setToken(entry.getKey())
								.addAllTransfers(entry.getValue())
								.build()));
				ownershipChanges.entrySet().stream()
						.forEach(entry -> xfers.addTokenTransfers(TokenTransferList.newBuilder()
								.setToken(entry.getKey().getTokenID())
								.addNftTransfers(NftTransfer.newBuilder()
										.setSenderAccountID(entry.getValue().get(0))
										.setReceiverAccountID(entry.getValue().get(1))
										.setSerialNumber(entry.getKey().getSerialNumber()))));
				xfers.setTransfers(TransferList.newBuilder().addAllAccountAmounts(hbarAdjustments));
				adjustmentsAreSet = true;
			}
			txn.setCryptoTransfer(xfers);
		}
	}

	private List<AccountAmount> transfersAsAccountAmounts() {
		return transfers
				.stream()
				.flatMap(fromTo -> List.of(
						AccountAmount.newBuilder()
								.setAccountID(fromTo.payerId()).setAmount(-1 * fromTo.getAmount()).build(),
						AccountAmount.newBuilder()
								.setAccountID(fromTo.payeeId()).setAmount(fromTo.getAmount()).build()
				).stream()).collect(toList());
	}
}
