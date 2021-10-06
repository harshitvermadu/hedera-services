package com.hedera.services.txns.contract.operation;

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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.FixedStack;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
public class HederaExtCodeSizeOperationTest {
    final private String ethAddress = "0xc257274276a4e539741ca11b590b9447b26a8051";
    final private Address ethAddressInstance = Address.fromHexString(ethAddress);

    @Mock
    WorldUpdater worldUpdater;

    @Mock
    GasCalculator gasCalculator;

    @Mock
    MessageFrame mf;

    @Mock
    EVM evm;

    HederaExtCodeSizeOperation subject;

    @BeforeEach
    void setUp() {
        given(gasCalculator.getExtCodeSizeOperationGasCost()).willReturn(Gas.of(10L));
        given(gasCalculator.getWarmStorageReadCost()).willReturn(Gas.of(2L));

        subject = new HederaExtCodeSizeOperation(gasCalculator);
    }

    @Test
    void executeWorldUpdaterResolvesToNull() {
        given(mf.getStackItem(0)).willReturn(ethAddressInstance);
        given(mf.getWorldUpdater()).willReturn(worldUpdater);

        var opResult = subject.execute(mf, evm);

        assertEquals(Optional.of(HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS), opResult.getHaltReason());
        assertEquals(Optional.of(Gas.of(12L)), opResult.getGasCost());
    }

    @Test
    void executeThrows() {
        given(mf.getStackItem(0)).willThrow(FixedStack.UnderflowException.class);

        var opResult = subject.execute(mf, evm);

        assertEquals(Optional.of(INSUFFICIENT_STACK_ITEMS), opResult.getHaltReason());
        assertEquals(Optional.of(Gas.of(12L)), opResult.getGasCost());
    }
}
