package com.hedera.services.bdd.suites.perf.crypto;

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
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNKNOWN;
import static java.util.concurrent.TimeUnit.SECONDS;

public class SimpleXfersAvoidingHotspot extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(SimpleXfersAvoidingHotspot.class);

	private static final int NUM_ACCOUNTS = 10;

	private AtomicLong duration = new AtomicLong(600);
	private AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
	private AtomicInteger maxOpsPerSec = new AtomicInteger(700);

	public static void main(String... args) {
		new SimpleXfersAvoidingHotspot().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				new HapiApiSpec[] {
						runSimpleXfers(),
				}
		);
	}

	private HapiApiSpec runSimpleXfers() {
		return HapiApiSpec.customHapiSpec("RunTokenTransfers").withProperties(Map.of(
				"default.keyAlgorithm", "SECP256K1"
//				"default.keyAlgorithm", "ED25519"
		)).given().when().then(
				runWithProvider(avoidantXfersFactory())
						.lasting(duration::get, unit::get)
						.maxOpsPerSec(maxOpsPerSec::get)
		);
	}

	private Function<HapiApiSpec, OpProvider> avoidantXfersFactory() {
		final var nextSender = new AtomicInteger();
		final IntFunction<String> nameFn = i -> "account" + i;

		return spec -> new OpProvider() {
			@Override
			public List<HapiSpecOperation> suggestedInitializers() {
				return List.of(inParallel(IntStream.range(0, NUM_ACCOUNTS)
								.mapToObj(i -> uniqueCreation(nameFn.apply(i)))
								.toArray(HapiSpecOperation[]::new)),
						sleepFor(10_000L));
			}

			@Override
			public Optional<HapiSpecOperation> get() {
				final int sender = nextSender.getAndUpdate(i -> (i + 1) % NUM_ACCOUNTS);
				final int receiver = (sender + 1) % NUM_ACCOUNTS;
				final var from = nameFn.apply(sender);
				final var to = nameFn.apply(receiver);
				final var op = cryptoTransfer(tinyBarsFromTo(from, to, 1))
						.payingWith(from)
						.hasKnownStatusFrom(ACCEPTED_STATUSES)
						.deferStatusResolution()
						.noLogging();
				return Optional.of(op);
			}
		};
	}

	private HapiSpecOperation uniqueCreation(final String name) {
		return withOpContext((spec, opLog) -> {
			while (true) {
				try {
					final var attempt = cryptoCreate(name)
							.payingWith(GENESIS)
							.ensuringResolvedStatusIsntFromDuplicate()
							.balance(ONE_HUNDRED_HBARS * 10_000);
					allRunFor(spec, attempt);
					return;
				} catch (IllegalStateException ignore) {
					/* Collision with another client also using the treasury as its payer */
				}
			}
		});
	}

	private static final ResponseCodeEnum[] ACCEPTED_STATUSES = {
			SUCCESS, OK, INSUFFICIENT_PAYER_BALANCE, UNKNOWN, TRANSACTION_EXPIRED
	};

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}