# Eddy

Eddy is an Android app that runs a real Bitcoin (XBT) node on your phone. Bitcoin is secured by BLAKE2b
proof of work, and its ticker is XBT. The node is unmodified [Bitcoin Knots](https://github.com/bitcoinknots/bitcoin)
built for Android, checking every signature from the 2009 genesis block and trusting no other node's
validation. [Lightning Fork](https://github.com/paulscode/lightning-fork) runs beside it. On top of those
come an Electrum server and Lightning Stream, for consuming and producing content such as podcasts.

## Status

Early. The Android app (`android/`, version 0.2.0) runs the node; Lightning Fork isn't in the app yet. Nothing
here holds real funds yet.

Works on an Android 14 x86_64 emulator:

- Knots v29.4.3 and Lightning Fork 0.21.6 build for Android and run
- `scripts/android-regtest-e2e.sh`: on regtest, two Lightning Fork nodes open a channel, pay an invoice
  and force-close, with Knots accepting the commitment transaction's unified signatures

- Mainnet from the 2009 genesis block with `assumevalid=0`, so every signature was checked: Knots reached
  the tip (height 980,813) in about 40 hours on an emulator with 6 cores and 8 GB of RAM, using
  `prune=4000` and `dbcache=3000`. Its tip hash matched an independent Knots node. The data directory is
  19 GB.
- Lightning Fork on mainnet against that Knots node: synced to the chain at the same tip, using about
  85 MB of RAM

Not yet shown: either on a physical phone, and real channels on mainnet.

## The app

Eddy runs Bitcoin Knots, unmodified, as its own process inside a foreground service, and validates the chain
from the genesis block with `assumevalid=0`. It aims to leave the phone usable while it does:

- **Wi-Fi only for the first sync.** Off Wi-Fi, the node's networking is switched off (`setnetworkactive`).
  Once the chain is synced, mobile data is allowed too.
- **Low priority.** The node runs at nice 10 with low I/O priority, so the apps in use get the CPU first,
  and it checks signatures on every core.
- **Bounded memory.** The UTXO cache is a sixth of the phone's RAM, at most 2 GB, during the first sync and
  300 MB after it. The node is pruned to 4 GB of blocks.
- **Pauses.** It pauses when Android reports severe heat or the battery reaches 42 °C, or the battery drops
  below 15% off the charger. Optional settings pause it while unplugged or while the screen is on.
- **Diagnostics.** Every minute it records the node's progress, CPU, memory and I/O, the phone's heat,
  battery, memory, network and main-thread lag, where the node's block-connecting time goes (from
  `-debug=bench`), and every pause, restart and crash. The records sit in
  `Android/data/com.awokenlazarus.eddy/files/diag` (readable over `adb`) with the node's own log, rotated
  and gzipped, and can be shared as one zip from the app.

- **SwiftSync.** Eddy 0.2 runs a fork of Knots that adds SwiftSync
  ([AwokenLazarus/knots](https://github.com/AwokenLazarus/knots), branch `eddy/swiftsync`). With a hints file
  in its files folder, the first sync skips the UTXO database for coins spent before the hints' end height,
  which is most of them. It still checks every block and signature, using the spent coins a peer serves, and
  a running total proves at the end height that those coins were real. A bad hints file or bad served data
  stops the sync with an error; it can't make the node accept an invalid chain.

Build it with `scripts/build-android-app.sh` after building Knots for `aarch64` (and `x86_64` for the
emulator). The fonts are Inter and JetBrains Mono (SIL Open Font License), converted from woff2 at build
time. An optional `PEERS_FILE` adds bootstrap peers.

## Build

```
export ANDROID_NDK_ROOT=/path/to/android-ndk-r26d
scripts/build-knots-android.sh x86_64 /path/to/knots            # or aarch64
scripts/build-lightning-fork-android.sh amd64 /path/to/lightning-fork   # or arm64
```

`config/bitcoin.conf` is the node's configuration. It sets `assumevalid=0` and `prune=4000`, so a full
first sync downloads the whole chain but stores about 16 GB. Keep `listenonion=0` until Eddy bundles Tor:
without it the node aborts on Android.

## Also here

`crates/eddy-headers` syncs and validates every block header from genesis, using our forks of
[Floresta](https://github.com/getfloresta/Floresta) and [rust-bitcoin](https://github.com/rust-bitcoin/rust-bitcoin).
It's the start of a lighter Rust node for phones short on storage, and that work is paused while the
Knots build comes first.

## License

MIT or Apache-2.0, at your option. Bitcoin Knots, Lightning Fork, Floresta and rust-bitcoin carry their own licenses.
