package com.hedera.services.txns.span;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.grpc.marshalling.CustomFeeMeta;
import com.hedera.services.grpc.marshalling.ImpliedTransfers;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMarshal;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMeta;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.state.submerkle.FcAssessedCustomFee;
import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hedera.services.usage.crypto.CryptoTransferMeta;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_ALIASES;
import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_CUSTOM_FEES;
import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_CUSTOM_FEE_META;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SpanMapManagerTest {
	private final int maxHbarAdjusts = 1;
	private final int maxTokenAdjusts = 2;
	private final int maxOwnershipChanges = 3;
	private final boolean areNftsEnabled = false;
	private final int maxFeeNesting = 4;
	private final int maxBalanceChanges = 5;
	private final ImpliedTransfersMeta.ValidationProps validationProps = new ImpliedTransfersMeta.ValidationProps(
			maxHbarAdjusts, maxTokenAdjusts, maxOwnershipChanges, maxFeeNesting, maxBalanceChanges, areNftsEnabled);
	private final ImpliedTransfersMeta.ValidationProps otherValidationProps = new ImpliedTransfersMeta.ValidationProps(
			maxHbarAdjusts, maxTokenAdjusts, maxOwnershipChanges + 1, maxFeeNesting, maxBalanceChanges, areNftsEnabled);
	private final TransactionBody pretendXferTxn = TransactionBody.getDefaultInstance();
	private final ImpliedTransfers someImpliedXfers = ImpliedTransfers.invalid(
			validationProps, ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
	private final ImpliedTransfers someOtherImpliedXfers = ImpliedTransfers.invalid(
			otherValidationProps, ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
	private final ImpliedTransfers someValidImpliedXfers = ImpliedTransfers.valid(
			validationProps, Collections.emptyList(), NO_CUSTOM_FEE_META, NO_CUSTOM_FEES, NO_ALIASES, 2);

	private final Id treasury = new Id(0 , 0, 2);
	private final Id customFeeToken = new Id(0, 0, 123);
	private final Id customFeeCollector = new Id(0, 0, 124);
	final List<CustomFeeMeta> entityCustomFees = List.of(
			new CustomFeeMeta(customFeeToken, treasury, new ArrayList<>()));

	final List<CustomFeeMeta> newCustomFeeChanges = List.of(
			new CustomFeeMeta(
					customFeeToken, treasury, List.of(FcCustomFee.fixedFee(
							10L, customFeeToken.asEntityId(), customFeeCollector.asEntityId()))));
	private final long [] effPayerNum = new long[] { 123L };
	private final List<FcAssessedCustomFee> assessedCustomFees = List.of(
			new FcAssessedCustomFee(customFeeCollector.asEntityId(), customFeeToken.asEntityId(), 123L, effPayerNum),
			new FcAssessedCustomFee(customFeeCollector.asEntityId(), 123L, effPayerNum)
	);

	private final ImpliedTransfers validImpliedTransfers = ImpliedTransfers.valid(
			validationProps, new ArrayList<>(), entityCustomFees, assessedCustomFees);
	private final ImpliedTransfers feeChangedImpliedTransfers = ImpliedTransfers.valid(
			otherValidationProps, new ArrayList<>(), newCustomFeeChanges, assessedCustomFees);

	private final ExpandHandleSpanMapAccessor spanMapAccessor = new ExpandHandleSpanMapAccessor();

	private CryptoTransferMeta xferMeta = new CryptoTransferMeta(1, 1, 1, 0);

	private Map<String, Object> span = new HashMap<>();

	@Mock
	private TxnAccessor accessor;
	@Mock
	private ImpliedTransfersMarshal impliedTransfersMarshal;
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private ImpliedTransfers mockImpliedTransfers;
	@Mock
	private CustomFeeSchedules customFeeSchedules;
	@Mock
	private AliasManager aliasManager;

	private SpanMapManager subject;

	@BeforeEach
	void setUp() {
		subject = new SpanMapManager(impliedTransfersMarshal, dynamicProperties, customFeeSchedules, aliasManager);
	}

	@Test
	void expandsImpliedTransfersForCryptoTransfer() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(accessor.availXferUsageMeta()).willReturn(xferMeta);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer()))
				.willReturn(someImpliedXfers);

		// when:
		subject.expandSpan(accessor);

		// then:
		assertSame(someImpliedXfers, spanMapAccessor.getImpliedTransfers(accessor));
	}

	@Test
	void setsNumAutoCreationsOnExpanding() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(accessor.availXferUsageMeta()).willReturn(xferMeta);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer()))
				.willReturn(someValidImpliedXfers);

		subject.expandSpan(accessor);

		verify(accessor).setNumAutoCreations(2);
	}

	@Test
	void expandsImpliedTransfersWithDetails() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(accessor.availXferUsageMeta()).willReturn(xferMeta);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer()))
				.willReturn(mockImpliedTransfers);
		given(mockImpliedTransfers.getAssessedCustomFees()).willReturn(assessedCustomFees);
		final var mockMeta = mock(ImpliedTransfersMeta.class);
		given(mockImpliedTransfers.getMeta()).willReturn(mockMeta);

		// when:
		subject.expandSpan(accessor);

		// then:
		assertEquals(1, xferMeta.getCustomFeeTokenTransfers());
		assertEquals(1, xferMeta.getNumTokensInvolved());
		assertEquals(1, xferMeta.getCustomFeeHbarTransfers());
	}

	@Test
	void doesntRecomputeImpliedTransfersIfMetaMatches() {
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(dynamicProperties.maxTransferListSize()).willReturn(maxHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxTokenAdjusts);
		given(dynamicProperties.maxNftTransfersLen()).willReturn(maxOwnershipChanges);
		given(dynamicProperties.maxXferBalanceChanges()).willReturn(maxBalanceChanges);
		given(dynamicProperties.maxCustomFeeDepth()).willReturn(maxFeeNesting);
		spanMapAccessor.setImpliedTransfers(accessor, someImpliedXfers);

		// when:
		subject.rationalizeSpan(accessor);

		// then:
		verify(impliedTransfersMarshal, never()).unmarshalFromGrpc(any());
		assertSame(someImpliedXfers, spanMapAccessor.getImpliedTransfers(accessor));
	}

	@Test
	void recomputesImpliedTransfersIfMetaNotMatches() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(accessor.availXferUsageMeta()).willReturn(xferMeta);
		given(dynamicProperties.maxTransferListSize()).willReturn(maxHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxTokenAdjusts + 1);
		spanMapAccessor.setImpliedTransfers(accessor, someImpliedXfers);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer()))
				.willReturn(someOtherImpliedXfers);

		// when:
		subject.rationalizeSpan(accessor);

		// then:
		verify(impliedTransfersMarshal).unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer());
		assertSame(someOtherImpliedXfers, spanMapAccessor.getImpliedTransfers(accessor));
	}

	@Test
	void recomputesImpliedTransfersIfCustomFeeChanges() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(accessor.availXferUsageMeta()).willReturn(xferMeta);
		given(dynamicProperties.maxTransferListSize()).willReturn(maxHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxTokenAdjusts + 1);
		spanMapAccessor.setImpliedTransfers(accessor, validImpliedTransfers);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer()))
				.willReturn(feeChangedImpliedTransfers);

		// when:
		subject.rationalizeSpan(accessor);

		// then:
		verify(impliedTransfersMarshal).unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer());
		assertSame(feeChangedImpliedTransfers, spanMapAccessor.getImpliedTransfers(accessor));
	}
}
