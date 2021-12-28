package com.hedera.services.yahcli.commands.accounts;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.yahcli.suites.SendSuite;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

import static com.hedera.services.bdd.spec.HapiApiSpec.SpecStatus.PASSED;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

@Command(
		name = "send",
		subcommands = { HelpCommand.class },
		description = "Transfers funds from the payer to a target account")
public class SendCommand implements Callable<Integer> {
	private static final long TINYBARS_PER_HBAR = 100_000_000L;
	private static final long TINYBARS_PER_KILOBAR = 1_000 * TINYBARS_PER_HBAR;

	@ParentCommand
	AccountsCommand accountsCommand;

	@CommandLine.Option(names = { "-d", "--denomination" },
			paramLabel = "denomination",
			description = "{ tinybar | hbar | kilobar }",
			defaultValue = "hbar")
	String denomination;

	@CommandLine.Option(
			names = { "--to" },
			paramLabel = "<beneficiary>",
			description = "account to receive the funds")
	String beneficiary;
	@CommandLine.Parameters(
			paramLabel = "<amount_to_send>",
			description = "how many units of the denomination to send")
	String amountRepr;

	@Override
	public Integer call() throws Exception {
		var config = configFrom(accountsCommand.getYahcli());

		long amount = Long.parseLong(amountRepr.replaceAll("_", ""));
		long amountInTinybars = amount;
		switch (denomination) {
			default:
				throw new CommandLine.ParameterException(
						accountsCommand.getYahcli().getSpec().commandLine(),
						"Denomination must be one of { tinybar | hbar | kilobar }");
			case "tinybar":
				break;
			case "hbar":
				amountInTinybars = amount * TINYBARS_PER_HBAR;
				break;
			case "kilobar":
				amountInTinybars = amount * TINYBARS_PER_KILOBAR;
				break;
		}
		var delegate = new SendSuite(config.asSpecConfig(), beneficiary, amountInTinybars);
		delegate.runSuiteSync();

		if (delegate.getFinalSpecs().get(0).getStatus() == PASSED) {
			COMMON_MESSAGES.info("SUCCESS - " +
					"sent " + amountRepr + " " + denomination + " to account " + beneficiary);
		} else {
			COMMON_MESSAGES.info("FAILED - " +
					"could not send " + amountRepr + " " + denomination + " to account " + beneficiary);
			return 1;
		}

		return 0;
	}
}
