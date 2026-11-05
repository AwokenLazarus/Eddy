//! Syncs block headers from genesis over the Bitcoin P2P protocol and validates each one with the
//! BLAKE2b fork rules. It trusts no peer: a bad header ends that peer's session, and the chain is
//! kept on disk, so a run resumes where the last one stopped.
//!
//! Usage: eddy-headers <datadir> <peer ip:port>...

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::Path;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use bitcoin::consensus::encode::{deserialize, serialize};
use bitcoin::hashes::Hash;
use bitcoin::p2p::message::{NetworkMessage, RawNetworkMessage};
use bitcoin::p2p::message_blockdata::GetHeadersMessage;
use bitcoin::p2p::message_network::VersionMessage;
use bitcoin::p2p::{Address, Magic, ServiceFlags};
use bitcoin::{BlockHash, Network};
use floresta_chain::pruned_utreexo::UpdatableChainstate;
use floresta_chain::{
    AssumeValidArg, BlockchainInterface, ChainState, FlatChainStore, FlatChainStoreConfig,
};

const PROTOCOL_VERSION: u32 = 70016;
const MAX_HEADERS_PER_MESSAGE: usize = 2000;
const MAX_MESSAGE_BYTES: usize = 32 * 1024 * 1024;
/// `NODE_BLAKE2B` in Bitcoin Knots: this node enforces the fork rules.
const NODE_BLAKE2B: u64 = 1 << 28;

type Chain = ChainState<FlatChainStore>;
type Failure = Box<dyn std::error::Error>;

fn main() -> Result<(), Failure> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (datadir, peers) = args.split_first().ok_or("usage: eddy-headers <datadir> <peer>...")?;
    if peers.is_empty() {
        return Err("name at least one peer".into());
    }

    let store = FlatChainStore::new(FlatChainStoreConfig::new(Path::new(datadir).join("chain")))?;
    let chain = ChainState::open(store, Network::Bitcoin, AssumeValidArg::Disabled)?;
    let start = Instant::now();
    let (first_height, _) = chain.get_best_block()?;
    println!("resuming at height {first_height}");

    for peer in peers {
        match sync_from(&chain, peer, first_height, start) {
            Ok(()) => {
                chain.flush()?;
                let (height, hash) = chain.get_best_block()?;
                println!("synced peer={peer} height={height} hash={hash}");
                println!("elapsed_secs={:.1}", start.elapsed().as_secs_f64());
                return Ok(());
            }
            Err(error) => {
                chain.flush()?;
                println!("peer {peer} dropped: {error}");
            }
        }
    }
    Err("no peer finished the sync".into())
}

/// Requests headers from `peer` until it has no more to give.
fn sync_from(chain: &Chain, peer: &str, first: u32, start: Instant) -> Result<(), Failure> {
    let address: SocketAddr = peer.parse()?;
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_secs(15))?;
    stream.set_read_timeout(Some(Duration::from_secs(90)))?;
    send(&mut stream, version_message(address))?;

    loop {
        match receive(&mut stream)? {
            NetworkMessage::Version(version) => {
                println!("peer {peer} is {} at {}", version.user_agent, version.start_height);
                send(&mut stream, NetworkMessage::Verack)?;
            }
            NetworkMessage::Verack => request_headers(&mut stream, chain)?,
            NetworkMessage::Ping(nonce) => send(&mut stream, NetworkMessage::Pong(nonce))?,
            NetworkMessage::Headers(headers) => {
                for header in &headers {
                    chain.accept_header(*header)?;
                }
                let (height, _) = chain.get_best_block()?;
                if height / 100_000 != height.saturating_sub(headers.len() as u32) / 100_000 {
                    let rate = f64::from(height - first) / start.elapsed().as_secs_f64();
                    println!("height={height} headers_per_sec={rate:.0}");
                }
                if headers.len() < MAX_HEADERS_PER_MESSAGE {
                    return Ok(());
                }
                request_headers(&mut stream, chain)?;
            }
            _ => {}
        }
    }
}

fn request_headers(stream: &mut TcpStream, chain: &Chain) -> Result<(), Failure> {
    let locator = chain.get_block_locator()?;
    let request = GetHeadersMessage::new(locator, BlockHash::all_zeros());
    send(stream, NetworkMessage::GetHeaders(request))?;
    Ok(())
}

fn version_message(peer: SocketAddr) -> NetworkMessage {
    let services = ServiceFlags::from(ServiceFlags::WITNESS.to_u64() | NODE_BLAKE2B);
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    let unspecified = SocketAddr::from(([0, 0, 0, 0], 0));
    NetworkMessage::Version(VersionMessage {
        version: PROTOCOL_VERSION,
        services,
        timestamp: now.as_secs() as i64,
        receiver: Address::new(&peer, ServiceFlags::NONE),
        sender: Address::new(&unspecified, services),
        nonce: now.subsec_nanos().into(),
        user_agent: "/Eddy:0.0.1/".to_string(),
        start_height: 0,
        relay: false,
    })
}

fn send(stream: &mut TcpStream, message: NetworkMessage) -> io::Result<()> {
    stream.write_all(&serialize(&RawNetworkMessage::new(Magic::BITCOIN, message)))
}

fn receive(stream: &mut TcpStream) -> Result<NetworkMessage, Failure> {
    let mut head = [0u8; 24];
    stream.read_exact(&mut head)?;
    let length = u32::from_le_bytes([head[16], head[17], head[18], head[19]]) as usize;
    if length > MAX_MESSAGE_BYTES {
        return Err(format!("{length}-byte message").into());
    }
    let mut raw = head.to_vec();
    raw.resize(24 + length, 0);
    stream.read_exact(&mut raw[24..])?;
    let message: RawNetworkMessage = deserialize(&raw)?;
    Ok(message.payload().clone())
}
