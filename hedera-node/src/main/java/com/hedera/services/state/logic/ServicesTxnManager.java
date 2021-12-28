package com.hedera.services.state.logic;

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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.records.RecordCache;
import com.hedera.services.state.annotations.RunRecordStreaming;
import com.hedera.services.state.annotations.RunTopLevelTransition;
import com.hedera.services.state.annotations.RunTriggeredTransition;
import com.hedera.services.utils.TxnAccessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;

@Singleton
public class ServicesTxnManager {
	private static final Logger log = LogManager.getLogger(ServicesTxnManager.class);

	private static final String ERROR_LOG_TPL = "Possibly CATASTROPHIC failure in {} :: {} ==>> {} ==>>";

	private final Runnable scopedProcessing;
	private final Runnable scopedRecordStreaming;
	private final Runnable scopedTriggeredProcessing;
	private final RecordCache recordCache;
	private final HederaLedger ledger;
	private final TransactionContext txnCtx;
	private final AccountRecordsHistorian recordsHistorian;

	@Inject
	public ServicesTxnManager(
			final @RunTopLevelTransition Runnable scopedProcessing,
			final @RunRecordStreaming Runnable scopedRecordStreaming,
			final @RunTriggeredTransition Runnable scopedTriggeredProcessing,
			final RecordCache recordCache,
			final HederaLedger ledger,
			final TransactionContext txnCtx,
			final AccountRecordsHistorian recordsHistorian
	) {
		this.txnCtx = txnCtx;
		this.ledger = ledger;
		this.recordCache = recordCache;
		this.recordsHistorian = recordsHistorian;
		this.scopedProcessing = scopedProcessing;
		this.scopedRecordStreaming = scopedRecordStreaming;
		this.scopedTriggeredProcessing = scopedTriggeredProcessing;
	}

	private boolean createdStreamableRecord;

	public void process(TxnAccessor accessor, Instant consensusTime, long submittingMember) {
		var processFailed = false;
		createdStreamableRecord = false;

		try {
			txnCtx.resetFor(accessor, consensusTime, submittingMember);
			recordsHistorian.clearHistory();
			ledger.begin();

			if (accessor.isTriggeredTxn()) {
				scopedTriggeredProcessing.run();
			} else {
				scopedProcessing.run();
			}
		} catch (Exception processFailure) {
			processFailed = true;
			logContextualizedError(processFailure, "txn processing");
			txnCtx.setStatus(FAIL_INVALID);
		}

		if (processFailed) {
			attemptRollback(accessor, consensusTime, submittingMember);
		} else {
			attemptCommit(accessor, consensusTime, submittingMember);
			if (createdStreamableRecord) {
				attemptRecordStreaming();
			}
		}
	}

	private void attemptRecordStreaming() {
		try {
			scopedRecordStreaming.run();
		} catch (Exception e) {
			logContextualizedError(e, "record streaming");
		}
	}

	private void attemptCommit(TxnAccessor accessor, Instant consensusTime, long submittingMember) {
		try {
			ledger.commit();
			createdStreamableRecord = true;
		} catch (Exception e) {
			logContextualizedError(e, "txn commit");
			attemptRollback(accessor, consensusTime, submittingMember);
		}
	}

	private void attemptRollback(TxnAccessor accessor, Instant consensusTime, long submittingMember) {
		try {
			recordCache.setFailInvalid(
					txnCtx.effectivePayer(),
					accessor,
					consensusTime,
					submittingMember);
		} catch (Exception e) {
			logContextualizedError(e, "failure record creation");
		}
		try {
			ledger.rollback();
		} catch (Exception e) {
			logContextualizedError(e, "txn rollback");
		}
	}

	private void logContextualizedError(Exception e, String context) {
		try {
			final var accessor = txnCtx.accessor();
			log.error(ERROR_LOG_TPL, context, accessor.getSignedTxnWrapper(), ledger.currentChangeSet(), e);
		} catch (Exception f) {
			log.error("Possibly CATASTROPHIC failure in {}", context, e);
			log.error("Full details could not be logged", f);
		}
	}
}