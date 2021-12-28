package com.hedera.services.state.exports;


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

import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.legacy.core.jproto.JEd25519Key;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleAccountTokens;
import com.hedera.services.state.merkle.internals.CopyOnWriteIds;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.utils.EntityNum;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.swirlds.merkle.map.MerkleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.given;

@ExtendWith({ LogCaptureExtension.class, MockitoExtension.class })
class ToStringAccountsExporterTest {
	private final String testExportLoc = "accounts.txt";
	private final MerkleAccount account1 = new HederaAccountCustomizer()
			.isReceiverSigRequired(true)
			.proxy(EntityId.MISSING_ENTITY_ID)
			.isDeleted(false)
			.expiry(1_234_567L)
			.memo("This ecstasy doth unperplex")
			.isSmartContract(true)
			.key(new JEd25519Key("first-fake".getBytes()))
			.autoRenewPeriod(555_555L)
			.customizing(new MerkleAccount());
	private final MerkleAccount account2 = new HederaAccountCustomizer()
			.isReceiverSigRequired(false)
			.proxy(EntityId.MISSING_ENTITY_ID)
			.isDeleted(true)
			.expiry(7_654_321L)
			.memo("We said, and show us what we love")
			.isSmartContract(false)
			.key(new JEd25519Key("second-fake".getBytes()))
			.autoRenewPeriod(444_444L)
			.customizing(new MerkleAccount());

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private ToStringAccountsExporter subject;

	@Mock
	private NodeLocalProperties nodeLocalProperties;


	@BeforeEach
	void setUp() {
		subject = new ToStringAccountsExporter(nodeLocalProperties);
	}

	@Test
	void toFileDoesNothingIfNoExportRequested() {
		// when:
		subject.toFile(new MerkleMap<>());

		// expect:
		assertFalse(new File(testExportLoc).exists());
	}

	@Test
	void warnsOnIoe() {
		given(nodeLocalProperties.exportAccountsOnStartup()).willReturn(true);
		given(nodeLocalProperties.accountsExportPath()).willReturn("/this/is/not/a/path");

		// expect:
		assertDoesNotThrow(() -> subject.toFile(new MerkleMap<>()));
		// and:
		assertThat(logCaptor.warnLogs(), contains(startsWith("Could not export accounts to '/this/is/not/a/path'")));
	}

	@Test
	void producesExpectedText() throws Exception {
		// setup:
		account1.setBalance(1L);
		account1.setTokens(new MerkleAccountTokens(new CopyOnWriteIds(new long[] { 1L, 2L, 3L, 3L, 2L, 1L })));
		account1.setMaxAutomaticAssociations(10);
		account1.setAlreadyUsedAutomaticAssociations(7);
		account2.setBalance(2L);
		account2.setTokens(new MerkleAccountTokens(new CopyOnWriteIds(new long[] { 0L, 0L, 1234L })));
		// and:
		var desired = "0.0.1\n" +
				"---\n" +
				"MerkleAccount{state=MerkleAccountState{number=1 <-> 0.0.1, key=ed25519: \"first-fake\"\n" +
				", expiry=1234567, balance=1, autoRenewSecs=555555, memo=This ecstasy doth unperplex, deleted=false, " +
				"smartContract=true, receiverSigRequired=true, proxy=EntityId{shard=0, realm=0, num=0}, nftsOwned=0, " +
				"alreadyUsedAutoAssociations=7, maxAutoAssociations=10, alias=}, # records=0, " +
				"tokens=[3.2.1, 1.2.3]}\n" +
				"\n" +
				"0.0.2\n" +
				"---\n" +
				"MerkleAccount{state=MerkleAccountState{number=2 <-> 0.0.2, key=ed25519: \"second-fake\"\n" +
				", expiry=7654321, balance=2, autoRenewSecs=444444, memo=We said, and show us what we love, " +
				"deleted=true, smartContract=false, receiverSigRequired=false, proxy=EntityId{shard=0, realm=0, " +
				"num=0}, nftsOwned=0, alreadyUsedAutoAssociations=0, maxAutoAssociations=0, alias=}, # records=0, tokens=[1234.0.0]}\n";

		// given:
		MerkleMap<EntityNum, MerkleAccount> accounts = new MerkleMap<>();
		// and:
		accounts.put(EntityNum.fromInt(2), account2);
		accounts.put(EntityNum.fromInt(1), account1);
		// and:
		given(nodeLocalProperties.exportAccountsOnStartup()).willReturn(true);
		given(nodeLocalProperties.accountsExportPath()).willReturn(testExportLoc);

		// when:
		subject.toFile(accounts);
		// and:
		var result = Files.readString(Paths.get(testExportLoc));

		// then:
		assertEquals(desired, result);
	}

	@AfterEach
	void cleanup() {
		var f = new File(testExportLoc);
		if (f.exists()) {
			f.delete();
		}
	}
}
