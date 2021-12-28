package com.hedera.services.sigs;

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

import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.annotations.HandleSigReqs;
import com.hedera.services.sigs.factories.ReusableBodySigningFactory;
import com.hedera.services.sigs.order.CodeOrderResultFactory;
import com.hedera.services.sigs.order.SigRequirements;
import com.hedera.services.sigs.order.SigningOrderResult;
import com.hedera.services.sigs.sourcing.PubKeyToSigBytes;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.utils.RationalizedSigMeta;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.swirlds.common.crypto.TransactionSignature;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static com.hedera.services.sigs.PlatformSigOps.createCryptoSigsFrom;
import static com.hedera.services.sigs.factories.PlatformSigFactory.allVaryingMaterialEquals;
import static com.hedera.services.sigs.order.CodeOrderResultFactory.CODE_ORDER_RESULT_FACTORY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

@Singleton
public class Rationalization {
	private final SyncVerifier syncVerifier;
	private final SigRequirements keyOrderer;
	private final ReusableBodySigningFactory bodySigningFactory;

	private TxnAccessor txnAccessor;
	private PubKeyToSigBytes pkToSigFn;

	private JKey reqPayerSig;
	private boolean verifiedSync;
	private List<JKey> reqOthersSigs;
	private ResponseCodeEnum finalStatus;
	private List<TransactionSignature> txnSigs;
	private SigningOrderResult<ResponseCodeEnum> lastOrderResult;
	private List<TransactionSignature> realPayerSigs = new ArrayList<>();
	private List<TransactionSignature> realOtherPartySigs = new ArrayList<>();

	@Inject
	public Rationalization(
			SyncVerifier syncVerifier,
			@HandleSigReqs SigRequirements keyOrderer,
			ReusableBodySigningFactory bodySigningFactory
	) {
		this.keyOrderer = keyOrderer;
		this.syncVerifier = syncVerifier;
		this.bodySigningFactory = bodySigningFactory;
	}

	public void performFor(TxnAccessor txnAccessor) {
		resetFor(txnAccessor);
		execute();
	}

	public ResponseCodeEnum finalStatus() {
		return finalStatus;
	}

	public boolean usedSyncVerification() {
		return verifiedSync;
	}

	void resetFor(TxnAccessor txnAccessor) {
		this.pkToSigFn = txnAccessor.getPkToSigsFn();
		this.txnAccessor = txnAccessor;

		pkToSigFn.resetAllSigsToUnused();
		bodySigningFactory.resetFor(txnAccessor);

		txnSigs = txnAccessor.getPlatformTxn().getSignatures();
		realPayerSigs.clear();
		realOtherPartySigs.clear();

		finalStatus = null;
		verifiedSync = false;

		reqPayerSig = null;
		reqOthersSigs = null;
		lastOrderResult = null;
	}

	private void execute() {
		ResponseCodeEnum otherFailure = null;

		final var payerStatus = expandIn(realPayerSigs, keyOrderer::keysForPayer);
		if (payerStatus != OK) {
			txnAccessor.setSigMeta(RationalizedSigMeta.noneAvailable());
			finalStatus = payerStatus;
			return;
		}
		reqPayerSig = lastOrderResult.getPayerKey();

		final var otherPartiesStatus = expandIn(realOtherPartySigs, keyOrderer::keysForOtherParties);
		if (otherPartiesStatus != OK) {
			otherFailure = otherPartiesStatus;
		} else {
			reqOthersSigs = lastOrderResult.getOrderedKeys();
			if (pkToSigFn.hasAtLeastOneUnusedSigWithFullPrefix()) {
				pkToSigFn.forEachUnusedSigWithFullPrefix((type, pubKey, sig) ->
						realOtherPartySigs.add(bodySigningFactory.signAppropriately(type, pubKey, sig)));
			}
		}

		final var rationalizedPayerSigs = rationalize(realPayerSigs, 0);
		final var rationalizedOtherPartySigs = rationalize(realOtherPartySigs, realPayerSigs.size());
		if (rationalizedPayerSigs == realPayerSigs || rationalizedOtherPartySigs == realOtherPartySigs) {
			txnSigs = new ArrayList<>();
			txnSigs.addAll(rationalizedPayerSigs);
			txnSigs.addAll(rationalizedOtherPartySigs);
			verifiedSync = true;
		}

		makeRationalizedMetaAccessible();

		finalStatus = (otherFailure != null) ? otherFailure : OK;
	}

	private void makeRationalizedMetaAccessible() {
		if (reqOthersSigs == null) {
			txnAccessor.setSigMeta(RationalizedSigMeta.forPayerOnly(reqPayerSig, txnSigs));
		} else {
			txnAccessor.setSigMeta(RationalizedSigMeta.forPayerAndOthers(reqPayerSig, reqOthersSigs, txnSigs));
		}
	}

	private List<TransactionSignature> rationalize(List<TransactionSignature> realSigs, int startingAt) {
		final var maxSubListEnd = txnSigs.size();
		final var requestedSubListEnd = startingAt + realSigs.size();
		if (requestedSubListEnd <= maxSubListEnd) {
			var candidateSigs = txnSigs.subList(startingAt, startingAt + realSigs.size());
			/* If all the key material is unchanged from expandSignatures(), we are done */
			if (allVaryingMaterialEquals(candidateSigs, realSigs)) {
				return candidateSigs;
			}
		}
		/* Otherwise we must synchronously verify these signatures for the rationalized keys */
		syncVerifier.verifySync(realSigs);
		return realSigs;
	}

	private ResponseCodeEnum expandIn(
			List<TransactionSignature> target,
			BiFunction<TransactionBody, CodeOrderResultFactory, SigningOrderResult<ResponseCodeEnum>> keysFn
	) {
		lastOrderResult = keysFn.apply(txnAccessor.getTxn(), CODE_ORDER_RESULT_FACTORY);
		if (lastOrderResult.hasErrorReport()) {
			return lastOrderResult.getErrorReport();
		}
		final var creation =
				createCryptoSigsFrom(lastOrderResult.getOrderedKeys(), pkToSigFn, bodySigningFactory);
		if (creation.hasFailed()) {
			return creation.asCode();
		}
		target.addAll(creation.getPlatformSigs());
		return OK;
	}

	/* --- Only used by unit tests --- */
	TxnAccessor getTxnAccessor() {
		return txnAccessor;
	}

	SyncVerifier getSyncVerifier() {
		return syncVerifier;
	}

	PubKeyToSigBytes getPkToSigFn() {
		return pkToSigFn;
	}

	SigRequirements getKeyOrderer() {
		return keyOrderer;
	}

	List<TransactionSignature> getRealPayerSigs() {
		return realPayerSigs;
	}

	List<TransactionSignature> getRealOtherPartySigs() {
		return realOtherPartySigs;
	}

	List<TransactionSignature> getTxnSigs() {
		return txnSigs;
	}

	void setReqOthersSigs(List<JKey> reqOthersSigs) {
		this.reqOthersSigs = reqOthersSigs;
	}

	List<JKey> getReqOthersSigs() {
		return reqOthersSigs;
	}

	void setLastOrderResult(SigningOrderResult<ResponseCodeEnum> lastOrderResult) {
		this.lastOrderResult = lastOrderResult;
	}

	SigningOrderResult<ResponseCodeEnum> getLastOrderResult() {
		return lastOrderResult;
	}

	void setReqPayerSig(JKey reqPayerSig) {
		this.reqPayerSig = reqPayerSig;
	}

	JKey getReqPayerSig() {
		return reqPayerSig;
	}

	void setFinalStatus(ResponseCodeEnum finalStatus) {
		this.finalStatus = finalStatus;
	}

	void setVerifiedSync(boolean verifiedSync) {
		this.verifiedSync = verifiedSync;
	}
}
