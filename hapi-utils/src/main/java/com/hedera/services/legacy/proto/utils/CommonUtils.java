package com.hedera.services.legacy.proto.utils;

/*-
 * ‌
 * Hedera Services API Utilities
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
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionOrBuilder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Common utilities.
 */
public final class CommonUtils {
	private CommonUtils() {
		throw new UnsupportedOperationException("Utility Class");
	}

	/**
	 * Encode bytes as base64.
	 *
	 * @param bytes
	 * 		to be encoded
	 * @return base64 string
	 */
	public static String base64encode(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	/**
	 * Generates a short human readable string for grpc transaction.
	 *
	 * @param grpcTransaction
	 * 		GRPC transaction
	 * @return generated readable string
	 * @throws InvalidProtocolBufferException
	 * 		when protocol buffer is invalid
	 */
	public static String toReadableTransactionID(Transaction grpcTransaction) throws InvalidProtocolBufferException {
		TransactionBody body = extractTransactionBody(grpcTransaction);
		return "txID=" + TextFormat.shortDebugString(body.getTransactionID());
	}

	public static ByteString extractTransactionBodyByteString(TransactionOrBuilder transaction)
			throws InvalidProtocolBufferException {
		ByteString signedTransactionBytes = transaction.getSignedTransactionBytes();
		if (!signedTransactionBytes.isEmpty()) {
			return SignedTransaction.parseFrom(signedTransactionBytes).getBodyBytes();
		}

		return transaction.getBodyBytes();
	}

	public static byte[] extractTransactionBodyBytes(TransactionOrBuilder transaction)
			throws InvalidProtocolBufferException {
		return extractTransactionBodyByteString(transaction).toByteArray();
	}

	public static TransactionBody extractTransactionBody(TransactionOrBuilder transaction)
			throws InvalidProtocolBufferException {
		return TransactionBody.parseFrom(extractTransactionBodyByteString(transaction));
	}

	public static SignatureMap extractSignatureMap(TransactionOrBuilder transaction)
			throws InvalidProtocolBufferException {
		ByteString signedTransactionBytes = transaction.getSignedTransactionBytes();
		if (!signedTransactionBytes.isEmpty()) {
			return SignedTransaction.parseFrom(signedTransactionBytes).getSigMap();
		}

		return transaction.getSigMap();
	}

	public static SignatureMap extractSignatureMapOrUseDefault(TransactionOrBuilder transaction) {
		try {
			return extractSignatureMap(transaction);
		} catch (InvalidProtocolBufferException ignoreToReturnDefault) {
			return SignatureMap.getDefaultInstance();
		}
	}

	public static Transaction.Builder toTransactionBuilder(TransactionOrBuilder transactionOrBuilder) {
		if (transactionOrBuilder instanceof Transaction transaction) {
			return transaction.toBuilder();
		}

		return (Transaction.Builder) transactionOrBuilder;
	}

	public static MessageDigest getSha384Hash() throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-384");
	}

	public static byte[] noThrowSha384HashOf(byte[] byteArray) {
		try {
			return getSha384Hash().digest(byteArray);
		} catch (NoSuchAlgorithmException ignoreToReturnEmptyByteArray) {
			return new byte[0];
		}
	}

	public static ByteString sha384HashOf(byte[] byteArray) {
		return ByteString.copyFrom(noThrowSha384HashOf(byteArray));
	}

	public static ByteString sha384HashOf(Transaction transaction) {
		if (transaction.getSignedTransactionBytes().isEmpty()) {
			return sha384HashOf(transaction.toByteArray());
		}

		return sha384HashOf(transaction.getSignedTransactionBytes().toByteArray());
	}

	public static boolean productWouldOverflow(final long multiplier, final long multiplicand) {
		final var maxMultiplier = Long.MAX_VALUE / multiplicand;
		return multiplier > maxMultiplier;
	}
}
