package com.hedera.services.ledger.accounts;

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
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.state.merkle.MerkleAccount;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static com.hedera.services.ledger.accounts.AccountCustomizer.Option;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HederaAccountCustomizerTest {
	HederaAccountCustomizer subject = new HederaAccountCustomizer();

	@Test
	void hasExpectedOptionProps() {
		// given:
		Map<Option, AccountProperty> optionProperties = subject.getOptionProperties();

		// expect:
		Arrays.stream(Option.class.getEnumConstants()).forEach(
				option -> assertEquals(AccountProperty.valueOf(option.toString()), optionProperties.get(option))
		);
	}

	@Test
	void canCustomizeAlias() {
		final var target = new MerkleAccount();
		final var alias = ByteString.copyFromUtf8("FAKE");
		subject.alias(alias).customizing(target);
		assertEquals(alias, target.getAlias());
	}
}
