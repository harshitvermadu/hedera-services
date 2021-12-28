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

import com.hedera.services.ServicesState;
import com.hedera.services.context.annotations.CompositeProps;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.stream.proto.AllAccountBalances;
import com.hedera.services.stream.proto.SingleAccountBalances;
import com.hedera.services.stream.proto.TokenUnitBalance;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.hedera.services.utils.MiscUtils;
import com.hedera.services.utils.SystemExits;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.common.NodeId;
import com.swirlds.merkle.map.MerkleMap;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static com.hedera.services.ledger.HederaLedger.ACCOUNT_ID_COMPARATOR;
import static com.hedera.services.state.merkle.MerkleEntityAssociation.fromAccountTokenRel;
import static com.hedera.services.utils.EntityIdUtils.readableId;
import static com.hedera.services.utils.EntityNum.fromTokenId;

@Singleton
public class SignedStateBalancesExporter implements BalancesExporter {
	private static final Logger log = LogManager.getLogger(SignedStateBalancesExporter.class);

	private static final String UNKNOWN_EXPORT_DIR = "";
	private static final String BAD_EXPORT_ATTEMPT_ERROR_MSG_TPL = "Could not export to '{}'!";
	private static final String BAD_SIGNING_ATTEMPT_ERROR_MSG_TPL = "Could not sign balance file '{}'!";
	private static final String BAD_EXPORT_DIR_ERROR_MSG_TPL = "Cannot ensure existence of export dir '{}'!";
	private static final String LOW_NODE_BALANCE_WARN_MSG_TPL = "Node '{}' has unacceptably low balance {}!";
	private static final String GOOD_SIGNING_ATTEMPT_DEBUG_MSG_TPL = "Created balance signature file '{}'.";

	private static final String PROTO_FILE_EXTENSION = ".pb";

	private Instant nextExportTime = null;

	final long expectedFloat;
	private final SystemExits systemExits;
	private final UnaryOperator<byte[]> signer;
	private final GlobalDynamicProperties dynamicProperties;

	SigFileWriter sigFileWriter = new StandardSigFileWriter();
	FileHashReader hashReader = new Sha384HashReader();
	DirectoryAssurance directories = loc -> Files.createDirectories(Paths.get(loc));

	private String lastUsedExportDir = UNKNOWN_EXPORT_DIR;
	private BalancesSummary summary;

	private final int exportPeriod;

	static final Comparator<SingleAccountBalances> SINGLE_ACCOUNT_BALANCES_COMPARATOR =
			Comparator.comparing(SingleAccountBalances::getAccountID, ACCOUNT_ID_COMPARATOR);

	@Inject
	public SignedStateBalancesExporter(
			SystemExits systemExits,
			@CompositeProps PropertySource properties,
			UnaryOperator<byte[]> signer,
			GlobalDynamicProperties dynamicProperties
	) {
		this.signer = signer;
		this.systemExits = systemExits;
		this.expectedFloat = properties.getLongProperty("ledger.totalTinyBarFloat");
		this.dynamicProperties = dynamicProperties;
		exportPeriod = dynamicProperties.balancesExportPeriodSecs();
	}

	private Instant getFirstExportTime(Instant now, final int exportPeriodInSecs) {
		final long epochSeconds = now.getEpochSecond();
		long elapsedSecs = epochSeconds % exportPeriodInSecs;
		return elapsedSecs == 0 ? Instant.ofEpochSecond(now.getEpochSecond())
				: Instant.ofEpochSecond(now.plusSeconds(exportPeriodInSecs - elapsedSecs).getEpochSecond());
	}

	Instant getNextExportTime() {
		return nextExportTime;
	}

	@Override
	public boolean isTimeToExport(Instant now) {
		if (!dynamicProperties.shouldExportBalances()) {
			return false;
		}
		if (nextExportTime == null) {
			nextExportTime = getFirstExportTime(now, exportPeriod);
		}
		if (!now.isBefore(nextExportTime)) {
			nextExportTime = nextExportTime.plusSeconds(exportPeriod);
			return true;
		}
		return false;
	}

	@Override
	public void exportBalancesFrom(ServicesState signedState, Instant consensusTime, NodeId nodeId) {
		if (!ensureExportDir(signedState.getAccountFromNodeId(nodeId))) {
			return;
		}
		var watch = StopWatch.createStarted();
		summary = summarized(signedState);
		final var expected = BigInteger.valueOf(expectedFloat);
		if (expected.equals(summary.totalFloat())) {
			log.info("Took {}ms to summarize signed state balances", watch.getTime(TimeUnit.MILLISECONDS));
			toProtoFile(consensusTime);
		} else {
			log.error(
					"Signed state @ {} had total balance {} not {}; exiting",
					consensusTime, summary.totalFloat(), expectedFloat);
			systemExits.fail(1);
		}
	}

	private void toProtoFile(Instant exportTimeStamp) {
		var watch = StopWatch.createStarted();

		var builder = AllAccountBalances.newBuilder();
		summarizeAsProto(exportTimeStamp, builder);
		var protoLoc = lastUsedExportDir
				+ exportTimeStamp.toString().replace(":", "_") + "_Balances" + PROTO_FILE_EXTENSION;
		boolean exportSucceeded = exportBalancesProtoFile(builder, protoLoc);
		if (exportSucceeded) {
			tryToSign(protoLoc);
		}

		log.info(" -> Took {}ms to export and sign proto balances file at {}", watch.getTime(TimeUnit.MILLISECONDS),
				exportTimeStamp);
	}

	private void tryToSign(String fileLoc) {
		try {
			var hash = hashReader.readHash(fileLoc);
			var sig = signer.apply(hash);
			var sigFileLoc = sigFileWriter.writeSigFile(fileLoc, sig, hash);
			if (log.isDebugEnabled()) {
				log.debug(GOOD_SIGNING_ATTEMPT_DEBUG_MSG_TPL, sigFileLoc);
			}
		} catch (Exception e) {
			log.error(BAD_SIGNING_ATTEMPT_ERROR_MSG_TPL, fileLoc, e);
		}
	}

	private void summarizeAsProto(Instant exportTimeStamp, AllAccountBalances.Builder builder) {
		builder.setConsensusTimestamp(Timestamp.newBuilder()
				.setSeconds(exportTimeStamp.getEpochSecond())
				.setNanos(exportTimeStamp.getNano()));
		builder.addAllAllAccounts(summary.orderedBalances());
	}

	private boolean exportBalancesProtoFile(AllAccountBalances.Builder allAccountsBuilder, String protoLoc) {
		try (FileOutputStream fout = new FileOutputStream(protoLoc)) {
			allAccountsBuilder.build().writeTo(fout);
		} catch (IOException e) {
			log.error(BAD_EXPORT_ATTEMPT_ERROR_MSG_TPL, protoLoc, e);
			return false;
		}
		return true;
	}

	BalancesSummary summarized(ServicesState signedState) {
		long nodeBalanceWarnThreshold = dynamicProperties.nodeBalanceWarningThreshold();
		BigInteger totalFloat = BigInteger.valueOf(0L);
		List<SingleAccountBalances> accountBalances = new ArrayList<>();

		var nodeIds = MiscUtils.getNodeAccounts(signedState.addressBook());
		var tokens = signedState.tokens();
		var accounts = signedState.accounts();
		var tokenAssociations = signedState.tokenAssociations();
		for (var entry : accounts.entrySet()) {
			var id = entry.getKey();
			var account = entry.getValue();
			if (!account.isDeleted()) {
				var accountId = id.toGrpcAccountId();
				var balance = account.getBalance();
				if (nodeIds.contains(accountId) && balance < nodeBalanceWarnThreshold) {
					log.warn(LOW_NODE_BALANCE_WARN_MSG_TPL,
							readableId(accountId),
							balance);
				}
				totalFloat = totalFloat.add(BigInteger.valueOf(account.getBalance()));
				SingleAccountBalances.Builder sabBuilder = SingleAccountBalances.newBuilder();
				sabBuilder.setHbarBalance(balance).setAccountID(accountId);
				if (dynamicProperties.shouldExportTokenBalances()) {
					addTokenBalances(accountId, account, sabBuilder, tokens, tokenAssociations);
				}
				accountBalances.add(sabBuilder.build());
			}
		}
		accountBalances.sort(SINGLE_ACCOUNT_BALANCES_COMPARATOR);
		return new BalancesSummary(totalFloat, accountBalances);
	}

	private void addTokenBalances(
			AccountID id,
			MerkleAccount account,
			SingleAccountBalances.Builder sabBuilder,
			MerkleMap<EntityNum, MerkleToken> tokens,
			MerkleMap<EntityNumPair, MerkleTokenRelStatus> tokenAssociations
	) {
		var accountTokens = account.tokens();
		for (TokenID tokenId : accountTokens.asTokenIds()) {
			var token = tokens.get(fromTokenId(tokenId));
			if (token != null) {
				var relationship = tokenAssociations.get(fromAccountTokenRel(id, tokenId));
				sabBuilder.addTokenUnitBalances(tb(tokenId, relationship.getBalance()));
			}
		}
	}

	private TokenUnitBalance tb(TokenID id, long balance) {
		return TokenUnitBalance.newBuilder().setTokenId(id).setBalance(balance).build();
	}

	private boolean ensureExportDir(AccountID node) {
		var correctDir = dynamicProperties.pathToBalancesExportDir();
		if (!lastUsedExportDir.startsWith(correctDir)) {
			var sb = new StringBuilder(correctDir);
			if (!correctDir.endsWith(File.separator)) {
				sb.append(File.separator);
			}
			sb.append("balance").append(readableId(node)).append(File.separator);
			var candidateDir = sb.toString();
			try {
				directories.ensureExistenceOf(candidateDir);
				lastUsedExportDir = candidateDir;
			} catch (IOException e) {
				log.error(BAD_EXPORT_DIR_ERROR_MSG_TPL, candidateDir);
				return false;
			}
		}
		return true;
	}
}

