package com.hedera.services.queries.contract;

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
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.contracts.execution.CallLocalEvmTxProcessor;
import com.hedera.services.contracts.execution.CallLocalExecutor;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.queries.AbstractAnswer;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.contracts.CodeCache;
import com.hedera.services.store.contracts.HederaWorldState;
import com.hedera.services.store.contracts.StaticEntityAccess;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.ContractCallLocalQuery;
import com.hederahashgraph.api.proto.java.ContractCallLocalResponse;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Optional;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCallLocal;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;

@Singleton
public class ContractCallLocalAnswer extends AbstractAnswer {
	public static final String CONTRACT_CALL_LOCAL_CTX_KEY =
			ContractCallLocalAnswer.class.getSimpleName() + "_localCallResponse";

	private final AccountStore accountStore;
	private final EntityIdSource ids;
	private final OptionValidator validator;
	private final GlobalDynamicProperties dynamicProperties;
	private final NodeLocalProperties nodeProperties;
	private final CallLocalEvmTxProcessor callLocalEvmTxProcessor;

	@Inject
	public ContractCallLocalAnswer(
			final EntityIdSource ids,
			final AccountStore accountStore,
			final OptionValidator validator,
			final GlobalDynamicProperties dynamicProperties,
			final NodeLocalProperties nodeProperties,
			final CallLocalEvmTxProcessor callLocalEvmTxProcessor
	) {
		super(
				ContractCallLocal,
				query -> query.getContractCallLocal().getHeader().getPayment(),
				query -> query.getContractCallLocal().getHeader().getResponseType(),
				response -> response.getContractCallLocal().getHeader().getNodeTransactionPrecheckCode(),
				(query, view) -> {
					var op = query.getContractCallLocal();
					if (op.getGas() < 0) {
						return CONTRACT_NEGATIVE_GAS;
					} else if (op.getGas() > dynamicProperties.maxGas()) {
						return MAX_GAS_LIMIT_EXCEEDED;
					} else {
						return validator.queryableContractStatus(op.getContractID(), view.contracts());
					}
				});

		this.ids = ids;
		this.validator = validator;
		this.accountStore = accountStore;
		this.dynamicProperties = dynamicProperties;
		this.nodeProperties = nodeProperties;
		this.callLocalEvmTxProcessor = callLocalEvmTxProcessor;
	}

	@Override
	public Response responseGiven(Query query, StateView view, ResponseCodeEnum validity, long cost) {
		return responseFor(query, view, validity, cost, NO_QUERY_CTX);
	}

	@Override
	public Response responseGiven(
			Query query,
			StateView view,
			ResponseCodeEnum validity,
			long cost,
			Map<String, Object> queryCtx
	) {
		return responseFor(query, view, validity, cost, Optional.of(queryCtx));
	}

	private Response responseFor(
			Query query,
			StateView view,
			ResponseCodeEnum validity,
			long cost,
			Optional<Map<String, Object>> queryCtx
	) {
		var op = query.getContractCallLocal();
		var response = ContractCallLocalResponse.newBuilder();

		var type = op.getHeader().getResponseType();
		if (validity != OK) {
			response.setHeader(header(validity, type, cost));
		} else {
			if (type == COST_ANSWER) {
				response.setHeader(costAnswerHeader(OK, cost));
			} else {
				setAnswerOnly(response, view, op, cost, queryCtx);
			}
		}

		return Response.newBuilder()
				.setContractCallLocal(response)
				.build();
	}

	@SuppressWarnings("unchecked")
	private void setAnswerOnly(
			ContractCallLocalResponse.Builder response,
			StateView view,
			ContractCallLocalQuery op,
			long cost,
			Optional<Map<String, Object>> queryCtx
	) {
		if (queryCtx.isPresent()) {
			var ctx = queryCtx.get();
			if (!ctx.containsKey(CONTRACT_CALL_LOCAL_CTX_KEY)) {
				throw new IllegalStateException("Query context had no cached local call result!");
			} else {
				response.mergeFrom(
						withCid((ContractCallLocalResponse) ctx.get(CONTRACT_CALL_LOCAL_CTX_KEY), op.getContractID()));
			}
		} else {
			/* If answering from a zero-stake node, there are no node payments, and the
			usage estimator won't have cached the result it got from the local call. */
			try {
				final var entityAccess = new StaticEntityAccess(view, validator, dynamicProperties);
				final var codeCache = new CodeCache(nodeProperties, entityAccess);
				final var worldState = new HederaWorldState(ids, entityAccess, codeCache);
				callLocalEvmTxProcessor.setWorldState(worldState);

				final var callLocalResponse = CallLocalExecutor.execute(accountStore, callLocalEvmTxProcessor, op);
				response.mergeFrom(withCid(callLocalResponse, op.getContractID()));
			} catch (Exception e) {
				response.setHeader(answerOnlyHeader(FAIL_INVALID, cost));
			}
		}
	}

	private ContractCallLocalResponse withCid(ContractCallLocalResponse response, ContractID target) {
		return response.toBuilder()
				.setFunctionResult(response.getFunctionResult().toBuilder()
						.setContractID(target))
				.build();
	}
}
