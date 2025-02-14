package com.hedera.services.contracts.execution;

/*
 * -
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 *
 */

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.store.contracts.HederaWorldState;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.account.EvmAccount;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.data.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;

import static com.hedera.test.utils.TxnUtils.assertFailsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class CreateEvmTxProcessorTest {
	private static final int MAX_STACK_SIZE = 1024;

	@Mock
	private HederaWorldState worldState;
	@Mock
	private HbarCentExchange hbarCentExchange;
	@Mock
	private UsagePricesProvider usagePricesProvider;
	@Mock
	private GlobalDynamicProperties globalDynamicProperties;
	@Mock
	private GasCalculator gasCalculator;
	@Mock
	private Set<Operation> operations;
	@Mock
	private Transaction transaction;
	@Mock
	private HederaWorldState.Updater updater;
	@Mock
	private ExchangeRate exchangeRate;

	private CreateEvmTxProcessor createEvmTxProcessor;
	private final Account sender = new Account(new Id(0, 0, 1002));
	private final Account receiver = new Account(new Id(0, 0, 1006));
	private final Instant consensusTime = Instant.now();
	private final long expiry = 123456L;
	private final int MAX_GAS_LIMIT = 10_000_000;

	@BeforeEach
	private void setup() {
		CommonProcessorSetup.setup(gasCalculator);

		createEvmTxProcessor = new CreateEvmTxProcessor(worldState, hbarCentExchange, usagePricesProvider,
				globalDynamicProperties, gasCalculator, operations);
	}

	@Test
	void assertSuccessfulExecution() {
		givenValidMock(true);
		givenSenderWithBalance(350_000L);
		var result = createEvmTxProcessor.execute(sender, receiver.getId().asEvmAddress(), 33_333L, 1234L, Bytes.EMPTY, consensusTime, expiry);
		assertTrue(result.isSuccessful());
		assertEquals(receiver.getId().asGrpcContract(), result.toGrpc().getContractID());
	}

	@Test
	void assertFailedExecution() {
		givenValidMock(false);
		// and:
		given(gasCalculator.mStoreOperationGasCost(any(), anyLong())).willReturn(Gas.of(200));
		given(gasCalculator.mLoadOperationGasCost(any(), anyLong())).willReturn(Gas.of(30));
		given(gasCalculator.memoryExpansionGasCost(any(), anyLong(), anyLong())).willReturn(Gas.of(5000));
		givenSenderWithBalance(350_000L);

		// when:
		var result = createEvmTxProcessor.execute(
				sender,
				receiver.getId().asEvmAddress(),
				33_333L,
				0,
				Bytes.fromHexString(
						"6080604052348015600f57600080fd5b506000604e576040517f08c379a" +
						"00000000000000000000000000000000000000000000000000000000081" +
						"526004016045906071565b60405180910390fd5b60c9565b6000605d601" +
						"183608f565b915060668260a0565b602082019050919050565b60006020" +
						"8201905081810360008301526088816052565b9050919050565b6000828" +
						"25260208201905092915050565b7f636f756c64206e6f74206578656375" +
						"7465000000000000000000000000000000600082015250565b603f80610" +
						"0d76000396000f3fe6080604052600080fdfea2646970667358221220d8" +
						"2b5e4f0118f9b6972aae9287dfe93930fdbc1e62ca10ea7ac70bde1c0ad" +
						"d2464736f6c63430008070033"),
				consensusTime,
				expiry);

		// then:
		assertFalse(result.isSuccessful());
	}

	@Test
	void assertIsContractCallFunctionality() {
		assertEquals(HederaFunctionality.ContractCreate, createEvmTxProcessor.getFunctionType());
	}

	@Test
	void assertTransactionSenderAndValue() {
		// setup:
		doReturn(Optional.of(receiver.getId().asEvmAddress())).when(transaction).getTo();
		given(transaction.getSender()).willReturn(sender.getId().asEvmAddress());
		given(transaction.getValue()).willReturn(Wei.of(1L));
		final MessageFrame.Builder commonInitialFrame =
				MessageFrame.builder()
						.messageFrameStack(mock(Deque.class))
						.maxStackSize(MAX_STACK_SIZE)
						.worldUpdater(mock(WorldUpdater.class))
						.initialGas(mock(Gas.class))
						.originator(sender.getId().asEvmAddress())
						.gasPrice(mock(Wei.class))
						.sender(sender.getId().asEvmAddress())
						.value(Wei.of(transaction.getValue().getAsBigInteger()))
						.apparentValue(Wei.of(transaction.getValue().getAsBigInteger()))
						.blockValues(mock(BlockValues.class))
						.depth(0)
						.completer(__ -> {
						})
						.miningBeneficiary(mock(Address.class))
						.blockHashLookup(h -> null);
		//when:
		MessageFrame buildMessageFrame = createEvmTxProcessor.buildInitialFrame(commonInitialFrame, worldState.updater(), (Address) transaction.getTo().get(), Bytes.EMPTY);

		//expect:
		assertEquals(transaction.getSender(), buildMessageFrame.getSenderAddress());
		assertEquals(transaction.getValue(), buildMessageFrame.getApparentValue());
	}

	@Test
	void throwsWhenSenderCannotCoverUpfrontCost() {
		givenInvalidMock();
		givenSenderWithBalance(123);

		Address receiver = this.receiver.getId().asEvmAddress();
		assertFailsWith(
				() -> createEvmTxProcessor
						.execute(sender, receiver, 33_333L, 1234L, Bytes.EMPTY, consensusTime, expiry),
				ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE);
	}

	@Test
	void throwsWhenIntrinsicGasCostExceedsGasLimit() {
		givenInvalidMock();
		givenSenderWithBalance(200_000);

		Address receiver = this.receiver.getId().asEvmAddress();
		assertFailsWith(
				() -> createEvmTxProcessor
						.execute(sender, receiver, 33_333L, 1234L, Bytes.EMPTY, consensusTime, expiry),
				ResponseCodeEnum.INSUFFICIENT_GAS);
	}

	@Test
	void throwsWhenIntrinsicGasCostExceedsGasLimitAndGasLimitIsEqualToMaxGasLimit() {
		givenInvalidMock();
		givenSenderWithBalance(100_000_000);
		given(gasCalculator.transactionIntrinsicGasCost(Bytes.EMPTY, true)).willReturn(Gas.of(MAX_GAS_LIMIT + 1));

		Address receiver = this.receiver.getId().asEvmAddress();
		assertFailsWith(
				() -> createEvmTxProcessor
						.execute(sender, receiver, MAX_GAS_LIMIT, 1234L, Bytes.EMPTY, consensusTime, expiry),
				ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED);
	}

	private void givenInvalidMock() {
		// given:
		var feeData = mock(FeeData.class);
		given(feeData.getServicedata()).willReturn(mock(FeeComponents.class));
		given(usagePricesProvider.defaultPricesGiven(HederaFunctionality.ContractCreate, Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()).build())).willReturn(feeData);
		given(hbarCentExchange.rate(Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()).build())).willReturn(exchangeRate);
		given(exchangeRate.getHbarEquiv()).willReturn(1);
		given(exchangeRate.getCentEquiv()).willReturn(1);
		// and:
		given(worldState.updater()).willReturn(updater);
		given(globalDynamicProperties.maxGas()).willReturn(MAX_GAS_LIMIT);
		given(gasCalculator.transactionIntrinsicGasCost(Bytes.EMPTY, true)).willReturn(Gas.of(100_000L));
	}

	private void givenValidMock(boolean expectedSuccess) {
		given(worldState.updater()).willReturn(updater);
		given(worldState.updater().updater()).willReturn(updater);
		given(globalDynamicProperties.maxGas()).willReturn(MAX_GAS_LIMIT);
		given(globalDynamicProperties.fundingAccount()).willReturn(new Id(0, 0, 1010).asGrpcAccount());

		var evmAccount = mock(EvmAccount.class);

		given(updater.getOrCreateSenderAccount(sender.getId().asEvmAddress())).willReturn(evmAccount);
		given(worldState.updater()).willReturn(updater);

		given(gasCalculator.transactionIntrinsicGasCost(Bytes.EMPTY, true)).willReturn(Gas.ZERO);

		var senderMutableAccount = mock(MutableAccount.class);
		given(senderMutableAccount.decrementBalance(any())).willReturn(Wei.of(1234L));
		given(senderMutableAccount.incrementBalance(any())).willReturn(Wei.of(1500L));
		given(senderMutableAccount.getNonce()).willReturn(0L);
		given(senderMutableAccount.getCode()).willReturn(Bytes.EMPTY);

		if (expectedSuccess) {
			given(gasCalculator.codeDepositGasCost(0)).willReturn(Gas.ZERO);
		}
		given(gasCalculator.getSelfDestructRefundAmount()).willReturn(Gas.ZERO);
		given(gasCalculator.getMaxRefundQuotient()).willReturn(2L);

		given(updater.getSenderAccount(any())).willReturn(evmAccount);
		given(updater.getSenderAccount(any()).getMutable()).willReturn(senderMutableAccount);
		given(updater.getOrCreate(any())).willReturn(evmAccount);
		given(updater.getOrCreate(any()).getMutable()).willReturn(senderMutableAccount);
		given(updater.getSbhRefund()).willReturn(Gas.ZERO);

		var feeData = mock(FeeData.class);
		given(feeData.getServicedata()).willReturn(mock(FeeComponents.class));
		given(usagePricesProvider.defaultPricesGiven(HederaFunctionality.ContractCreate, Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()).build())).willReturn(feeData);
		given(hbarCentExchange.rate(Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()).build())).willReturn(exchangeRate);
		given(exchangeRate.getHbarEquiv()).willReturn(1);
		given(exchangeRate.getCentEquiv()).willReturn(1);

		given(updater.getSenderAccount(any())).willReturn(evmAccount);
		given(updater.getSenderAccount(any()).getMutable()).willReturn(senderMutableAccount);
		given(updater.updater().getOrCreate(any()).getMutable()).willReturn(senderMutableAccount);

	}

	private void givenSenderWithBalance(final long amount) {
		final var wrappedSenderAccount = mock(EvmAccount.class);
		final var mutableSenderAccount = mock(MutableAccount.class);
		given(wrappedSenderAccount.getMutable()).willReturn(mutableSenderAccount);
		given(mutableSenderAccount.getBalance()).willReturn(Wei.of(amount));
		given(updater.getOrCreateSenderAccount(sender.getId().asEvmAddress())).willReturn(wrappedSenderAccount);
	}
}