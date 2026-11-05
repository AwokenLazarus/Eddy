#!/system/bin/sh
# Runs inside Android: Knots on regtest with the BLAKE2b fork at height 20, and two Lightning Fork nodes.
# Opens a channel, pays an invoice, and force-closes, so a commitment transaction with unified
# signatures has to pass Knots's validation. Expects bitcoind, bitcoin-cli, lnd and lncli in
# /data/local/tmp/eddy. Uses regtest coins only.
E=/data/local/tmp/eddy; W=/data/local/tmp/e2e; cd $E
pkill lnd; pkill bitcoind; sleep 2; rm -rf $W; mkdir -p $W/k $W/a $W/b
bc() { $E/bitcoin-cli -regtest -datadir=$W/k -rpcuser=lab -rpcpassword=lab "$@"; }
la() { $E/lncli --lnddir=$W/a --network=regtest --rpcserver=127.0.0.1:10009 "$@"; }
lb() { $E/lncli --lnddir=$W/b --network=regtest --rpcserver=127.0.0.1:10010 "$@"; }
field() { grep -m1 "\"$1\"" | sed 's/.*": *"\{0,1\}//; s/"\{0,1\},\{0,1\} *$//'; }
nohup $E/bitcoind -regtest -datadir=$W/k -rpcuser=lab -rpcpassword=lab -testactivationheight=blake2b@20 "-blake2b_headline=Lightning Fork lab" -listenonion=0 -txindex=1 -fallbackfee=0.0002 -zmqpubrawblock=tcp://127.0.0.1:28332 -zmqpubrawtx=tcp://127.0.0.1:28333 > $W/k.log 2>&1 &
for i in $(seq 40); do bc getblockchaininfo >/dev/null 2>&1 && break; sleep 1; done
bc generatetoaddress 30 bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080 >/dev/null; echo "pre-mined to $(bc getblockcount)"
for n in a:10009:9735 b:10010:9736; do N=${n%%:*}; R=$(echo $n|cut -d: -f2); P=$(echo $n|cut -d: -f3)
 nohup $E/lnd --lnddir=$W/$N --noseedbackup --bitcoin.regtest --bitcoin.node=bitcoind --bitcoin.blake2b-activation-height=20 --bitcoind.rpchost=127.0.0.1:18443 --bitcoind.rpcuser=lab --bitcoind.rpcpass=lab --bitcoind.zmqpubrawblock=tcp://127.0.0.1:28332 --bitcoind.zmqpubrawtx=tcp://127.0.0.1:28333 --rpclisten=127.0.0.1:$R --listen=127.0.0.1:$P --restlisten=127.0.0.1:0 --alias=eddy-$N --debuglevel=info > $W/$N.out 2>&1 &
done
for i in $(seq 120); do la getinfo >/dev/null 2>&1 && lb getinfo >/dev/null 2>&1 && break; sleep 1; done; [ $i = 120 ] && { echo LND-NOT-READY; tail -6 $W/a.out; }
echo "lnd a: $(la getinfo | field version)"
ADDR=$(la newaddress p2wkh | field address); echo "addr $ADDR"
bc generatetoaddress 130 $ADDR >/dev/null || { echo MINE-FAIL; tail -5 $W/k.log; }
echo "height $(bc getblockcount)"; H=$(bc getblockhash 25); echo "header-25 hexlen $(bc getblockheader $H false | wc -c)"
for i in $(seq 90); do [ "$(la getinfo | field synced_to_chain)" = true ] && [ "$(lb getinfo | field synced_to_chain)" = true ] && break; sleep 1; done
echo "synced a=$(la getinfo | field synced_to_chain) b=$(lb getinfo | field synced_to_chain)"
for i in $(seq 30); do B=$(la walletbalance | field confirmed_balance); [ "${B:-0}" != 0 ] && break; sleep 1; done; echo "a wallet $B"
PB=$(lb getinfo | field identity_pubkey); la connect $PB@127.0.0.1:9736 >/dev/null; sleep 2
la openchannel --node_key=$PB --local_amt=1000000 --sat_per_vbyte=2 | tail -3
bc generatetoaddress 6 $ADDR >/dev/null
for i in $(seq 90); do [ "$(la listchannels | field active)" = true ] && break; sleep 1; done
echo "channel active=$(la listchannels | field active) type=$(la listchannels | field commitment_type)"
PA=$(la getinfo | field identity_pubkey)
paid=no; for try in $(seq 20); do
 INV=$(lb addinvoice --amt=1000 --memo=eddy | field payment_request)
 la payinvoice --force --json "$INV" > $W/pay.json 2>&1
 grep -q '"status": *"SUCCEEDED"' $W/pay.json && { paid=yes; break; }; sleep 3
done; echo "paid=$paid after $try tries; preimage=$(field payment_preimage < $W/pay.json | cut -c1-16)..."
echo "a channel: $(la channelbalance | field balance)  b channel: $(lb channelbalance | field balance)"
wit() { bc getrawtransaction $1 1 | grep -A4 txinwitness | grep -E '^ *"[0-9a-f]{100,}' | sed 's/[", ]//g' | while read x; do echo -n "$(echo $x | tail -c 3) "; done; }
CP=$(la listchannels | field channel_point); T=${CP%%:*}; I=${CP##*:}
la closechannel --funding_txid=$T --output_index=$I --force --sat_per_vbyte=0 2>&1 | grep -E "closing_txid|error|rror" | head -2
sleep 4; bc generatetoaddress 1 $ADDR >/dev/null; sleep 8
C=$(la pendingchannels | grep -m1 closing_txid | sed 's/.*: *"//; s/".*//'); echo "force-close tx $C"
echo "commit-tx witness signature sighash bytes: $(wit $C)  (0x21 = SIGHASH_ALL|UNIFIED)"
echo "confirmations: $(bc getrawtransaction $C 1 | field confirmations) tip=$(bc getblockcount) tipheaderlen=$(bc getblockheader $(bc getbestblockhash) false | wc -c)"
pgrep -l 'lnd|bitcoind'; echo "rss: $(for p in $(pgrep 'lnd|bitcoind'); do grep -h VmRSS /proc/$p/status | tr -s ' ' | cut -d' ' -f2; done | tr '\n' ' ')"
echo E2E-DONE
