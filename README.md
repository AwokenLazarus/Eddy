# Eddy

Eddy is an Android app that runs its own Bitcoin (XBT) node. Bitcoin is secured by BLAKE2b proof of
work, and its ticker is XBT. The goal is a real validating node on an ordinary phone: it checks the
chain itself from the 2009 genesis block and trusts no other node's validation. On top of the node
sit an Electrum server and a Lightning node, so other Bitcoin tools can use the phone as their
backend, and Lightning Stream for consuming and producing content such as podcasts.

## Status

Early. What works today is `eddy-headers`, which syncs and validates every block header from genesis
over the P2P network, including on Android. Block validation is the next step.

```
eddy-headers <datadir> <peer ip:port>...
```

Nothing here holds real funds yet.

## Built on

- [Floresta](https://github.com/getfloresta/Floresta), a Rust Utreexo node, through our fork
- [rust-bitcoin](https://github.com/rust-bitcoin/rust-bitcoin), through our fork, which adds the
  164-byte BLAKE2b block header
- The consensus rules of [Bitcoin Knots](https://github.com/bitcoinknots/bitcoin), which these forks
  follow

## License

MIT or Apache-2.0, at your option.
