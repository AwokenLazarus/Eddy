# Eddy

Eddy is an Android app that runs a real Bitcoin (XBT) node on your phone. Bitcoin is secured by BLAKE2b
proof of work, and its ticker is XBT. The node is unmodified [Bitcoin Knots](https://github.com/bitcoinknots/bitcoin)
built for Android, checking every signature from the 2009 genesis block and trusting no other node's
validation. [Lightning Fork](https://github.com/paulscode/lightning-fork) runs beside it. On top of those
come an Electrum server and Lightning Stream, for consuming and producing content such as podcasts.

## Status

Early, and not an app yet: there's no APK, only command-line binaries run over `adb`. Nothing here holds
real funds yet.

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
