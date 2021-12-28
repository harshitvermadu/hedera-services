package com.hedera.services.state.merkle;

/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.common.base.MoreObjects;
import com.hedera.services.state.merkle.internals.BitPackUtils;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.utils.EntityIdUtils;
import com.hedera.services.utils.EntityNumPair;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.merkle.utility.AbstractMerkleLeaf;
import com.swirlds.common.merkle.utility.Keyed;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import static com.hedera.services.state.merkle.internals.BitPackUtils.packedTime;
import static com.hedera.services.state.merkle.internals.BitPackUtils.signedLowOrder32From;
import static com.hedera.services.state.merkle.internals.BitPackUtils.unsignedHighOrder32From;

/**
 * Represents an uniqueToken entity. Part of the nft implementation.
 */
public class MerkleUniqueToken extends AbstractMerkleLeaf implements Keyed<EntityNumPair> {
	private static final int TREASURY_OWNER_CODE = 0;

	static final int PRE_RELEASE_0180_VERSION = 1;
	static final int RELEASE_0180_VERSION = 2;

	static final int CURRENT_VERSION = RELEASE_0180_VERSION;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0x899641dafcc39164L;

	public static final int UPPER_BOUND_METADATA_BYTES = 1024;

	private int ownerCode;
	private long packedCreationTime;
	private byte[] metadata;
	private long numbers;

	/**
	 * Constructs a Merkle-usable unique token from an explicit owner id.
	 *
	 * @param owner
	 * 		the id of the entity which owns the unique token
	 * @param metadata
	 * 		metadata about the token
	 * @param creationTime
	 * 		the consensus time at which the token was created
	 */
	public MerkleUniqueToken(
			EntityId owner,
			byte[] metadata,
			RichInstant creationTime
	) {
		this.ownerCode = owner.identityCode();
		this.metadata = metadata;
		this.packedCreationTime = packedTime(creationTime.getSeconds(), creationTime.getNanos());
	}

	/**
	 * Constructs a Merkle-usable unique token (NFT) from primitive values.
	 *
	 * @param ownerCode
	 * 		the number of the owning entity as an unsigned {@code int}
	 * @param metadata
	 * 		the metadata of the unique token
	 * @param packedCreationTime
	 * 		the "packed" representation of the consensus time at which the token was minted
	 * @param numbers
	 * 		the packed representation of the token type number and serial number
	 */
	public MerkleUniqueToken(
			int ownerCode,
			byte[] metadata,
			long packedCreationTime,
			long numbers
	) {
		this.numbers = numbers;
		this.ownerCode = ownerCode;
		this.metadata = metadata;
		this.packedCreationTime = packedCreationTime;
	}

	public MerkleUniqueToken() {
		/* RuntimeConstructable */
	}

	/* Object */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || MerkleUniqueToken.class != o.getClass()) {
			return false;
		}

		var that = (MerkleUniqueToken) o;
		return this.numbers == that.numbers &&
				this.ownerCode == that.ownerCode &&
				this.packedCreationTime == that.packedCreationTime &&
				Objects.deepEquals(this.metadata, that.metadata);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				numbers,
				ownerCode,
				packedCreationTime,
				Arrays.hashCode(metadata));
	}

	@Override
	public String toString() {
		final var then = Instant.ofEpochSecond(
				unsignedHighOrder32From(packedCreationTime),
				signedLowOrder32From(packedCreationTime));
		return MoreObjects.toStringHelper(MerkleUniqueToken.class)
				.add("id", EntityIdUtils.asScopedSerialNoLiteral(numbers))
				.add("owner", EntityId.fromIdentityCode(ownerCode).toAbbrevString())
				.add("creationTime", then)
				.add("metadata", metadata)
				.toString();
	}

	/* --- MerkleLeaf --- */
	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return CURRENT_VERSION;
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		ownerCode = in.readInt();
		packedCreationTime = in.readLong();
		metadata = in.readByteArray(UPPER_BOUND_METADATA_BYTES);
		if (version >= RELEASE_0180_VERSION) {
			numbers = in.readLong();
		}
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		out.writeInt(ownerCode);
		out.writeLong(packedCreationTime);
		out.writeByteArray(metadata);
		out.writeLong(numbers);
	}

	/* --- FastCopyable --- */
	@Override
	public MerkleUniqueToken copy() {
		setImmutable(true);
		return new MerkleUniqueToken(ownerCode, metadata, packedCreationTime, numbers);
	}

	public void setOwner(EntityId owner) {
		throwIfImmutable("Cannot change this unique token's owner if it's immutable.");
		this.ownerCode = owner.identityCode();
	}

	public EntityId getOwner() {
		return new EntityId(0, 0, BitPackUtils.numFromCode(ownerCode));
	}

	public byte[] getMetadata() {
		return metadata;
	}

	public RichInstant getCreationTime() {
		return new RichInstant(unsignedHighOrder32From(packedCreationTime), signedLowOrder32From(packedCreationTime));
	}

	public void setMetadata(final byte[] metadata) {
		this.metadata = metadata;
	}

	public long getPackedCreationTime() {
		return packedCreationTime;
	}

	public void setPackedCreationTime(final long packedCreationTime) {
		this.packedCreationTime = packedCreationTime;
	}

	public boolean isTreasuryOwned() {
		return ownerCode == TREASURY_OWNER_CODE;
	}

	@Override
	public EntityNumPair getKey() {
		return new EntityNumPair(numbers);
	}

	@Override
	public void setKey(EntityNumPair phl) {
		this.numbers = phl.value();
	}
}