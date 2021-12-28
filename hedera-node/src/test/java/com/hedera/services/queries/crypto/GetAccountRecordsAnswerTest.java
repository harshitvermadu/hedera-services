package com.hedera.services.queries.crypto;

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

import com.hedera.services.context.StateChildren;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.queries.answering.AnswerFunctions;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.store.tokens.views.EmptyUniqTokenViewFactory;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.EntityNum;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hederahashgraph.api.proto.java.CryptoGetAccountRecordsQuery;
import com.hederahashgraph.api.proto.java.CryptoGetAccountRecordsResponse;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.swirlds.merkle.map.MerkleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.hedera.services.state.serdes.DomainSerdesTest.recordOne;
import static com.hedera.services.state.serdes.DomainSerdesTest.recordTwo;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.QueryUtils.defaultPaymentTxn;
import static com.hedera.test.utils.QueryUtils.payer;
import static com.hedera.test.utils.QueryUtils.queryHeaderOf;
import static com.hedera.test.utils.QueryUtils.queryOf;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoGetAccountRecords;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RESULT_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;

class GetAccountRecordsAnswerTest {
	private static final long fee = 1_234L;
	private StateView view;
	private MerkleMap<EntityNum, MerkleAccount> accounts;
	private static final String target = payer;
	private MerkleAccount payerAccount;
	private OptionValidator optionValidator;

	private GetAccountRecordsAnswer subject;

	private NodeLocalProperties nodeProps;

	@BeforeEach
	private void setup() throws Exception {
		payerAccount = MerkleAccountFactory.newAccount()
				.accountKeys(COMPLEX_KEY_ACCOUNT_KT)
				.proxy(asAccount("1.2.3"))
				.senderThreshold(1_234L)
				.receiverThreshold(4_321L)
				.receiverSigRequired(true)
				.balance(555L)
				.autoRenewPeriod(1_000_000L)
				.expirationTime(9_999_999L)
				.get();
		payerAccount.records().offer(recordOne());
		payerAccount.records().offer(recordTwo());

		accounts = mock(MerkleMap.class);
		given(accounts.get(EntityNum.fromAccountId(asAccount(target)))).willReturn(payerAccount);

		nodeProps = mock(NodeLocalProperties.class);
		final StateChildren children = new StateChildren();
		children.setAccounts(accounts);
		view = new StateView(
				null,
				null,
				children,
				EmptyUniqTokenViewFactory.EMPTY_UNIQ_TOKEN_VIEW_FACTORY);

		optionValidator = mock(OptionValidator.class);

		subject = new GetAccountRecordsAnswer(new AnswerFunctions(), optionValidator);
	}

	@Test
	void requiresAnswerOnlyCostAsExpected() {
		assertTrue(subject.needsAnswerOnlyCost(validQuery(COST_ANSWER, 0, target)));
		assertFalse(subject.needsAnswerOnlyCost(validQuery(ANSWER_ONLY, 0, target)));
	}

	@Test
	void getsInvalidResponse() {
		final var query = validQuery(ANSWER_ONLY, fee, target);

		final var response = subject.responseGiven(query, view, ACCOUNT_DELETED, fee);

		validate(response, ACCOUNT_DELETED, ANSWER_ONLY, fee);
	}

	@Test
	void getsCostAnswerResponse() {
		final var query = validQuery(COST_ANSWER, fee, target);

		final var response = subject.responseGiven(query, view, OK, fee);

		validate(response, OK, COST_ANSWER, fee);
	}

	@Test
	void getsTheAccountRecords() {
		final var query = validQuery(ANSWER_ONLY, fee, target);

		final var response = subject.responseGiven(query, view, OK, fee);

		validate(response, OK, ANSWER_ONLY, 0L);
		assertEquals(ExpirableTxnRecord.allToGrpc(payerAccount.recordList()),
				response.getCryptoGetAccountRecords().getRecordsList());
	}

	@Test
	void usesValidator() {
		final var query = validQuery(COST_ANSWER, fee, target);
		given(optionValidator.queryableAccountStatus(asAccount(target), accounts)).willReturn(ACCOUNT_DELETED);

		final var validity = subject.checkValidity(query, view);

		assertEquals(ACCOUNT_DELETED, validity);
	}

	@Test
	void getsExpectedPayment() {
		final var query = validQuery(COST_ANSWER, fee, target);

		assertEquals(defaultPaymentTxn(fee), subject.extractPaymentFrom(query).get().getSignedTxnWrapper());
	}

	@Test
	void recognizesFunction() {
		assertEquals(CryptoGetAccountRecords, subject.canonicalFunction());
	}

	@Test
	void requiresAnswerOnlyPayment() {
		assertFalse(subject.requiresNodePayment(validQuery(COST_ANSWER, 0, target)));
		assertTrue(subject.requiresNodePayment(validQuery(ANSWER_ONLY, 0, target)));
	}

	@Test
	void getsValidity() {
		final var response = Response.newBuilder().setCryptoGetAccountRecords(
				CryptoGetAccountRecordsResponse.newBuilder()
						.setHeader(subject.answerOnlyHeader(RESULT_SIZE_LIMIT_EXCEEDED))).build();

		assertEquals(RESULT_SIZE_LIMIT_EXCEEDED, subject.extractValidityFrom(response));
	}

	private void validate(
			final Response response,
			final ResponseCodeEnum precheck,
			final ResponseType type,
			final long fee
	) {
		assertTrue(response.hasCryptoGetAccountRecords());
		final var opResponse = response.getCryptoGetAccountRecords();

		assertTrue(opResponse.hasHeader(), "Missing response header!");
		final var header = opResponse.getHeader();

		assertEquals(precheck, header.getNodeTransactionPrecheckCode());
		assertEquals(type, header.getResponseType());
		assertEquals(fee, header.getCost());
	}

	private Query validQuery(final ResponseType type, final long payment, final String idLit) {
		final var header = queryHeaderOf(type, payment);
		final var op = CryptoGetAccountRecordsQuery.newBuilder()
				.setHeader(header)
				.setAccountID(asAccount(idLit));
		return queryOf(op);
	}
}
