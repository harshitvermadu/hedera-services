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

import com.google.protobuf.ByteString;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.ledger.PureTransferSemanticChecks;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hedera.services.utils.EntityNum;
import com.hedera.test.factories.keys.KeyFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_ALIASES;
import static com.hedera.services.grpc.marshalling.ImpliedTransfers.NO_CUSTOM_FEE_META;
import static com.hedera.services.ledger.BalanceChange.changingHbar;
import static com.hedera.services.ledger.BalanceChange.changingNftOwnership;
import static com.hedera.services.ledger.BalanceChange.tokenAdjust;
import static com.hedera.test.utils.IdUtils.adjustFrom;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.asToken;
import static com.hedera.test.utils.IdUtils.nftXfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ImpliedTransfersMarshalTest {
	private final List<CustomFeeMeta> mockFinalMeta = List.of(CustomFeeMeta.MISSING_META);
	private final Map<ByteString, EntityNum> mockAliases = Map.of(ByteString.copyFromUtf8("A"), EntityNum.fromLong(1L));

	private CryptoTransferTransactionBody op;

	@Mock
	private FeeAssessor feeAssessor;
	@Mock
	private CustomFeeSchedules customFeeSchedules;
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private PureTransferSemanticChecks xferChecks;
	@Mock
	private BalanceChangeManager.ChangeManagerFactory changeManagerFactory;
	@Mock
	private Function<CustomFeeSchedules, CustomSchedulesManager> customSchedulesFactory;
	@Mock
	private BalanceChangeManager changeManager;
	@Mock
	private CustomSchedulesManager schedulesManager;
	@Mock
	private AliasManager aliasManager;
	@Mock
	private AliasResolver aliasResolver;
	@Mock
	private Predicate<CryptoTransferTransactionBody> aliasCheck;

	private ImpliedTransfersMarshal subject;

	@BeforeEach
	void setUp() {
		subject = new ImpliedTransfersMarshal(
				feeAssessor,
				aliasManager,
				customFeeSchedules,
				() -> aliasResolver,
				dynamicProperties,
				xferChecks,
				aliasCheck,
				changeManagerFactory,
				customSchedulesFactory);
	}

	@Test
	void rejectsPerceivedMissing() {
		setupHbarOnlyFixture();
		setupProps();

		given(aliasCheck.test(op)).willReturn(true);
		given(aliasResolver.resolve(op, aliasManager)).willReturn(op);
		given(aliasResolver.perceivedMissingAliases()).willReturn(1);
		given(aliasResolver.resolutions()).willReturn(mockAliases);

		final var expectedMeta = new ImpliedTransfersMeta(
				props, INVALID_ACCOUNT_ID, NO_CUSTOM_FEE_META, mockAliases);

		final var result = subject.unmarshalFromGrpc(op);

		assertEquals(result.getMeta(), expectedMeta);
	}

	@Test
	void rejectsPerceivedInvalidAliasKey() {
		setupHbarOnlyFixture();
		setupProps();

		given(aliasCheck.test(op)).willReturn(true);
		given(aliasResolver.resolve(op, aliasManager)).willReturn(op);
		given(aliasResolver.perceivedInvalidCreations()).willReturn(1);
		given(aliasResolver.resolutions()).willReturn(mockAliases);

		final var expectedMeta = new ImpliedTransfersMeta(
				props, INVALID_ALIAS_KEY, NO_CUSTOM_FEE_META, mockAliases);

		final var result = subject.unmarshalFromGrpc(op);

		assertEquals(result.getMeta(), expectedMeta);
	}

	@Test
	void startsWithChecks() {
		setupHbarOnlyFixture();
		setupProps();

		final var expectedMeta = new ImpliedTransfersMeta(
				props, TRANSFER_LIST_SIZE_LIMIT_EXCEEDED, Collections.emptyList(), NO_ALIASES);

		givenValidity(TRANSFER_LIST_SIZE_LIMIT_EXCEEDED);

		final var result = subject.unmarshalFromGrpc(op);

		assertEquals(result.getMeta(), expectedMeta);
	}

	@Test
	void getsHbarOnly() {
		setupHbarOnlyFixture();
		setupProps();
		given(aliasCheck.test(op)).willReturn(true);
		given(aliasResolver.resolve(op, aliasManager)).willReturn(op);
		given(aliasResolver.perceivedAutoCreations()).willReturn(1);
		given(aliasResolver.resolutions()).willReturn(mockAliases);

		final var expectedChanges = expNonFeeChanges(false);
		// and:
		final var expectedMeta = new ImpliedTransfersMeta(props, OK, Collections.emptyList(), mockAliases, 1);

		givenValidity(OK);

		final var result = subject.unmarshalFromGrpc(op);

		// then:
		assertEquals(expectedChanges, result.getAllBalanceChanges());
		assertEquals(result.getMeta(), expectedMeta);
		assertTrue(result.getAssessedCustomFees().isEmpty());
	}

	@Test
	void hasAliasInChanges() {
		Key aliasA  = KeyFactory.getDefaultInstance().newEd25519();
		Key aliasB  = KeyFactory.getDefaultInstance().newEd25519();
		AccountID a = AccountID.newBuilder().setShardNum(0).setRealmNum(0).setAccountNum(9_999L).setAlias(aliasA.toByteString()).build();
		AccountID validAliasAccount = AccountID.newBuilder().setAlias(aliasB.toByteString()).build();
		setupProps();

		final var builder = CryptoTransferTransactionBody.newBuilder()
				.setTransfers(TransferList.newBuilder()
						.addAccountAmounts(adjustFrom(a, -100))
						.addAccountAmounts(adjustFrom(validAliasAccount, 100))
						.build());
		op = builder.build();

		final List<BalanceChange> expectedChanges = new ArrayList<>();
		expectedChanges.add(changingHbar(adjustFrom(a, -100)));
		expectedChanges.add(changingHbar(adjustFrom(validAliasAccount, +100)));

		final var expectedMeta = new ImpliedTransfersMeta(props, OK, Collections.emptyList(), NO_ALIASES);

		givenValidity(OK);

		final var result = subject.unmarshalFromGrpc(op);

		assertEquals(expectedChanges, result.getAllBalanceChanges());
		assertEquals(aliasA.toByteString(), result.getAllBalanceChanges().get(0).alias());
		assertEquals(aliasB.toByteString(), result.getAllBalanceChanges().get(1).alias());
		assertEquals(result.getMeta(), expectedMeta);
		assertTrue(result.getAssessedCustomFees().isEmpty());
	}

	@Test
	void getsHappyPath() {
		// setup:
		setupFullFixture();
		setupProps();
		// and:
		final var nonFeeChanges = expNonFeeChanges(true);
		// and:
		final var expectedMeta = new ImpliedTransfersMeta(props, OK, mockFinalMeta, NO_ALIASES);

		givenValidity(OK);
		// and:
		given(changeManagerFactory.from(nonFeeChanges, 3)).willReturn(changeManager);
		given(customSchedulesFactory.apply(customFeeSchedules)).willReturn(schedulesManager);
		given(changeManager.nextAssessableChange()).willReturn(aTrigger).willReturn(bTrigger).willReturn(null);
		given(feeAssessor.assess(eq(aTrigger), eq(schedulesManager), eq(changeManager), anyList(), eq(props)))
				.willReturn(OK);
		given(feeAssessor.assess(eq(bTrigger), eq(schedulesManager), eq(changeManager), anyList(), eq(props)))
				.willReturn(OK);
		// and:
		given(schedulesManager.metaUsed()).willReturn(mockFinalMeta);

		// when:
		final var result = subject.unmarshalFromGrpc(op);

		// then:
		assertEquals(expectedMeta, result.getMeta());
	}

	@Test
	void getsUnhappyPath() {
		setupFullFixture();
		setupProps();
		final var nonFeeChanges = expNonFeeChanges(true);
		final var expectedMeta = new ImpliedTransfersMeta(
				props, CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH, mockFinalMeta, NO_ALIASES);

		givenValidity(OK);
		given(changeManagerFactory.from(nonFeeChanges, 3)).willReturn(changeManager);
		given(customSchedulesFactory.apply(customFeeSchedules)).willReturn(schedulesManager);
		given(changeManager.nextAssessableChange()).willReturn(aTrigger).willReturn(bTrigger).willReturn(null);
		given(feeAssessor.assess(eq(aTrigger), eq(schedulesManager), eq(changeManager), anyList(), eq(props)))
				.willReturn(CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH);
		given(schedulesManager.metaUsed()).willReturn(mockFinalMeta);

		final var result = subject.unmarshalFromGrpc(op);

		assertEquals(expectedMeta, result.getMeta());
	}

	private void givenValidity(ResponseCodeEnum s) {
		given(xferChecks.fullPureValidation(op.getTransfers(), op.getTokenTransfersList(), props))
				.willReturn(s);
	}

	private void setupProps() {
		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts);
		given(dynamicProperties.maxNftTransfersLen()).willReturn(maxExplicitOwnershipChanges);
		given(dynamicProperties.maxXferBalanceChanges()).willReturn(maxBalanceChanges);
		given(dynamicProperties.maxCustomFeeDepth()).willReturn(maxFeeNesting);
		given(dynamicProperties.areNftsEnabled()).willReturn(areNftsEnabled);
	}

	private void setupFullFixture() {
		setupFixtureOp(true);
	}

	private void setupHbarOnlyFixture() {
		setupFixtureOp(false);
	}

	private void setupFixtureOp(boolean incTokens) {
		var hbarAdjusts = TransferList.newBuilder()
				.addAccountAmounts(adjustFrom(a, -100))
				.addAccountAmounts(adjustFrom(b, 50))
				.addAccountAmounts(adjustFrom(c, 50))
				.build();
		final var builder = CryptoTransferTransactionBody.newBuilder()
				.setTransfers(hbarAdjusts);
		if (incTokens) {
			builder
					.addTokenTransfers(TokenTransferList.newBuilder()
							.setToken(anotherId)
							.addAllTransfers(List.of(
									adjustFrom(a, -50),
									adjustFrom(b, 25),
									adjustFrom(c, 25)
							)))
					.addTokenTransfers(TokenTransferList.newBuilder()
							.setToken(anId)
							.addAllTransfers(List.of(
									adjustFrom(b, -100),
									adjustFrom(c, 100)
							)))
					.addTokenTransfers(TokenTransferList.newBuilder()
							.setToken(yetAnotherId)
							.addAllNftTransfers(List.of(
									nftXfer(a, b, serialNumberA),
									nftXfer(a, b, serialNumberB)
							)));
		}
		op = builder.build();
	}

	private List<BalanceChange> expNonFeeChanges(boolean incTokens) {
		final List<BalanceChange> ans = new ArrayList<>();
		ans.add(changingHbar(adjustFrom(aModel, -100)));
		ans.add(changingHbar(adjustFrom(bModel, +50)));
		ans.add(changingHbar(adjustFrom(cModel, +50)));
		if (incTokens) {
			ans.add(tokenAdjust(aAccount, Id.fromGrpcToken(anotherId), -50));
			ans.add(tokenAdjust(bAccount, Id.fromGrpcToken(anotherId), 25));
			ans.add(tokenAdjust(cAccount, Id.fromGrpcToken(anotherId), 25));
			ans.add(tokenAdjust(bAccount, Id.fromGrpcToken(anId), -100));
			ans.add(tokenAdjust(cAccount, Id.fromGrpcToken(anId), 100));
			ans.add(changingNftOwnership(Id.fromGrpcToken(yetAnotherId), yetAnotherId, nftXfer(a, b, serialNumberA)));
			ans.add(changingNftOwnership(Id.fromGrpcToken(yetAnotherId), yetAnotherId, nftXfer(a, b, serialNumberB)));
		}
		return ans;
	}

	private final int maxExplicitHbarAdjusts = 5;
	private final int maxExplicitTokenAdjusts = 50;
	private final int maxExplicitOwnershipChanges = 12;
	private final int maxFeeNesting = 1;
	private final int maxBalanceChanges = 20;
	private final boolean areNftsEnabled = true;
	private final ImpliedTransfersMeta.ValidationProps props = new ImpliedTransfersMeta.ValidationProps(
			maxExplicitHbarAdjusts,
			maxExplicitTokenAdjusts,
			maxExplicitOwnershipChanges,
			maxFeeNesting,
			maxBalanceChanges,
			areNftsEnabled);

	private final AccountID aModel = asAccount("1.2.3");
	private final AccountID bModel = asAccount("2.3.4");
	private final AccountID cModel = asAccount("3.4.5");
	private final long serialNumberA = 12;
	private final long serialNumberB = 13;
	private final TokenID anId = asToken("0.0.75231");
	private final TokenID anotherId = asToken("0.0.75232");
	private final TokenID yetAnotherId = asToken("0.0.75233");
	private final Id aAccount = new Id(1, 2, 3);
	private final Id bAccount = new Id(2, 3, 4);
	private final Id cAccount = new Id(3, 4, 5);
	private final AccountID a = asAccount("1.2.3");
	private final AccountID b = asAccount("2.3.4");
	private final AccountID c = asAccount("3.4.5");

	private final BalanceChange aTrigger = BalanceChange.tokenAdjust(aAccount, Id.fromGrpcToken(anId), -1);
	private final BalanceChange bTrigger = BalanceChange.tokenAdjust(bAccount, Id.fromGrpcToken(anotherId), -2);
}
