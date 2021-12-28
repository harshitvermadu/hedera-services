package com.hedera.services.grpc;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.grpc.controllers.ConsensusController;
import com.hedera.services.grpc.controllers.ContractController;
import com.hedera.services.grpc.controllers.CryptoController;
import com.hedera.services.grpc.controllers.FileController;
import com.hedera.services.grpc.controllers.FreezeController;
import com.hedera.services.grpc.controllers.NetworkController;
import com.hedera.services.grpc.controllers.ScheduleController;
import com.hedera.services.grpc.controllers.TokenController;
import com.hedera.services.grpc.marshalling.AdjustmentUtils;
import com.hedera.services.grpc.marshalling.AliasResolver;
import com.hedera.services.grpc.marshalling.BalanceChangeManager;
import com.hedera.services.grpc.marshalling.CustomSchedulesManager;
import com.hedera.services.grpc.marshalling.FeeAssessor;
import com.hedera.services.grpc.marshalling.FixedFeeAssessor;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMarshal;
import com.hedera.services.grpc.marshalling.RoyaltyFeeAssessor;
import com.hedera.services.ledger.PureTransferSemanticChecks;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.grpc.BindableService;

import javax.inject.Singleton;
import java.util.Set;
import java.util.function.Consumer;

@Module
public abstract class GrpcModule {
	@Binds
	@Singleton
	public abstract GrpcServerManager bindGrpcServerManager(NettyGrpcServerManager nettyGrpcServerManager);

	@Provides
	@ElementsIntoSet
	public static Set<BindableService> provideBindableServices(
			CryptoController cryptoController,
			FileController fileController,
			FreezeController freezeController,
			ContractController contractController,
			ConsensusController consensusController,
			NetworkController networkController,
			TokenController tokenController,
			ScheduleController scheduleController
	) {
		return Set.of(
				cryptoController,
				fileController,
				freezeController,
				contractController,
				consensusController,
				networkController,
				tokenController,
				scheduleController);
	}

	@Provides
	@Singleton
	public static Consumer<Thread> provideHookAdder() {
		return Runtime.getRuntime()::addShutdownHook;
	}

	@Provides
	@Singleton
	public static RoyaltyFeeAssessor provideRoyaltyFeeAssessor(FixedFeeAssessor fixedFeeAssessor) {
		return new RoyaltyFeeAssessor(fixedFeeAssessor, AdjustmentUtils::adjustedChange);
	}

	@Provides
	@Singleton
	public static ImpliedTransfersMarshal provideImpliedTransfersMarshal(
			FeeAssessor feeAssessor,
			AliasManager aliasManager,
			CustomFeeSchedules customFeeSchedules,
			GlobalDynamicProperties dynamicProperties,
			PureTransferSemanticChecks transferSemanticChecks
	) {
		return new ImpliedTransfersMarshal(
				feeAssessor,
				aliasManager,
				customFeeSchedules,
				AliasResolver::new,
				dynamicProperties,
				transferSemanticChecks,
				AliasResolver::usesAliases,
				BalanceChangeManager::new,
				CustomSchedulesManager::new);
	}
}
