package com.hedera.services.utils;


import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class LogUtils {
	private LogUtils() {
		throw new UnsupportedOperationException("Utility Class");
	}

	// one with txnBody
	// one with txn
	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message, Transaction txn) {
		encodeGrpcAndLog(logger, logLevel, message, txn, null);
	}

	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message, Transaction txn, Exception exception) {
		var loggableGrpc = escapeBytes(txn.getSignedTransactionBytes());
		logger.log(logLevel, String.format(message, loggableGrpc), exception);
	}

	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message, Query query) {
		encodeGrpcAndLog(logger, logLevel, message, query, null);
	}

	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message, Query query, Exception exception) {
		var loggableGrpc = escapeBytes(query.toByteString());
		logger.log(logLevel, String.format(message, loggableGrpc), exception);
	}

	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message, TransactionBody txnBody) {
		encodeGrpcAndLog(logger, logLevel, message, txnBody, null);
	}

	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message, TransactionBody txnBody,
			Exception exception) {
		var loggableGrpc = escapeBytes(txnBody.toByteString());
		logger.log(logLevel, String.format(message, loggableGrpc), exception);
	}

	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message, String grpc) {
		var loggableGrpc = escapeBytes(ByteString.copyFromUtf8(grpc));
		logger.log(logLevel, String.format(message, loggableGrpc));
	}

	public static void encodeGrpcAndLog(Logger logger, Level logLevel, String message) {
		var loggableGrpc = escapeBytes(ByteString.copyFromUtf8(message));
		logger.log(logLevel, loggableGrpc);
	}

	static String escapeBytes(ByteString bs) {
		StringBuilder builder = new StringBuilder(bs.size());

		for(int i = 0; i < bs.size(); ++i) {
			byte b = bs.byteAt(i);
			switch(b) {
				case 7:
					builder.append("\\a");
					break;
				case 8:
					builder.append("\\b");
					break;
				case 9:
					builder.append("\\t");
					break;
				case 10:
					builder.append("\\n");
					break;
				case 11:
					builder.append("\\v");
					break;
				case 12:
					builder.append("\\f");
					break;
				case 13:
					builder.append("\\r");
					break;
				case 34:
					builder.append("\\\"");
					break;
				case 36:
					builder.append("\\d");
					break;
				case 39:
					builder.append("\\'");
					break;
				case 58:
					builder.append("\\g");
					break;
				case 92:
					builder.append("\\\\");
					break;
				default:
					if (b >= 32 && b <= 126) {
						builder.append((char)b);
					} else {
						builder.append('\\');
						builder.append((char)(48 + (b >>> 6 & 3)));
						builder.append((char)(48 + (b >>> 3 & 7)));
						builder.append((char)(48 + (b & 7)));
					}
			}
		}

		return builder.toString();
	}

	public static ByteString unescapeBytes(CharSequence charString) throws InvalidEscapeSequenceException {
		ByteString input = ByteString.copyFromUtf8(charString.toString());
		byte[] result = new byte[input.size()];
		int pos = 0;

		for(int i = 0; i < input.size(); ++i) {
			byte c = input.byteAt(i);
			if (c != 92) {
				result[pos++] = c;
			} else {
				if (i + 1 >= input.size()) {
					throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\' at end of string.");
				}

				++i;
				c = input.byteAt(i);
				int code;
				if (isOctal(c)) {
					code = digitValue(c);
					if (i + 1 < input.size() && isOctal(input.byteAt(i + 1))) {
						++i;
						code = code * 8 + digitValue(input.byteAt(i));
					}

					if (i + 1 < input.size() && isOctal(input.byteAt(i + 1))) {
						++i;
						code = code * 8 + digitValue(input.byteAt(i));
					}

					result[pos++] = (byte)code;
				} else {
					switch(c) {
						case 34:
							result[pos++] = 34;
							break;
						case 39:
							result[pos++] = 39;
							break;
						case 63:
							result[pos++] = 63;
							break;
						case 85:
							++i;
							if (i + 7 >= input.size()) {
								throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\U' with too few hex chars");
							}

							int codepoint = 0;

							for(int offset = i; offset < i + 8; ++offset) {
								byte b = input.byteAt(offset);
								if (!isHex(b)) {
									throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\U' with too few hex chars");
								}

								codepoint = codepoint << 4 | digitValue(b);
							}

							if (!Character.isValidCodePoint(codepoint)) {
								throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\U" + input.substring(i, i + 8).toStringUtf8() + "' is not a valid code point value");
							}

							Character.UnicodeBlock unicodeBlock = Character.UnicodeBlock.of(codepoint);
							if (unicodeBlock.equals(Character.UnicodeBlock.LOW_SURROGATES) || unicodeBlock.equals(
									Character.UnicodeBlock.HIGH_SURROGATES) || unicodeBlock.equals(Character.UnicodeBlock.HIGH_PRIVATE_USE_SURROGATES)) {
								throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\U" + input.substring(i, i + 8).toStringUtf8() + "' refers to a surrogate code unit");
							}

							int[] codepoints = new int[]{codepoint};
							byte[] chUtf8 = (new String(codepoints, 0, 1)).getBytes(StandardCharsets.UTF_8);
							System.arraycopy(chUtf8, 0, result, pos, chUtf8.length);
							pos += chUtf8.length;
							i += 7;
							break;
						case 92:
							result[pos++] = 92;
							break;
						case 97:
							result[pos++] = 7;
							break;
						case 98:
							result[pos++] = 8;
							break;
						case 100:
							result[pos++] = 36;
							break;
						case 102:
							result[pos++] = 12;
							break;
						case 103:
							result[pos++] = 58;
							break;
						case 110:
							result[pos++] = 10;
							break;
						case 114:
							result[pos++] = 13;
							break;
						case 116:
							result[pos++] = 9;
							break;
						case 117:
							++i;
							if (i + 3 >= input.size() || !isHex(input.byteAt(i)) || !isHex(input.byteAt(i + 1)) || !isHex(input.byteAt(i + 2)) || !isHex(input.byteAt(i + 3))) {
								throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\u' with too few hex chars");
							}

							char ch = (char)(digitValue(input.byteAt(i)) << 12 | digitValue(input.byteAt(i + 1)) << 8 | digitValue(input.byteAt(i + 2)) << 4 | digitValue(input.byteAt(i + 3)));
							if (Character.isSurrogate(ch)) {
								throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\u' refers to a surrogate");
							}

							byte[] utf8Ch = Character.toString(ch).getBytes(StandardCharsets.UTF_8);
							System.arraycopy(utf8Ch, 0, result, pos, utf8Ch.length);
							pos += utf8Ch.length;
							i += 3;
							break;
						case 118:
							result[pos++] = 11;
							break;
						case 120:
							int val;
							if (i + 1 < input.size() && isHex(input.byteAt(i + 1))) {
								++i;
								val = digitValue(input.byteAt(i));
								if (i + 1 < input.size() && isHex(input.byteAt(i + 1))) {
									++i;
									val = val * 16 + digitValue(input.byteAt(i));
								}

								result[pos++] = (byte)val;
								break;
							}

							throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\x' with no digits");
						default:
							throw new InvalidEscapeSequenceException("Invalid escape sequence: '\\" + (char)c + '\'');
					}
				}
			}
		}

		return ByteString.copyFrom(result, 0, pos);
	}


	private static boolean isOctal(byte c) {
		return 48 <= c && c <= 55;
	}

	private static boolean isHex(byte c) {
		return 48 <= c && c <= 57 || 97 <= c && c <= 102 || 65 <= c && c <= 70;
	}

	private static int digitValue(byte c) {
		if (48 <= c && c <= 57) {
			return c - 48;
		} else {
			return 97 <= c && c <= 122 ? c - 97 + 10 : c - 65 + 10;
		}
	}

	public static class InvalidEscapeSequenceException extends IOException {
		InvalidEscapeSequenceException(String description) {
			super(description);
		}
	}
}
