package com.hedera.services.state.migration;

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

/**
 * Gives the versions of the current and previous world states.
 */
public final class StateVersions {
	/* For the record,
	     - Release 0.7.0 was state version 1
	     - Release 0.8.0 was state version 2
	     - Release 0.9.0 was state version 3
	     - Release 0.10.0 was state version 4
	     - Release 0.11.0 was state version 5
	     - Release 0.12.0 was state version 6
	     - Release 0.13.0 was state version 7
	     - Release 0.14.0 was state version 8
	     - Release 0.15.0 was state version 9 */

	public static final int RELEASE_0160_VERSION = 10;
	public static final int RELEASE_0170_VERSION = 11;
	public static final int RELEASE_0180_VERSION = 12;
	public static final int RELEASE_0190_AND_020_VERSION = 13;
	public static final int RELEASE_0210_VERSION = 14;
	public static final int RELEASE_0220_VERSION = 15;

	public static final int MINIMUM_SUPPORTED_VERSION = RELEASE_0190_AND_020_VERSION;
	public static final int CURRENT_VERSION = RELEASE_0220_VERSION;

	private StateVersions() {
		throw new UnsupportedOperationException("Utility Class");
	}
}
