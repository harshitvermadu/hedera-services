package com.hedera.services.store.contracts.precompile;

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
import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.contracts.sources.TxnAwareSoliditySigsVerifier;
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMarshal;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.legacy.core.jproto.TxnReceipt;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.state.expiry.ExpiringCreations;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.state.submerkle.TxnId;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.contracts.AbstractLedgerWorldUpdater;
import com.hedera.services.store.contracts.WorldLedgers;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.NftId;
import com.hedera.services.txns.token.MintLogic;
import com.hedera.services.txns.token.process.DissociationFactory;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.hedera.services.state.expiry.ExpiringCreations.EMPTY_MEMO;
import static com.hedera.services.store.contracts.precompile.HTSPrecompiledContract.NOOP_TREASURY_ADDER;
import static com.hedera.services.store.contracts.precompile.HTSPrecompiledContract.NOOP_TREASURY_REMOVER;
import static com.hedera.services.store.tokens.views.UniqueTokenViewsManager.NOOP_VIEWS_MANAGER;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.TxnUtils.assertFailsWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("rawtypes")
class MintPrecompilesTest {
	private static final Bytes pretendArguments = Bytes.fromBase64String("ABCDEF");

	@Mock
	private AccountStore accountStore;
	@Mock
	private TypedTokenStore tokenStore;
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private OptionValidator validator;
	@Mock
	private GasCalculator gasCalculator;
	@Mock
	private MessageFrame frame;
	@Mock
	private TxnAwareSoliditySigsVerifier sigsVerifier;
	@Mock
	private AccountRecordsHistorian recordsHistorian;
	@Mock
	private DecodingFacade decoder;
	@Mock
	private EncodingFacade encoder;
	@Mock
	private HTSPrecompiledContract.MintLogicFactory mintLogicFactory;
	@Mock
	private HTSPrecompiledContract.TokenStoreFactory tokenStoreFactory;
	@Mock
	private HTSPrecompiledContract.AccountStoreFactory accountStoreFactory;
	@Mock
	private MintLogic mintLogic;
	@Mock
	private SideEffectsTracker sideEffects;
	@Mock
	private TransactionBody.Builder mockSynthBodyBuilder;
	@Mock
	private ExpirableTxnRecord.Builder mockRecordBuilder;
	@Mock
	private SyntheticTxnFactory syntheticTxnFactory;
	@Mock
	private AbstractLedgerWorldUpdater worldUpdater;
	@Mock
	private WorldLedgers wrappedLedgers;
	@Mock
	private TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nfts;
	@Mock
	private TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRels;
	@Mock
	private TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accounts;
	@Mock
	private TransactionalLedger<TokenID, TokenProperty, MerkleToken> tokens;
	@Mock
	private ExpiringCreations creator;
	@Mock
	private ImpliedTransfersMarshal impliedTransfers;
	@Mock
	private DissociationFactory dissociationFactory;

	private HTSPrecompiledContract subject;

	@BeforeEach
	void setUp() {
		subject = new HTSPrecompiledContract(
				validator, dynamicProperties, gasCalculator,
				recordsHistorian, sigsVerifier, decoder, encoder,
				syntheticTxnFactory, creator, dissociationFactory, impliedTransfers);
		subject.setMintLogicFactory(mintLogicFactory);
		subject.setTokenStoreFactory(tokenStoreFactory);
		subject.setAccountStoreFactory(accountStoreFactory);
		subject.setSideEffectsFactory(() -> sideEffects);
	}

	@Test
	void mintFailurePathWorks() {
		givenNonFungibleFrameContext();

		given(sigsVerifier.hasActiveSupplyKey(nonFungibleId, recipientAddr, contractAddr))
				.willThrow(new InvalidTransactionException(INVALID_SIGNATURE));
		given(creator.createUnsuccessfulSyntheticRecord(INVALID_SIGNATURE)).willReturn(mockRecordBuilder);

		// when:
		final var result = subject.computeMintToken(pretendArguments, frame);

		// then:
		assertEquals(invalidSigResult, result);

		verify(worldUpdater).manageInProgressRecord(recordsHistorian, mockRecordBuilder, mockSynthBodyBuilder);
	}

	@Test
	void mintRandomFailurePathWorks() {
		givenNonFungibleFrameContext();

		given(sigsVerifier.hasActiveSupplyKey(any(), any(), any())).willThrow(new IllegalArgumentException("random error"));

		// when:
		final var result = subject.computeMintToken(pretendArguments, frame);

		// then:
		assertEquals(failInvalidResult, result);
	}

	@Test
	void nftMintHappyPathWorks() {
		givenNonFungibleFrameContext();
		givenLedgers();

		given(sigsVerifier.hasActiveSupplyKey(nonFungibleId, recipientAddr, contractAddr)).willReturn(true);
		given(accountStoreFactory.newAccountStore(
				validator, dynamicProperties, accounts
		)).willReturn(accountStore);
		given(tokenStoreFactory.newTokenStore(
				accountStore, tokens, nfts, tokenRels, NOOP_VIEWS_MANAGER, NOOP_TREASURY_ADDER, NOOP_TREASURY_REMOVER, sideEffects
		)).willReturn(tokenStore);
		given(mintLogicFactory.newMintLogic(validator, tokenStore, accountStore)).willReturn(mintLogic);
		given(creator.createSuccessfulSyntheticRecord(Collections.emptyList(), sideEffects, EMPTY_MEMO))
				.willReturn(mockRecordBuilder);
		given(recordsHistorian.nextFollowingChildConsensusTime()).willReturn(pendingChildConsTime);

		// when:
		final var result = subject.computeMintToken(pretendArguments, frame);

		// then:
		assertEquals(successResult, result);
		// and:
		verify(mintLogic).mint(nonFungibleId, 3, 0, newMetadata, pendingChildConsTime);
		verify(wrappedLedgers).commit();
		verify(worldUpdater).manageInProgressRecord(recordsHistorian, mockRecordBuilder, mockSynthBodyBuilder);
	}

	@Test
	void fungibleMintHappyPathWorks() {
		givenFungibleFrameContext();
		givenLedgers();
		givenFungibleCollaborators();
		given(encoder.getMintSuccessfulResultFromReceipt(10, new long[0])).willReturn(fungibleSuccessResultWith10Supply);

		// when:
		final var result = subject.computeMintToken(pretendArguments, frame);
		// then:
		assertEquals(fungibleSuccessResultWith10Supply, result);
		// and:
		verify(mintLogic).mint(fungibleId, 0, amount, Collections.emptyList(), Instant.EPOCH);
		verify(wrappedLedgers).commit();
		verify(worldUpdater).manageInProgressRecord(recordsHistorian, expirableTxnRecordBuilder, mockSynthBodyBuilder);
	}

	@Test
	void mintFailsWithMissingParentUpdater() {
		givenFungibleFrameContext();
		givenLedgers();
		givenFungibleCollaborators();
		given(worldUpdater.parentUpdater()).willReturn(Optional.empty());

		assertFailsWith(() -> subject.computeMintToken(pretendArguments, frame), FAIL_INVALID);
	}

	private void givenNonFungibleFrameContext() {
		givenFrameContext();
		given(decoder.decodeMint(pretendArguments)).willReturn(nftMint);
		given(syntheticTxnFactory.createMint(nftMint)).willReturn(mockSynthBodyBuilder);
	}

	private void givenFungibleFrameContext() {
		givenFrameContext();
		given(decoder.decodeMint(pretendArguments)).willReturn(fungibleMint);
		given(syntheticTxnFactory.createMint(fungibleMint)).willReturn(mockSynthBodyBuilder);
	}

	private void givenFrameContext() {
		given(frame.getContractAddress()).willReturn(contractAddr);
		given(frame.getRecipientAddress()).willReturn(recipientAddr);
		given(frame.getWorldUpdater()).willReturn(worldUpdater);
		Optional<WorldUpdater> parent = Optional.of(worldUpdater);
		given(worldUpdater.parentUpdater()).willReturn(parent);
		given(worldUpdater.wrappedTrackingLedgers()).willReturn(wrappedLedgers);
	}

	private void givenLedgers() {
		given(wrappedLedgers.accounts()).willReturn(accounts);
		given(wrappedLedgers.tokenRels()).willReturn(tokenRels);
		given(wrappedLedgers.nfts()).willReturn(nfts);
		given(wrappedLedgers.tokens()).willReturn(tokens);
	}

	private void givenFungibleCollaborators() {
		given(sigsVerifier.hasActiveSupplyKey(fungibleId, recipientAddr, contractAddr)).willReturn(true);
		given(accountStoreFactory.newAccountStore(
				validator, dynamicProperties, accounts
		)).willReturn(accountStore);
		given(tokenStoreFactory.newTokenStore(
				accountStore, tokens, nfts, tokenRels, NOOP_VIEWS_MANAGER, NOOP_TREASURY_ADDER, NOOP_TREASURY_REMOVER, sideEffects
		)).willReturn(tokenStore);
		given(mintLogicFactory.newMintLogic(validator, tokenStore, accountStore)).willReturn(mintLogic);
		given(creator.createSuccessfulSyntheticRecord(Collections.emptyList(), sideEffects, EMPTY_MEMO))
				.willReturn(expirableTxnRecordBuilder);
	}

	private static final long amount = 1_234_567;
	private static final TokenID nonFungible = IdUtils.asToken("0.0.777");
	private static final TokenID fungible = IdUtils.asToken("0.0.888");
	private static final Id nonFungibleId = Id.fromGrpcToken(nonFungible);
	private static final Id fungibleId = Id.fromGrpcToken(fungible);
	private static final List<ByteString> newMetadata = List.of(
			ByteString.copyFromUtf8("AAA"), ByteString.copyFromUtf8("BBB"), ByteString.copyFromUtf8("CCC"));
	private static final MintWrapper nftMint =
			MintWrapper.forNonFungible(nonFungible, newMetadata);
	private static final MintWrapper fungibleMint =
			MintWrapper.forFungible(fungible, amount);
	private static final Address recipientAddr = Address.ALTBN128_ADD;
	private static final Address contractAddr = Address.ALTBN128_MUL;
	private static final Bytes successResult = UInt256.valueOf(ResponseCodeEnum.SUCCESS_VALUE);
	private static final Bytes fungibleSuccessResultWith10Supply = Bytes.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000016000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000600000000000000000000000000000000000000000000000000000000000000000");
	private static final Bytes invalidSigResult = UInt256.valueOf(ResponseCodeEnum.INVALID_SIGNATURE_VALUE);
	private static final Bytes failInvalidResult = UInt256.valueOf(ResponseCodeEnum.FAIL_INVALID_VALUE);
	private static final Instant pendingChildConsTime = Instant.ofEpochSecond(1_234_567L, 890);
	private static final TxnReceipt.Builder receiptBuilder =
			TxnReceipt.newBuilder().setNewTotalSupply(10).setSerialNumbers(new long[0]).setStatus(ResponseCodeEnum.SUCCESS.name());
	private static final ExpirableTxnRecord.Builder expirableTxnRecordBuilder = ExpirableTxnRecord.newBuilder()
			.setReceiptBuilder(receiptBuilder);
}
