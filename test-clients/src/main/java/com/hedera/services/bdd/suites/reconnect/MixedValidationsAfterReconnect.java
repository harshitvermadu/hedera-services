package com.hedera.services.bdd.suites.reconnect;

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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;

public class MixedValidationsAfterReconnect extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(MixedValidationsAfterReconnect.class);

	public static void main(String... args) {
		new MixedValidationsAfterReconnect().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				getAccountBalanceFromAllNodes(),
				validateTopicInfo(),
				validateFileInfo()
		);
	}

	private HapiApiSpec getAccountBalanceFromAllNodes() {
		String sender = "0.0.1002";
		String receiver = "0.0.1003";
		String lastlyCreatedAccount = "0.0.21013";
		return defaultHapiSpec("GetAccountBalanceFromAllNodes")
				.given().when().then(
						balanceSnapshot("senderBalance", sender), // from default node 0.0.3
						balanceSnapshot("receiverBalance", receiver), // from default node 0.0.3
						balanceSnapshot("lastlyCreatedAccountBalance", lastlyCreatedAccount), // from default node 0.0.3
						getAccountBalance(sender).logged().setNode("0.0.4")
								.hasTinyBars(changeFromSnapshot("senderBalance", 0)),
						getAccountBalance(receiver).logged().setNode("0.0.4")
								.hasTinyBars(changeFromSnapshot("receiverBalance", 0)),
						getAccountBalance(sender).logged().setNode("0.0.5")
								.hasTinyBars(changeFromSnapshot("senderBalance", 0)),
						getAccountBalance(receiver).logged().setNode("0.0.5")
								.hasTinyBars(changeFromSnapshot("receiverBalance", 0)),
						getAccountBalance(sender).logged().setNode("0.0.8")
								.hasTinyBars(changeFromSnapshot("senderBalance", 0)),
						getAccountBalance(receiver).logged().setNode("0.0.8")
								.hasTinyBars(changeFromSnapshot("receiverBalance", 0)),
						getAccountBalance(lastlyCreatedAccount).logged().setNode("0.0.8")
								.hasTinyBars(changeFromSnapshot("lastlyCreatedAccountBalance", 0))
				);
	}

	private HapiApiSpec validateTopicInfo() {
		String firstlyCreatedTopic = "0.0.21014";
		String lastlyCreatedTopic = "0.0.41013";
		String invalidTopicId = "0.0.41014";
		String topicIdWithMessagesSubmittedTo = "0.0.30000";
		byte[] emptyRunningHash = new byte[48];
		return defaultHapiSpec("ValidateTopicInfo")
				.given(
						getTopicInfo(topicIdWithMessagesSubmittedTo).logged().saveRunningHash()
				).when().then(
						getTopicInfo(firstlyCreatedTopic).logged().setNode("0.0.8")
								.hasRunningHash(emptyRunningHash),
						getTopicInfo(lastlyCreatedTopic).logged().setNode("0.0.8")
								.hasRunningHash(emptyRunningHash),
						getTopicInfo(invalidTopicId).hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_TOPIC_ID),
						getTopicInfo(topicIdWithMessagesSubmittedTo).logged().setNode("0.0.8")
								.hasRunningHash(topicIdWithMessagesSubmittedTo)
				);
	}

	private HapiApiSpec validateFileInfo() {
		String firstlyCreatedFile = "0.0.41014";
		String lastlyCreatedFile = "0.0.42013";
		String invalidFileId = "0.0.42014";
		return defaultHapiSpec("ValidateFileInfo")
				.given().when().then(
						getFileInfo(firstlyCreatedFile).logged().setNode("0.0.8"),
						getFileInfo(lastlyCreatedFile).logged().setNode("0.0.8"),
						getFileInfo(invalidFileId).hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_FILE_ID)
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
