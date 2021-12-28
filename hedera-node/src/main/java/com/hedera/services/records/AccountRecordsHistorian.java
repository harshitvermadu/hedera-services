package com.hedera.services.records;

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

import com.hedera.services.state.EntityCreator;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.stream.RecordStreamObject;
import com.hederahashgraph.api.proto.java.TransactionBody;

import java.time.Instant;
import java.util.List;

/**
 * Defines a type able to manage the history of transactions
 * funded by accounts on the injected ledger. (Note that these
 * transactions may not be directly <b>about</b> the ledger, but
 * instead a file or smart contract.)
 *
 * The definitive history is represented by {@link ExpirableTxnRecord}
 * instances, which expire at regular intervals and are stored in
 * the ledger accounts themselves.
 *
 * Note this type is implicitly assumed to have access to the context
 * of the active transaction, which is somewhat confusing and will be
 * addressed in a future refactor.
 */
public interface AccountRecordsHistorian {
	/**
	 * For safety, a method to notify the historian that a new transaction is beginning
	 * so any residual history can be cleared (e.g., in-progress child records).
	 */
	void clearHistory();

	/**
	 * Injects the expiring entity creator which the historian should use to create records.
	 *
	 * @param creator the creator of expiring entities.
	 */
	void setCreator(EntityCreator creator);

	/**
	 * Called immediately after committing the active transaction, to save its record(s) in
	 * the account of the effective payer account of the committed transaction.
	 */
	void saveExpirableTransactionRecords();

	/**
	 * Returns the last record created by this historian, if it is known, and null otherwise.
	 *
	 * @return the last-created record, or null if none is known
	 */
	ExpirableTxnRecord lastCreatedTopLevelRecord();

	/**
	 * Indicates if the active transaction created child records that follow the top-level transaction.
	 *
	 * @return whether following child records were created
	 */
	boolean hasFollowingChildRecords();

	/**
	 * Indicates if the active transaction created child records that precede the top-level transaction.
	 *
	 * @return whether preceding child records were created
	 */
	boolean hasPrecedingChildRecords();

	/**
	 * Returns all the child records created by the active transaction with consensus time <i>after</i>
	 * that of the top-level user transaction.
	 *
	 * @return the created following child records
	 */
	List<RecordStreamObject> getFollowingChildRecords();

	/**
	 * Returns all the child records created by the active transaction with consensus time <i>before</i>
	 * that of the top-level user transaction.
	 *
	 * @return the created preceding child records
	 */
	List<RecordStreamObject> getPrecedingChildRecords();

	/**
	 * Returns a non-negative "source id" to be used to create a group of in-progress child transactions.
	 *
	 * @return the next source id
	 */
	int nextChildRecordSourceId();

	/**
	 * Adds the given in-progress child record to the active transaction, where its synthetic consensus
	 * timestamp will come <i>after</i> that of the parent user transaction.
	 *
	 * @param sourceId the id of the child record source
	 * @param recordSoFar the in-progress child record
	 * @param syntheticBody the synthetic body for the child record
	 */
	void trackFollowingChildRecord(int sourceId, TransactionBody.Builder syntheticBody, ExpirableTxnRecord.Builder recordSoFar);

	/**
	 * Adds the given in-progress child record to the active transaction, where its synthetic consensus
	 * timestamp will come <i>before</i> that of the parent user transaction.
	 *
	 * @param sourceId the id of the child record source
	 * @param syntheticBody the synthetic body for the child record
	 * @param recordSoFar the in-progress child record
	 */
	void trackPrecedingChildRecord(int sourceId, TransactionBody.Builder syntheticBody, ExpirableTxnRecord.Builder recordSoFar);

	/**
	 * Reverts all records created by the given source.
	 *
	 * @param sourceId the id of the source whose records should be reverted
	 */
	void revertChildRecordsFromSource(int sourceId);

	/**
	 * At the moment before committing the active transaction, takes the opportunity to track
	 * any new expiring entities with the {@link com.hedera.services.state.expiry.ExpiryManager}.
	 */
	void noteNewExpirationEvents();

	/**
	 * Provides the next consensus timestamp that will be used; needed for assigning creation
	 * times to minted NFTs as part of a HTS precompiled contract.
	 *
	 * @return the consensus time that will be used for the next following child record
	 */
	Instant nextFollowingChildConsensusTime();
}
