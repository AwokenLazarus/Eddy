# Eddy

Eddy is an Android app that runs a real Bitcoin (XBT) node on your phone. Bitcoin is secured by BLAKE2b
proof of work, and its ticker is XBT. The node is [Bitcoin Knots](https://github.com/bitcoinknots/bitcoin)
built for Android, checking every block and every signature from the 2009 genesis block and trusting no
other node's validation. Eddy runs a fork of Knots that adds SwiftSync, a faster first sync that keeps that
guarantee ([AwokenLazarus/knots](https://github.com/AwokenLazarus/knots), branch `eddy/swiftsync-29.5`, on
Knots 29.5). [Lightning Fork](https://github.com/paulscode/lightning-fork) runs beside it. On top of those
come an Electrum server and Lightning Stream, for consuming and producing content such as podcasts.

## Status

Early. The Android app (`android/`, version 0.4.0) runs the node; Lightning Fork isn't in the app yet.
Nothing here holds real funds yet.

On an Android 14 x86_64 emulator:

- Knots and Lightning Fork 0.21.6 build for Android and run.
- `scripts/android-regtest-e2e.sh`: on regtest, two Lightning Fork nodes open a channel, pay an invoice
  and force-close, with Knots accepting the commitment transaction's unified signatures.
- Mainnet from the genesis block with `assumevalid=0`, so every signature was checked: unmodified Knots
  v29.4.3 reached the tip (height 980,813) in about 40 hours with 6 cores and 8 GB of RAM, using
  `prune=4000` and `dbcache=3000`. Its tip hash matched an independent Knots node. The data directory is
  19 GB.
- Lightning Fork on mainnet against that node synced to the same tip, using about 85 MB of RAM.

On a physical phone (8-core Snapdragon, 11 GB RAM, Android 17), in progress:

- The app runs the node from genesis on Wi-Fi at low priority. The phone stays responsive: the app's main
  thread lags under 15 ms at the 95th percentile while the node uses several cores.
- A first sync with SwiftSync from genesis, with hints and spent coins from file hosting and from a peer
  running the fork. Its UTXO cache stays in the tens of MB where an ordinary sync uses hundreds. Its speed
  against an ordinary sync on the same phone is being measured from full logs.

Not yet shown: a complete sync on a phone, and real channels on mainnet.

## The app

Eddy runs the node as its own process inside a foreground service. It aims to leave the phone usable while
it syncs:

- **Wi-Fi only for the first sync.** Off Wi-Fi, the node's networking is switched off (`setnetworkactive`).
  Once the chain is synced, mobile data is allowed too.
- **Low priority.** The node runs at nice 10 with low I/O priority, so the apps in use get the CPU first,
  and it checks signatures on every core.
- **Bounded memory and storage.** The UTXO cache is a sixth of the phone's RAM, at most 2 GB, during the
  first sync and 300 MB after it. The node is pruned to 4 GB of blocks. Everything Eddy stores counts
  against a 32 GB budget shown on screen.
- **Pauses.** It pauses when Android reports severe heat or the battery reaches 42 °C, when the battery
  drops below 15% off the charger, or below 10% during the first sync even when plugged in (it resumes at
  15%). Optional settings pause it while unplugged or while the screen is on.
- **Diagnostics.** Every minute it records the node's progress, CPU, memory and I/O, the phone's heat,
  battery, memory, network and main-thread lag, where the node's block-connecting time goes (from
  `-debug=bench`), SwiftSync downloads, and every pause, restart and crash. The records sit in
  `Android/data/com.awokenlazarus.eddy/files/diag` (readable over `adb`) with the node's own log, rotated
  and gzipped, and can be shared as one zip from the app.

## SwiftSync

A hints file says, for every block up to an end height, which of its outputs are still unspent at that
height. During the first sync the node keeps only those outputs in its UTXO set. Every other output is
added to a salted running total when it's created and subtracted when it's spent. To check a spending
transaction the node needs the coins it spends; instead of looking them up in its own database, it is
given them (each block's undo data). It checks every script, signature, amount, maturity, lock time and
sigop count against them exactly as before; nothing is skipped the way `assumevalid` skips signatures.

At the end height the total must be zero. That proves every coin it was given was really created earlier
in the chain, with that amount and script, and was spent exactly once, and that every hint was right. A
bad hints file or bad undo data can only make the sync stop with an error; it can't make the node accept
an invalid chain. A block that fails with served undo data is never marked invalid: the data is fetched
again, and after three failures the node stops. Until the end height, coins are checked against the data
given and proven only at the end height; blocks after it are checked the ordinary way.

A bad source can still waste time or stop a sync, so the node and the app limit that. Undo data that fails
a block is blamed on its source: a peer stops being asked, and file hosting's copy is replaced by a peer's.
The hints file is parsed with bounds, so a crafted one can't make the node allocate without limit. Hints
for a block that isn't on the best chain are reported instead of waited on. If a SwiftSync can't go on,
the app wipes its partial chain state and syncs the ordinary way from genesis, setting the hints aside; if
a save was interrupted mid-SwiftSync, it starts the SwiftSync again. Either way it says so on screen and in
the diagnostics.

What it saves is the UTXO database work for the coins spent before the end height, which is most of them.
That work is what made an ordinary sync slow on a phone's storage, and its cache small.

Where the data comes from, all of it untrusted:

- **File hosting.** `https://swiftsync.lightning-stream.com/v1/latest.json` names the current hints file
  (height, block hash, size, SHA-256). The hints file is at `v1/<end block hash>/hints` and each block's
  undo data at `v1/<end block hash>/undo/<block hash>` (a serialized `CBlockUndo`; 404 means the block
  spends nothing). The same undo data also comes in packs, listed in `v1/<end block hash>/packs.json`:
  `v1/<end block hash>/pack/<first>-<last>.gz` holds every block in that height range that spends
  something, about 16 MB each before gzip. Eddy downloads the hints on its first start over Wi-Fi and
  checks the SHA-256. It then streams packs four at a time, keeping up to 768 MB unzipped ahead of the
  tip, checks each against its listed SHA-256 and size, and hands it to the node
  (`loadswiftsyncundopack`), which reads each block's entry from the file as the block connects. One
  request per block had capped a phone at a few dozen blocks a second. Single files fill any gaps near
  the tip (`getswiftsyncwants`, `submitswiftsyncundo`); the node never asks for a block that spends
  nothing, so a 404 there means the file isn't up yet, and Eddy tries again two minutes later.
- **Peers running the fork.** `getswundo`/`swundo` and the batched `getswundos`/`swundos` messages, sent to
  manually added peers.

A node running the fork with full blocks and undo data can produce all of it: `generateswiftsynchints
<file>` writes a hints file for its tip, `exportswiftsyncundo <dir> <height>` writes the undo files, and
the fork's `contrib/swiftsync/make-undo-packs.py` bundles them into packs.
`-swiftsync=<file>` starts a SwiftSync, and `getswiftsyncinfo` reports its progress. The hints and undo
formats are this fork's own; they follow Ruben Somsen's SwiftSync design and the draft BIPs 455-457 but
not their wire formats.

## Build

```
export ANDROID_NDK_ROOT=/path/to/android-ndk-r26d
scripts/build-knots-android.sh aarch64 /path/to/knots           # and x86_64 for the emulator
scripts/build-lightning-fork-android.sh arm64 /path/to/lightning-fork   # or amd64
KNOTS_SRC=/path/to/knots FONT_DIR=/path/to/fonts scripts/build-android-app.sh
```

For the app, `/path/to/knots` is a checkout of the fork's `eddy/swiftsync-29.5` branch; the scripts also
build unmodified Knots. `build-android-app.sh` strips the node binaries and packs them into the APK as
native libraries, converts the fonts (Inter and JetBrains Mono, SIL Open Font License) from woff2, and
builds with Gradle. An optional `PEERS_FILE` adds bootstrap peers.

The app writes the node's configuration itself at every start. `config/bitcoin.conf` is the equivalent for
running the node by hand: it sets `assumevalid=0` and `prune=4000`, so a first sync downloads the whole
chain but stores about 16 GB. Keep `listenonion=0` until Eddy bundles Tor: without it the node aborts on
Android.

## Also here

`crates/eddy-headers` syncs and validates every block header from genesis, using our forks of
[Floresta](https://github.com/getfloresta/Floresta) and [rust-bitcoin](https://github.com/rust-bitcoin/rust-bitcoin).
It's the start of a lighter Rust node for phones short on storage, and that work is paused while the
Knots build comes first.

## Credits

Eddy is built on other people's work:

- [Bitcoin Knots](https://github.com/bitcoinknots/bitcoin), maintained by Luke Dashjr, and
  [Bitcoin Core](https://github.com/bitcoin/bitcoin), whose contributors wrote the node Eddy runs. Knots
  also carries the BLAKE2b proof-of-work rules Eddy validates.
- SwiftSync was designed by Ruben Somsen
  ([write-up](https://gist.github.com/RubenSomsen/a61a37d14182ccd78760e477c78133cd)). Eddy's fork follows
  it and the draft BIPs 455-457 ([bitcoin/bips#2152](https://github.com/bitcoin/bips/pull/2152)), and
  learned from the Bitcoin Core prototype in
  [bitcoin/bitcoin#34004](https://github.com/bitcoin/bitcoin/pull/34004).
- [Lightning Fork](https://github.com/paulscode/lightning-fork), and the
  [lnd](https://github.com/lightningnetwork/lnd) it is built on.
- [Floresta](https://github.com/getfloresta/Floresta) and
  [rust-bitcoin](https://github.com/rust-bitcoin/rust-bitcoin), behind `crates/eddy-headers`.
- The fonts [Inter](https://rsms.me/inter/) and [JetBrains Mono](https://www.jetbrains.com/lp/mono/), under
  the SIL Open Font License.

## License

MIT or Apache-2.0, at your option. Bitcoin Knots, Lightning Fork, Floresta and rust-bitcoin carry their own licenses.
