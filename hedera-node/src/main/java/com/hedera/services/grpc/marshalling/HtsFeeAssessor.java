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

import com.hedera.services.state.submerkle.FcAssessedCustomFee;
import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.store.models.Id;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

import static com.hedera.services.grpc.marshalling.AdjustmentUtils.adjustForAssessed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

@Singleton
public class HtsFeeAssessor {
	@Inject
	public HtsFeeAssessor() {
	}

	public ResponseCodeEnum assess(
			Id payer,
			Id chargingToken,
			FcCustomFee htsFee,
			BalanceChangeManager changeManager,
			List<FcAssessedCustomFee> accumulator
	) {
		final var collector = htsFee.getFeeCollectorAsId();
		final var fixedSpec = htsFee.getFixedFeeSpec();
		final var amount = fixedSpec.getUnitsToCollect();
		final var denominatingToken = fixedSpec.getTokenDenomination().asId();
		adjustForAssessed(payer, chargingToken, collector, denominatingToken, amount, changeManager);

		final var effPayerAccountNums = new long[] { payer.num() };
		final var assessed = new FcAssessedCustomFee(
				htsFee.getFeeCollector(),
				fixedSpec.getTokenDenomination(),
				amount,
				effPayerAccountNums);
		accumulator.add(assessed);

		return OK;
	}
}
