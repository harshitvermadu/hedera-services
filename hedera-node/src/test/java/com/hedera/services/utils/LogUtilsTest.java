package com.hedera.services.utils;

import com.google.protobuf.ByteString;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FileGetInfoQuery;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.MarkerManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.hedera.services.txns.crypto.AutoCreationLogic.THREE_MONTHS_IN_SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogUtilsTest {
	private static final String HGCAA_LOG_PATH = "output/hgcaa.log";

	private final String malUri = "${jndi:https://previewnet.mirrornode.hedera.com/api/v1/accounts?account.id=0.0.90}";
	private final String escapedUri = LogUtils.escapeBytes(ByteString.copyFromUtf8(malUri));
	private final String message = "We are Doomed!! %s";
	private final String stackStraceSample = "at org.apache.logging.log4j.core.net.JndiManager.lookup";
	private final Logger logger = LogManager.getLogger();


	@BeforeAll
	public static void clearHgcaaLog() throws FileNotFoundException {
		new PrintWriter(HGCAA_LOG_PATH).close();
	}

	@Test
	void ignoresJndiInTransaction() throws IOException {
		final var txnBody = buildTransactionBody();
		final var txn = Transaction.newBuilder().setSignedTransactionBytes(txnBody.toByteString()).build();

		// log4j-test has no {nolookups} tag, so if the encoding is not done for grpc, we can exploit log4J
		LogUtils.encodeGrpcAndLog(logger, Level.WARN, message, txn);

		final var actualLogWithNoJndiLookUp = readHgcaaLog();

		commonAsserts(actualLogWithNoJndiLookUp);

		// If using log4J version 2.14 or older the following test code is relevant.
		/*
		logger.log(Level.WARN, malUri);

		final var actualLogWithJndiLookUp = outContent.toString();
		assertTrue(actualLogWithJndiLookUp.contains(stackStraceSample));
		 */
	}

	@Test
	void ignoresJndiInQuery() throws IOException {
		final var query = buildQuery();

		LogUtils.encodeGrpcAndLog(logger, Level.WARN, message, query);

		final var actualLogWithNoJndiLookUp = readHgcaaLog();

		commonAsserts(actualLogWithNoJndiLookUp);
	}

	@Test
	void ignoresJndiInQuery_WithMarker() throws IOException {
		final var query = buildQuery();

		LogUtils.encodeGrpcAndLog(logger, Level.WARN, MarkerManager.getMarker("ALL_QUERIES"), message, query);

		final var actualLogWithNoJndiLookUp = readHgcaaLog();

		commonAsserts(actualLogWithNoJndiLookUp);
	}

	@Test
	void ignoresJndiInTxnBody() throws IOException {
		final var txnBody = buildTransactionBody();

		LogUtils.encodeGrpcAndLog(logger, Level.WARN, message, txnBody);

		final var actualLogWithNoJndiLookUp = readHgcaaLog();

		commonAsserts(actualLogWithNoJndiLookUp);
	}

	@Test
	void ignoresJndiInGrpcMessage() throws IOException {
		LogUtils.encodeGrpcAndLog(logger, Level.WARN, message, malUri);

		final var actualLogWithNoJndiLookUp = readHgcaaLog();

		commonAsserts(actualLogWithNoJndiLookUp);
	}

	@Test
	void ignoresJndiInUriMessage() throws IOException {
		final var logMessage = String.format(message, malUri);
		LogUtils.encodeGrpcAndLog(logger, Level.WARN, logMessage);

		final var actualLogWithNoJndiLookUp = readHgcaaLog();

		commonAsserts(actualLogWithNoJndiLookUp);
	}

	@Test
	void unescapeBytesWorks() throws LogUtils.InvalidEscapeSequenceException {
		final var malUri = "${jndi:https://previewnet.mirrornode.hedera.com/api/v1/accounts?account.id=0.0.90}";

		assertEquals(malUri, LogUtils.unescapeBytes(LogUtils.escapeBytes(ByteString.copyFromUtf8(malUri))));
	}

	private String readHgcaaLog() throws IOException {
		return Files.readString(Path.of(HGCAA_LOG_PATH), StandardCharsets.UTF_8);
	}

	private void commonAsserts(String actualLogs) {
		assertFalse(actualLogs.contains(stackStraceSample));
		assertTrue(actualLogs.contains(escapedUri),
				"actual : " + actualLogs + " expected : " + escapedUri);
	}

	private CryptoCreateTransactionBody buildCreateTransactionBody() {
		return CryptoCreateTransactionBody.newBuilder()
				.setMemo(malUri)
				.setInitialBalance(0L)
				.setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
				.build();
	}

	private TransactionBody buildTransactionBody() {
		var createTxnBody =  buildCreateTransactionBody();

		return TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder().setAccountID(IdUtils.asAccount("0.0.2")))
				.setCryptoCreateAccount(createTxnBody).build();
	}

	private Query buildQuery() {
		final var op = FileGetInfoQuery.newBuilder()
				.setFileID(IdUtils.asFile("0.0.112"))
				.setHeader(
						QueryHeader.newBuilder()
								.setPayment(Transaction.newBuilder()
										.setSignedTransactionBytes(buildTransactionBody().toByteString()))
								.setResponseType(ResponseType.ANSWER_ONLY));
		return Query.newBuilder()
				.setFileGetInfo(op)
				.build();
	}
}
