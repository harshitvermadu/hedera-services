package com.hedera.services.bdd.spec.queries.meta;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.google.common.base.MoreObjects;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hedera.services.bdd.spec.assertions.ErroringAssertsProvider;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.legacy.proto.utils.CommonUtils;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.AssessedCustomFee;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenAssociation;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionGetRecordQuery;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import static com.hedera.services.bdd.spec.assertions.AssertUtils.rethrowSummaryError;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asDebits;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTokenId;
import static com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate.correspondingScheduledTxnId;
import static com.hedera.services.bdd.suites.HapiApiSuite.HBAR_TOKEN_SENTINEL;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HapiGetTxnRecord extends HapiQueryOp<HapiGetTxnRecord> {
	private static final Logger log = LogManager.getLogger(HapiGetTxnRecord.class);

	private static final TransactionID defaultTxnId = TransactionID.getDefaultInstance();

	private String txn;
	private boolean scheduled = false;
	private boolean assertNothing = false;
	private boolean useDefaultTxnId = false;
	private boolean requestDuplicates = false;
	private boolean requestChildRecords = false;
	private boolean shouldBeTransferFree = false;
	private boolean assertOnlyPriority = false;
	private boolean assertNothingAboutHashes = false;
	private boolean lookupScheduledFromRegistryId = false;
	private boolean omitPaymentHeaderOnCostAnswer = false;
	private boolean hasAliasInChildRecord = false;
	private List<Pair<String, Long>> accountAmountsToValidate = new ArrayList<>();
	private List<Triple<String, String, Long>> tokenAmountsToValidate = new ArrayList<>();
	private List<AssessedNftTransfer> assessedNftTransfersToValidate = new ArrayList<>();
	private List<Triple<String, String, Long>> assessedCustomFeesToValidate = new ArrayList<>();
	private List<Pair<String, String>> newTokenAssociations = new ArrayList<>();
	private OptionalInt assessedCustomFeesSize = OptionalInt.empty();
	private Optional<TransactionID> explicitTxnId = Optional.empty();
	private Optional<TransactionRecordAsserts> priorityExpectations = Optional.empty();
	private Optional<List<TransactionRecordAsserts>> childRecordsExpectations = Optional.empty();
	private Optional<BiConsumer<TransactionRecord, Logger>> format = Optional.empty();
	private Optional<String> creationName = Optional.empty();
	private Optional<String> saveTxnRecordToRegistry = Optional.empty();
	private Optional<String> registryEntry = Optional.empty();
	private Optional<String> topicToValidate = Optional.empty();
	private Optional<byte[]> lastMessagedSubmitted = Optional.empty();
	private Optional<LongConsumer> priceConsumer = Optional.empty();
	private Optional<Map<AccountID, Long>> expectedDebits = Optional.empty();
	private Optional<Consumer<Map<AccountID, Long>>> debitsConsumer = Optional.empty();
	private Optional<ErroringAssertsProvider<List<TransactionRecord>>> duplicateExpectations = Optional.empty();
	private Optional<Integer> childRecordsCount = Optional.empty();
	private Optional<Integer> childRecordNumber = Optional.empty();
	private Optional<String> aliasKey = Optional.empty();
	private Optional<Consumer<TransactionRecord>> observer = Optional.empty();

	public HapiGetTxnRecord(String txn) {
		this.txn = txn;
	}

	public HapiGetTxnRecord(TransactionID txnId) {
		this.explicitTxnId = Optional.of(txnId);
	}

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.TransactionGetRecord;
	}

	@Override
	protected HapiGetTxnRecord self() {
		return this;
	}

	public HapiGetTxnRecord exposingTo(final Consumer<TransactionRecord> observer) {
		this.observer = Optional.of(observer);
		return this;
	}

	public HapiGetTxnRecord scheduled() {
		scheduled = true;
		return this;
	}

	public HapiGetTxnRecord assertingOnlyPriority() {
		assertOnlyPriority = true;
		return this;
	}

	public HapiGetTxnRecord omittingAnyPaymentForCostAnswer() {
		omitPaymentHeaderOnCostAnswer = true;
		return this;
	}

	public HapiGetTxnRecord scheduledBy(String creation) {
		scheduled = true;
		creationName = Optional.of(creation);
		lookupScheduledFromRegistryId = true;
		return this;
	}

	public HapiGetTxnRecord andAnyDuplicates() {
		requestDuplicates = true;
		return this;
	}

	public HapiGetTxnRecord andAllChildRecords() {
		requestChildRecords = true;
		return this;
	}

	public HapiGetTxnRecord assertingNothingAboutHashes() {
		assertNothingAboutHashes = true;
		return this;
	}

	public HapiGetTxnRecord hasChildRecordCount(int count) {
		requestChildRecords = true;
		childRecordsCount = Optional.of(count);
		return this;
	}

	public HapiGetTxnRecord hasAliasInChildRecord(final String validAlias, int num) {
		requestChildRecords = true;
		hasAliasInChildRecord = true;
		childRecordNumber = Optional.of(num);
		aliasKey = Optional.of(validAlias);
		return this;
	}

	public HapiGetTxnRecord assertingNothing() {
		assertNothing = true;
		return this;
	}

	public HapiGetTxnRecord hasExactDebits(Map<AccountID, Long> expected) {
		expectedDebits = Optional.of(expected);
		return this;
	}

	public HapiGetTxnRecord revealingDebitsTo(Consumer<Map<AccountID, Long>> observer) {
		debitsConsumer = Optional.of(observer);
		return this;
	}

	public HapiGetTxnRecord providingFeeTo(LongConsumer priceConsumer) {
		this.priceConsumer = Optional.of(priceConsumer);
		return this;
	}

	public HapiGetTxnRecord showsNoTransfers() {
		shouldBeTransferFree = true;
		return this;
	}

	public HapiGetTxnRecord saveCreatedContractListToRegistry(String registryEntry) {
		this.registryEntry = Optional.of(registryEntry);
		return this;
	}

	public HapiGetTxnRecord saveTxnRecordToRegistry(String txnRecordEntry) {
		this.saveTxnRecordToRegistry = Optional.of(txnRecordEntry);
		return this;
	}

	public HapiGetTxnRecord useDefaultTxnId() {
		useDefaultTxnId = true;
		return this;
	}

	public HapiGetTxnRecord hasPriority(TransactionRecordAsserts provider) {
		priorityExpectations = Optional.of(provider);
		return this;
	}

	public HapiGetTxnRecord hasChildRecords(TransactionRecordAsserts ...providers) {
		childRecordsExpectations = Optional.of(Arrays.asList(providers));
		return this;
	}

	public HapiGetTxnRecord hasDuplicates(ErroringAssertsProvider<List<TransactionRecord>> provider) {
		duplicateExpectations = Optional.of(provider);
		return this;
	}

	public HapiGetTxnRecord hasCorrectRunningHash(String topic, byte[] lastMessage) {
		topicToValidate = Optional.of(topic);
		lastMessagedSubmitted = Optional.of(lastMessage);
		return this;
	}

	public HapiGetTxnRecord hasCorrectRunningHash(String topic, String lastMessage) {
		hasCorrectRunningHash(topic, lastMessage.getBytes());
		return this;
	}

	public HapiGetTxnRecord loggedWith(BiConsumer<TransactionRecord, Logger> customFormat) {
		super.logged();
		format = Optional.of(customFormat);
		return this;
	}

	public HapiGetTxnRecord hasNewTokenAssociation(final String token, final String account) {
		newTokenAssociations.add(Pair.of(token, account));
		return this;
	}

	public HapiGetTxnRecord hasHbarAmount(final String account, final long amount) {
		accountAmountsToValidate.add(Pair.of(account, amount));
		return this;
	}

	public HapiGetTxnRecord hasTokenAmount(final String token, final String account, final long amount) {
		tokenAmountsToValidate.add(Triple.of(token, account, amount));
		return this;
	}

	public HapiGetTxnRecord hasNftTransfer(final String token,
			final String sender,
			final String receiver,
			final long serial
	) {
		assessedNftTransfersToValidate.add(new AssessedNftTransfer(token, sender, receiver, serial));
		return this;
	}

	public HapiGetTxnRecord hasAssessedCustomFee(String token, String account, long amount) {
		assessedCustomFeesToValidate.add(Triple.of(token, account, amount));
		return this;
	}

	public HapiGetTxnRecord hasAssessedCustomFeesSize(final int size) {
		assessedCustomFeesSize = OptionalInt.of(size);
		return this;
	}

	public TransactionRecord getResponseRecord() {
		return response.getTransactionGetRecord().getTransactionRecord();
	}

	private void assertPriority(HapiApiSpec spec, TransactionRecord actualRecord) throws Throwable {
		if (priorityExpectations.isPresent()) {
			ErroringAsserts<TransactionRecord> asserts = priorityExpectations.get().assertsFor(spec);
			List<Throwable> errors = asserts.errorsIn(actualRecord);
			rethrowSummaryError(log, "Bad priority record!", errors);
		}
		expectedDebits.ifPresent(debits -> assertEquals(debits, asDebits(actualRecord.getTransferList())));
	}

	private void assertChildRecords(HapiApiSpec spec, List<TransactionRecord> actualRecords) throws Throwable {
		if (childRecordsExpectations.isPresent()) {
			final var expectedChildRecords = childRecordsExpectations.get();

			assertEquals(expectedChildRecords.size(), actualRecords.size(), String.format("Expected %d child records, got %d", expectedChildRecords.size(), actualRecords.size()));
			for (int i = 0; i < actualRecords.size(); i++) {
				final var expectedChildRecord = expectedChildRecords.get(i);
				final var actualChildRecord = actualRecords.get(i);

				ErroringAsserts<TransactionRecord> asserts = expectedChildRecord.assertsFor(spec);
				List<Throwable> errors = asserts.errorsIn(actualChildRecord);
				rethrowSummaryError(log, "Bad child records!", errors);
				expectedDebits.ifPresent(debits -> assertEquals(debits, asDebits(actualChildRecord.getTransferList())));
			}
		}
	}

	private void assertDuplicates(HapiApiSpec spec) throws Throwable {
		if (duplicateExpectations.isPresent()) {
			var asserts = duplicateExpectations.get().assertsFor(spec);
			var errors = asserts.errorsIn(response.getTransactionGetRecord().getDuplicateTransactionRecordsList());
			rethrowSummaryError(log, "Bad duplicate records!", errors);
		}
	}

	private void assertTransactionHash(HapiApiSpec spec, TransactionRecord actualRecord) throws Throwable {
		Transaction transaction = Transaction.parseFrom(spec.registry().getBytes(txn));
		assertArrayEquals(CommonUtils.sha384HashOf(transaction).toByteArray(),
				actualRecord.getTransactionHash().toByteArray(),
				"Bad transaction hash!");
	}

	private void assertTopicRunningHash(HapiApiSpec spec, TransactionRecord actualRecord) throws Throwable {
		if (topicToValidate.isPresent()) {
			if (actualRecord.getReceipt().getStatus().equals(ResponseCodeEnum.SUCCESS)) {
				var previousRunningHash = spec.registry().getBytes(topicToValidate.get());
				var payer = actualRecord.getTransactionID().getAccountID();
				var topicId = TxnUtils.asTopicId(topicToValidate.get(), spec);
				var boas = new ByteArrayOutputStream();
				try (var out = new ObjectOutputStream(boas)) {
					out.writeObject(previousRunningHash);
					out.writeLong(spec.setup().defaultTopicRunningHashVersion());
					out.writeLong(payer.getShardNum());
					out.writeLong(payer.getRealmNum());
					out.writeLong(payer.getAccountNum());
					out.writeLong(topicId.getShardNum());
					out.writeLong(topicId.getRealmNum());
					out.writeLong(topicId.getTopicNum());
					out.writeLong(actualRecord.getConsensusTimestamp().getSeconds());
					out.writeInt(actualRecord.getConsensusTimestamp().getNanos());
					out.writeLong(actualRecord.getReceipt().getTopicSequenceNumber());
					out.writeObject(CommonUtils.noThrowSha384HashOf(lastMessagedSubmitted.get()));
					out.flush();
					var expectedRunningHash = CommonUtils.noThrowSha384HashOf(boas.toByteArray());
					var actualRunningHash = actualRecord.getReceipt().getTopicRunningHash();
					assertArrayEquals(expectedRunningHash,
							actualRunningHash.toByteArray(),
							"Bad running hash!");
					spec.registry().saveBytes(topicToValidate.get(), actualRunningHash);
				}
			} else {
				if (verboseLoggingOn) {
					log.warn("Cannot validate running hash for an unsuccessful submit message transaction!");
				}
			}
		}
	}

	@Override
	protected void assertExpectationsGiven(HapiApiSpec spec) throws Throwable {
		if (assertNothing) {
			return;
		}
		final var txRecord = response.getTransactionGetRecord();
		final var actualRecord = txRecord.getTransactionRecord();
		assertCorrectRecord(spec, actualRecord);

		final var childRecords = txRecord.getChildTransactionRecordsList();
		assertChildRecords(spec, childRecords);
	}

	private void assertCorrectRecord(HapiApiSpec spec, TransactionRecord actualRecord) throws Throwable {
		assertPriority(spec, actualRecord);
		if (scheduled || assertOnlyPriority) {
			return;
		}
		assertDuplicates(spec);
		if (!assertNothingAboutHashes) {
			assertTransactionHash(spec, actualRecord);
			assertTopicRunningHash(spec, actualRecord);
		}
		if (shouldBeTransferFree) {
			assertEquals(
					0,
					actualRecord.getTokenTransferListsCount(),
					"Unexpected transfer list!");
		}
		if (!accountAmountsToValidate.isEmpty()) {
			final var accountAmounts = actualRecord.getTransferList().getAccountAmountsList();
			accountAmountsToValidate.forEach(pair ->
					validateAccountAmount(asId(pair.getLeft(), spec), pair.getRight(), accountAmounts));
		}
		final var tokenTransferLists = actualRecord.getTokenTransferListsList();
		if (!tokenAmountsToValidate.isEmpty()) {
			tokenAmountsToValidate.forEach(triple ->
					validateTokenAmount(
							asTokenId(triple.getLeft(), spec),
							asId(triple.getMiddle(), spec),
							triple.getRight(),
							tokenTransferLists));
		}
		if (!assessedNftTransfersToValidate.isEmpty()) {
			assessedNftTransfersToValidate.forEach(transfer ->
					validateAssessedNftTransfer(
							asTokenId(transfer.getToken(), spec),
							asId(transfer.getSender(), spec),
							asId(transfer.getReceiver(), spec),
							transfer.getSerial(),
							tokenTransferLists));
		}
		final var actualAssessedCustomFees = actualRecord.getAssessedCustomFeesList();
		if (assessedCustomFeesSize.isPresent()) {
			assertEquals(assessedCustomFeesSize.getAsInt(), actualAssessedCustomFees.size(),
					"Unexpected size of assessed_custom_fees:\n" + actualAssessedCustomFees);
		}
		if (!assessedCustomFeesToValidate.isEmpty()) {
			assessedCustomFeesToValidate.forEach(triple ->
					validateAssessedCustomFees(
							triple.getLeft().equals(HBAR_TOKEN_SENTINEL) ? null : asTokenId(triple.getLeft(), spec),
							asId(triple.getMiddle(), spec),
							triple.getRight(),
							actualAssessedCustomFees
					));
		}
		final var actualNewTokenAssociations = actualRecord.getAutomaticTokenAssociationsList();
		if (!newTokenAssociations.isEmpty()) {
			newTokenAssociations.forEach(pair ->
					validateNewTokenAssociations(
							asTokenId(pair.getLeft(), spec),
							asId(pair.getRight(), spec), actualNewTokenAssociations));
		}
		if (hasAliasInChildRecord) {
			assertFalse(childRecords.get(childRecordNumber.get()).getAlias().isEmpty());
			assertEquals(spec.registry().getKey(aliasKey.get()).toByteString().toStringUtf8(),
					childRecords.get(childRecordNumber.get()).getAlias().toStringUtf8());
		}
	}

	private void validateNewTokenAssociations(
			final TokenID token, final AccountID account, final List<TokenAssociation> newTokenAssociations) {
		for (var newTokenAssociation : newTokenAssociations) {
			if (newTokenAssociation.getTokenId().equals(token) && newTokenAssociation.getAccountId().equals(account)) {
				return;
			}
		}
		Assertions.fail(cannotFind(token, account) + " in the new_token_associations of the txnRecord");
	}

	private void validateAssessedCustomFees(final TokenID tokenID,
			final AccountID accountID,
			final long amount,
			final List<AssessedCustomFee> assessedCustomFees
	) {
		for (var acf : assessedCustomFees) {
			if (acf.getAmount() == amount
					&& acf.getFeeCollectorAccountId().equals(accountID)
					&& (!acf.hasTokenId() || acf.getTokenId().equals(tokenID))) {
				return;
			}
		}

		Assertions.fail(cannotFind(tokenID, accountID, amount) + " in the assessed_custom_fees of the txnRecord");
	}

	private String cannotFind(final TokenID tokenID, final AccountID accountID) {
		return "Cannot find TokenID: " + tokenID
				+ " AccountID: " + accountID;
	}

	private void validateTokenAmount(final TokenID tokenID,
			final AccountID accountID,
			final long amount,
			final List<TokenTransferList> tokenTransferLists
	) {
		for (final var ttl : tokenTransferLists) {
			if (ttl.getToken().equals(tokenID)) {
				final var accountAmounts = ttl.getTransfersList();
				if (!accountAmounts.isEmpty() && foundInAccountAmountsList(accountID, amount, accountAmounts)) {
					return;
				}
			}
		}

		Assertions.fail(cannotFind(tokenID, accountID, amount) + " in the tokenTransferLists of the txnRecord");
	}

	private String cannotFind(final TokenID tokenID, final AccountID accountID, final long amount) {
		return cannotFind(tokenID, accountID) + " and amount: " + amount;
	}

	private void validateAssessedNftTransfer(final TokenID tokenID,
			final AccountID sender,
			final AccountID receiver,
			final long serial,
			final List<TokenTransferList> tokenTransferLists
	) {
		for (final var ttl : tokenTransferLists) {
			if (ttl.getToken().equals(tokenID)) {
				final var nftTransferList = ttl.getNftTransfersList();
				if (!nftTransferList.isEmpty() && foundInNftTransferList(sender, receiver, serial, nftTransferList)) {
					return;
				}
			}
		}

		Assertions.fail(cannotFind(tokenID, sender, receiver, serial) + " in the tokenTransferLists of the txnRecord");
	}

	private String cannotFind(final TokenID tokenID,
			final AccountID sender,
			final AccountID receiver,
			final long serial
	) {
		return "Cannot find TokenID: " + tokenID
				+ " sender: " + sender
				+ " receiver: " + receiver
				+ " and serial: " + serial;
	}

	private boolean foundInNftTransferList(final AccountID sender,
			final AccountID receiver,
			final long serial,
			final List<NftTransfer> nftTransferList
	) {
		for (final var nftTransfer : nftTransferList) {
			if (nftTransfer.getSerialNumber() == serial
					&& nftTransfer.getSenderAccountID().equals(sender)
					&& nftTransfer.getReceiverAccountID().equals(receiver)) {
				return true;
			}
		}

		return false;
	}

	private void validateAccountAmount(final AccountID accountID,
			final long amount,
			final List<AccountAmount> accountAmountsList
	) {
		final var found = foundInAccountAmountsList(accountID, amount, accountAmountsList);
		assertTrue(found, "Cannot find AccountID: " + accountID
				+ " and amount: " + amount + " in the transferList of the txnRecord");
	}

	private boolean foundInAccountAmountsList(final AccountID accountID,
			final long amount,
			final List<AccountAmount> accountAmountsList
	) {
		for (final var aa : accountAmountsList) {
			if (aa.getAmount() == amount && aa.getAccountID().equals(accountID)) {
				return true;
			}
		}

		return false;
	}

	@Override
	protected void submitWith(HapiApiSpec spec, Transaction payment) throws InvalidProtocolBufferException {
		Query query = getRecordQuery(spec, payment, false);
		response = spec.clients().getCryptoSvcStub(targetNodeFor(spec), useTls).getTxRecordByTxID(query);
		final TransactionRecord record = response.getTransactionGetRecord().getTransactionRecord();
		observer.ifPresent(obs -> obs.accept(record));
		childRecords = response.getTransactionGetRecord().getChildTransactionRecordsList();
		childRecordsCount.ifPresent(count -> assertEquals(count, childRecords.size()));
		for (var rec : childRecords) {
			spec.registry().saveAccountId(rec.getAlias().toStringUtf8(), rec.getReceipt().getAccountID());
			spec.registry().saveKey(rec.getAlias().toStringUtf8(), Key.parseFrom(rec.getAlias()));
			log.info(spec.logPrefix() + "  Saving alias {} to registry for Account ID {}",
					rec.getAlias().toStringUtf8(),
					rec.getReceipt().getAccountID());
		}

		if (verboseLoggingOn) {
			if (format.isPresent()) {
				format.get().accept(record, log);
			} else {
				var fee = record.getTransactionFee();
				var rates = spec.ratesProvider();
				var priceInUsd = sdec(rates.toUsdWithActiveRates(fee), 5);
				log.info(spec.logPrefix() + "Record (charged ${}): {}", priceInUsd, record);
				log.info(spec.logPrefix() + "  And {} child record{}: {}",
						childRecords.size(),
						childRecords.size() > 1 ? "s" : "",
						childRecords);
				log.info("Duplicates: {}",
						response.getTransactionGetRecord().getDuplicateTransactionRecordsList());
			}
		}
		if (response.getTransactionGetRecord().getHeader().getNodeTransactionPrecheckCode() == OK) {
			priceConsumer.ifPresent(pc -> pc.accept(record.getTransactionFee()));
			debitsConsumer.ifPresent(dc -> dc.accept(asDebits(record.getTransferList())));
		}
		if (registryEntry.isPresent()) {
			spec.registry().saveContractList(
					registryEntry.get() + "CreateResult",
					record.getContractCreateResult().getCreatedContractIDsList());
			spec.registry().saveContractList(
					registryEntry.get() + "CallResult",
					record.getContractCallResult().getCreatedContractIDsList());
		}
		if (saveTxnRecordToRegistry.isPresent()) {
			spec.registry().saveTransactionRecord(saveTxnRecordToRegistry.get(), record);
		}
	}

	@Override
	protected long lookupCostWith(HapiApiSpec spec, Transaction payment) throws Throwable {
		Query query = getRecordQuery(spec, payment, true);
		Response response = spec.clients().getCryptoSvcStub(targetNodeFor(spec), useTls).getTxRecordByTxID(query);
		return costFrom(response);
	}

	private Query getRecordQuery(HapiApiSpec spec, Transaction payment, boolean costOnly) {
		TransactionID txnId = useDefaultTxnId
				? defaultTxnId
				: explicitTxnId.orElseGet(() -> spec.registry().getTxnId(txn));
		if (lookupScheduledFromRegistryId) {
			txnId = spec.registry().getTxnId(correspondingScheduledTxnId(creationName.get()));
		} else {
			if (scheduled) {
				txnId = txnId.toBuilder()
						.setScheduled(true)
						.build();
			}
		}
		QueryHeader header;
		if (costOnly && omitPaymentHeaderOnCostAnswer) {
			header = QueryHeader.newBuilder().setResponseType(COST_ANSWER).build();
		} else {
			header = costOnly ? answerCostHeader(payment) : answerHeader(payment);
		}
		TransactionGetRecordQuery getRecordQuery = TransactionGetRecordQuery.newBuilder()
				.setHeader(header)
				.setTransactionID(txnId)
				.setIncludeDuplicates(requestDuplicates)
				.setIncludeChildRecords(requestChildRecords)
				.build();
		return Query.newBuilder().setTransactionGetRecord(getRecordQuery).build();
	}

	@Override
	protected long costOnlyNodePayment(HapiApiSpec spec) throws Throwable {
		return spec.fees().forOp(
				HederaFunctionality.TransactionGetRecord,
				cryptoFees.getCostTransactionRecordQueryFeeMatrices());
	}

	@Override
	protected boolean needsPayment() {
		return true;
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		if (explicitTxnId.isPresent()) {
			return super.toStringHelper().add("explicitTxnId", true);
		} else {
			return super.toStringHelper().add("txn", txn);
		}
	}

	public class AssessedNftTransfer {
		private String token;
		private String sender;
		private String receiver;
		private long serial;

		public AssessedNftTransfer(final String token,
				final String sender,
				final String receiver,
				final long serial
		) {
			this.token = token;
			this.sender = sender;
			this.receiver = receiver;
			this.serial = serial;
		}

		public String getToken() {
			return token;
		}

		public String getSender() {
			return sender;
		}

		public String getReceiver() {
			return receiver;
		}

		public long getSerial() {
			return serial;
		}
	}
}
