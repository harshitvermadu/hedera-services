# Yahcli v0.1.8
Yahcli (_Yet Another Hedera Command Line Interface_) supports DevOps
actions against the Hedera networks listed in a _config.yml_ file.

Actions include updating system files, running validation tests,
re-keying accounts, and freezing networks for maintenance.

:warning:&nbsp;Besides the _config.yml_, yahcli requires keys and
other assets to be present in a specific directory layout. The details
appear below. 

**Table of contents**
1. [Setting up the working directory](#setting-up-the-working-directory)
2. [Understanding general usage](#general-usage)
3. [Checking account balances](#getting-account-balances)
4. [Sending funds to an account](#sending-account-funds)
5. [Updating system files](#updating-system-files)
6. [Validating network services](#validating-network-services)
7. [Preparing for an NMT upgrade](#preparing-an-nmt-software-upgrade)
8. [Launching an NMT telemetry upgrade](#launching-an-nmt-telemetry-upgrade)
9. [Scheduling a network freeze](#scheduling-a-network-freeze)
10. [Re-keying an account](#updating-account-keys)
11. [Get deployed version info of a network](#get-version-info)

# Setting up the working directory

Yahcli needs the key for a "default" payer to use for each network. 
To specify a key for account `0.0.2` on previewnet, you would create
a directory structure as below:
```
├── config.yml
├── previewnet
│   └── keys
│       ├── account2.pass
│       └── account2.pem
```

Where the _config.yml_ would have contents like:
```
defaultNetwork: previewnet

networks:
  previewnet:
    nodes:
      - { id: 0, account: 3, ipv4Addr: 35.231.208.148 }
```

We can add details for multiple networks to this config file; for example,
we can add information about the stable testnet. And we can override the 
default payer account or default node account for any network using the 
`defaultPayer` and `defaultNodeAccount` fields, respectively. 

We can also use the command line option `-p` to override `defaultPayer`, and 
the command line option `-a/--node-account` to override `defaultNodeAccount`. 

It is also possible to use the `-i/--node-ip` option to choose a target 
node by its IP adress. However, if the IP address given to the `-i` option does 
not appear in the _config.yml_, then we **must** explicitly give its node
account via the `-a` option. So with the above _config.yml_, it is enough to do,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -p 2 -n previewnet \
> -i 35.231.208.148
```

...since the ip `35.231.208.148` is in the _config.yml_. But to use an IP not
in the _config.yml_, we must also specify the node account, 
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -p 2 -n previewnet \
> -i 35.199.15.177 -a 4
```

```
defaultNetwork: previewnet

networks:
  previewnet:
    nodes:
      - { id: 0, account: 3, ipv4Addr: 35.231.208.148 }
  stabletestnet:
    defaultPayer: 50
    defaultNodeAccount: 4
    nodes:
      - { id: 0, account: 3, ipv4Addr: 34.94.106.61 }
      - { id: 1, account: 4, ipv4Addr: 35.237.119.55 }
```

For each network we add, we need a _{network}/keys/_ folder 
that contains a `account{num}.pem` for each account we will 
use with that network. :guard: &nbsp; If there is no corresponding
`account{num}.pass` for a PEM file, please be ready to enter 
the passphrase interactively in the console. For example,
```
$ docker run -it -v $(pwd):/launch yahcli:0.1.8 -p 2 sysfiles download all 
Targeting localhost, paying with 0.0.2
Please enter the passphrase for key file localhost/keys/account2.pem: 
```

:turtle: &nbsp; The docker image needs to launch a JAR, which is fairly slow. 
Please allow a few seconds for the the above command to run.

:warning:&nbsp;Without the `-it` flags above, Docker will not attach
STDIN as a TTY, and you will either not be prompted for the passphrase,
or your passphrase will appear in clear text.

Note that yahcli does not support multi-sig accounts.

# General usage

To list all available commands,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 help
``` 

:information_desk_person: &nbsp; Since the only key we have for previewnet
is for account `0.0.2`, we will need to use `-p 2` for the payer argument 
when running against this network.

To download the fee schedules from previewnet given the config above, we run,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -p 2 -n previewnet sysfiles download fees
Targeting previewnet, paying with 0.0.2
Downloading the fees...OK
$ ls previewnet/sysfiles/
feeSchedules.json
```

The fee schedules were downloaded in JSON form to _previewnet/sysfiles/feeSchedules.json_.
To see more options for the `download` subcommand (including a custom download directory), 
we run,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 sysfiles download help
```

The remaining sections of this document focus on specific use cases.

# Getting account balances
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n previewnet -p 2 accounts balance 56 50
```

# Sending account funds
You can send funds from the default payer's account to a beneficiary account, in denominations of `tinybar`, `hbar`, or `kilobar`.

The default denomination is `hbar`.

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n previewnet -p 2 accounts send --denomination hbar --to 58 1_000_000

```

# Updating system files
For this example, we will run against a `localhost` network since we will modify a system file.

Our goal in the example is to add a completely new address book entry for a node with `nodeId=3`. 
The DER-encoded RSA public key of the node is in a file _node3.der_, and its TLS X509 cert is 
in a file _node3.crt_. We place these files in the directory structure below.
```
localhost
├── keys
│   ├── account2.pass
│   ├── account2.pem
│   ├── account55.pass
│   └── account55.pem
└── sysfiles
    ├── certs
    │   └── node3.crt
    └── pubkeys
        └── node3.der
```

We first download the existing address book,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 2 sysfiles download address-book
Targeting localhost, paying with 0.0.2
Downloading the address-book...OK
```

Next we edit the newly-downloaded _localhost/sysfiles/addressBook.json_ and 
add a new entry with `nodeId=3`, as below.

:information_desk_person: &nbsp; By using the `'!'` character in the `certHash` and `rsaPubKey` fields,
we tell yahcli to compute their values from the _certs/node3.crt_ and _pubkeys/node3.der_
files, respectively.
```
...
  }, {
    "deprecatedIp" : "127.0.0.1",
    "deprecatedMemo" : "0.0.6",
    "deprecatedPortNo" : 50207,
    "nodeId" : 3,
    "certHash" : "!",
    "rsaPubKey" : "!",
    "nodeAccount" : "0.0.6",
    "endpoints" : [ {
      "ipAddressV4" : "127.0.0.1",
      "port" : 50207
    }, {
      "ipAddressV4" : "127.0.0.1",
      "port" : 50208
    } ]
...
```

And now we upload the new address book, this time using the address book admin `0.0.55` as the payer:
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 55 sysfiles upload address-book
```

Finally we re-download the book to see that the hex-encoded cert hash and RSA public key were uploaded as expected:
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 2 sysfiles download address-book
Targeting localhost, paying with 0.0.2
Downloading the address-book...OK
$ tail -17 localhost/sysfiles/addressBook.json 
  }, {
    "deprecatedIp" : "127.0.0.1",
    "deprecatedMemo" : "0.0.6",
    "deprecatedPortNo" : 50207,
    "nodeId" : 3,
    "certHash" : "0ae05bde15d216781a40e7bce5303bf68926f9440eec3cb20fabe9df06b0091a205fdea86911facb4e51e46c3890c803",
    "rsaPubKey" : "363630383939643834353735393365353739656536386133326330363033653539393666616261353038643934343830633365623634646361353837383532646133383035373032393363653533656439393266646534383533323463616235643335356537343831333439353462313963323163336630386336316131653564396533353031633433323435393765633464653864643538386666626664643461356633363436626237633539383063626432316464363430316137633931313366316365333138646361346166353737323234626465383963326331373366336665386430393265346238663830303731303761386439653236333331663533353561353834643830373736613061626361393265303034386464333731636665303539366564643662613037373033383134323838663130396138323830353836303635623762626632383534323034343761343433363838306333613933366136666666636461623130.1.83335633864666561306461306537353035383530346661396163333036396438653166643762623333343530663761346261303439310a",
    "nodeAccount" : "0.0.6",
    "endpoints" : [ {
      "ipAddressV4" : "127.0.0.1",
      "port" : 50207
    }, {
      "ipAddressV4" : "127.0.0.1",
      "port" : 50208
    } ]
  } ]
}
```
 
In some cases, yahcli does client-side validation to catch errors early. 
For example, **all three** of the `deprecated*` fields must be set to "reasonable"
values. Suppose we try to update the address book again, changing the 
`deprecatedMemo` field to something other than an account literal,
```
...
  }, {
    "deprecatedIp" : "127.0.0.1",
    "deprecatedMemo" : "This node is the best!",
    "deprecatedPortNo" : 50207,
    "nodeId" : 3,
...
```

We then get a messy error and the update aborts before sending
any `FileUpdate` transaction to the network:
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 55 sysfiles upload address-book
Targeting localhost, paying with 0.0.55
java.lang.IllegalStateException: Deprecated memo field cannot be set to 'This node is the best!'
	at com.hedera.services.bdd.suites.utils.sysfiles.serdes.AddrBkJsonToGrpcBytes.toValidatedRawFile(AddrBkJsonToGrpcBytes.java:70)
	at com.hedera.services.bdd.suites.utils.sysfiles.serdes.AddrBkJsonToGrpcBytes.toValidatedRawFile(AddrBkJsonToGrpcBytes.java:35)
	at com.hedera.services.yahcli.suites.SysFileUploadSuite.appropriateContents(SysFileUploadSuite.java:97)
	at com.hedera.services.yahcli.suites.SysFileUploadSuite.uploadSysFiles(SysFileUploadSuite.java:70)
	at com.hedera.services.yahcli.suites.SysFileUploadSuite.getSpecsInSuite(SysFileUploadSuite.java:65)
...
```

## Uploading special files

System files in the range `0.0.150-159` are _special files_ that do not have the normal 1MB size limit. 
These are used to stage ZIP artifacts for an NMT software or telemetry upgrade. By default, file `0.0.150`
is used for a software update ZIP, and file `0.0.159` for a telemetry upgrade ZIP. 

:warning:&nbsp;Only three accounts have permission to update the special files: `0.0.2`, `0.0.50`, and `0.0.58`.

To upload such artifacts, use the special files names as below,
```
$ tree localhost/sysfiles/
localhost/sysfiles/
├── softwareUpgrade.zip
└── telemetryUpgrade.zip
```

Then proceed as with any other `sysfiles upload` command, 
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 58 sysfiles upload software-zip
...
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 58 sysfiles upload telemetry-zip
...
```

# Validating network services

:building_construction:&nbsp;**TODO** the _ValidationScenarios.jar_ functionality to be migrated here.

Services will be validated by type; to see all supported options, run,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 2 validate help
```

# Preparing an NMT software upgrade

To prepare for an automatic software upgrade, there must exist a system file in the range `0.0.150-159` 
(by default, `0.0.150`) that is a ZIP archive with artifacts listed in the [NMT requirements document](https://github.com/swirlds/swirlds-docker/blob/main/docs/docker-infrastructure-design.md#toc-phase-1-feat-hedera-node-protobuf-defs-current). The expected 
SHA-384 hash of this ZIP must be given so the nodes can validate the integrity of the upgrade file before 
staging its artifacts for NMT to use. This looks like,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 58 prepare-upgrade \
> --upgrade-zip-hash 5d3b0e619d8513dfbf606ef00a2e83ba97d736f5f5ba61561d895ea83a6d4c34fce05d6cd74c83ec171f710e37e12aab
```

# Launching an NMT telemetry upgrade

To perform an automatic telemetry upgrade, there must exist a system file in the range `0.0.150-159` 
(by default, `0.0.159`) that is a ZIP archive with artifacts listed in the [NMT requirements document](https://github.com/swirlds/swirlds-docker/blob/main/docs/docker-infrastructure-design.md#toc-phase-1-feat-hedera-node-protobuf-defs-current). The expected 
SHA-384 hash of this ZIP must be known so the nodes can validate the integrity of the upgrade file before 
staging its artifacts for NMT to use.  This looks like,

```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 58 upgrade-telemetry \
> --upgrade-zip-hash 8ec75ab44b6c8ccac4a6e7f7d77b5a66280cad8d8a86ed961975a3bea597613f83af9075f65786bf9101d50047ca768f \
> --start-time 2022-01-01.00:00:00
```

# Scheduling a network freeze

Freeze start times are (consensus) UTC times formatted as `yyyy-MM-dd.HH:mm:ss`. Freezes may be
both scheduled and aborted; and a scheduled freeze may also be flagged as the trigger for an NMT
software upgrade.

A vanilla freeze with no NMT upgrade only includes the start time, 
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 58 freeze \
> --start-time 2022-01-01.00:00:00
```

While a freeze that should trigger a staged NMT upgrade uses the `freeze-upgrade` variant,
which **must** repeat the hash of the intended update, 
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 58 freeze-upgrade \
> --upgrade-zip-hash 5d3b0e619d8513dfbf606ef00a2e83ba97d736f5f5ba61561d895ea83a6d4c34fce05d6cd74c83ec171f710e37e12aab
> --start-time 2021-09-09.20:11:13 
```

To abort a scheduled freeze, simply use the `freeze-abort` command,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n localhost -p 58 freeze-abort 
```

# Updating account keys

You can use yahcli to replace an account's key with either a newly generated key, or an existing key. (Existing keys
can be either PEM files or BIP-39 mnemonics.) 

Our first example uses a randomly generated new key,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -p 2 -n localhost \
> accounts rekey --gen-new-key 57
Targeting localhost, paying with 0.0.2
.i. Exported a newly generated key in PEM format to localhost/keys/account57.pem
.i. SUCCESS - account 0.0.57 has been re-keyed
```

This leaves the existing key info under _localhost/keys_ with _.bkup_ extensions, and overwrites
_localhost/keys/account57.pem_ and _localhost/keys/account57.pass_ in-place.
```
$ tree localhost/keys
localhost/keys
├── account2.pass
├── account2.pem
├── account57.pass
├── account57.pass.bkup
├── account57.pem
└── account57.pem.bkup
```

For the next example, we specify an existing PEM file, and enter its passphrase when prompted,
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -p 57 -n localhost \
> accounts rekey -k new-account57.pem 57
Targeting localhost, paying with 0.0.2
Please enter the passphrase for key file new-account55.pem: 
.i. Exported key from new-account55 to localhost/keys/account57.pem
.i. SUCCESS - account 0.0.57 has been re-keyed
```

In our final example, we replace the `0.0.57` key from a mnemonic,
```
$ cat new-account57.words
goddess maze eternal small normal october ... author
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -p 57 -n localhost \
> accounts rekey -k new-account57.words 57
Targeting localhost, paying with 0.0.2
.i. Exported key from new-account55 to localhost/keys/account57.pem
.i. SUCCESS - account 0.0.57 has been re-keyed
```

# Get version info
```
$ docker run -it -v $(pwd):/launch gcr.io/hedera-registry/yahcli:0.1.8 -n previewnet -p 2 version
```
