package com.turn.ttorrent.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.Client.ClientState;
import com.turn.ttorrent.client.SharedTorrent.PeerAndMillis;
import com.turn.ttorrent.client.announce.AnnounceException;
import com.turn.ttorrent.client.announce.AnnounceResponseListener;
import com.turn.ttorrent.client.announce.MultiTorrentAnnounce;
import com.turn.ttorrent.client.nio.PeerCommunicationManager;
import com.turn.ttorrent.client.nio.TorrentPeerWrapper;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage;

/**
 * An implementation of the Client class that manages multiple torrents and uses a non-blocking IO setup. 
 * This was mainly created to significantly reduce thread counts.
 * 
 * @author cshoff
 *
 */
public class MultiTorrentClient implements 
	Runnable, AnnounceResponseListener, CommunicationListener, PeerActivityListener {
	
	private static final Logger logger =
			LoggerFactory.getLogger(MultiTorrentClient.class);
	
	private static final String BITTORRENT_ID_PREFIX = "-TO0042-";
	
	/** Peers unchoking frequency, in seconds. Current BitTorrent specification
	 * recommends 10 seconds to avoid choking fibrilation. */
	protected static final int UNCHOKING_FREQUENCY = 3;

	/** Optimistic unchokes are done every 2 loop iterations, i.e. every
	 * 2*UNCHOKING_FREQUENCY seconds. */
	protected static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;
	
	protected static final int RATE_COMPUTATION_ITERATIONS = 2;
	public static final int MAX_DOWNLOADERS_UNCHOKE = 4;
	private static final int VOLUNTARY_OUTBOUND_CONNECTIONS = 20;
	
	protected PeerCommunicationManager service;
	protected MultiTorrentAnnounce announce;
	protected Peer self;
	private String id;
	protected boolean stop = false;
	protected boolean server;
	
	// The purpose of this map is to quickly look up a peer by its socket in the event that the PeerCommunication Manager
	// finds a bad socket channel
	protected HashMap<SocketChannel, SharingPeer> socketChannelMap = new HashMap<SocketChannel, SharingPeer>();
	
	// Map of socket channels to torrents and peers. This allows us to quickly determine what peers we are talking to
	// when a connection is being created and managed.
	private Map<SocketChannel, TorrentPeerWrapper> torrentPeerAssociations = new HashMap<SocketChannel, TorrentPeerWrapper>();
	
	Thread thread;
	
	// List of all torrents this client is currently sharing/downloading
	protected ConcurrentMap<String, ClientSharedTorrent> torrents = new ConcurrentHashMap<String, ClientSharedTorrent>();
	
	public MultiTorrentClient(InetAddress address) 
			throws UnknownHostException, IOException {	
		this(address, false);	
	}
	
	public MultiTorrentClient(InetAddress address, boolean server) 
			throws UnknownHostException, IOException {	
		this(address, address, server);
	}
	
	public MultiTorrentClient(InetAddress localAddress, InetAddress publicAddress)
			throws UnknownHostException, IOException {
		this(localAddress, publicAddress, false);
	}
	
	public MultiTorrentClient(InetAddress localAddress, InetAddress publicAddress, boolean server)
			throws UnknownHostException, IOException {
		
		this.id = MultiTorrentClient.BITTORRENT_ID_PREFIX + UUID.randomUUID()
				.toString().split("-")[4];
		
		// Initialize the peer communication manager and register ourselves to
		// it.
		this.service = new PeerCommunicationManager(localAddress);
		this.service.register(this);
		
		this.self = new Peer(
			publicAddress.getHostAddress(),
			(short)this.service.getAddress().getPort(),
			ByteBuffer.wrap(id.getBytes(Torrent.BYTE_ENCODING)), server);
		
		this.server = server;
		
		// Initialize the announce request thread, and register ourselves to it
		// as well.
		this.announce = new MultiTorrentAnnounce(this.self, this.server);
		this.announce.register(this);
		
		start();
	}
	
	/**
	 * Add a torrent to this client to be shared/downloaded.
	 * @param torrent to be added
	 * @throws UnknownHostException
	 * @throws UnknownServiceException
	 */
	public void addTorrent(ClientSharedTorrent torrent) throws UnknownHostException, UnknownServiceException {
		boolean alreadyShared = false;
		if (this.torrents.get(torrent.getHexInfoHash()) != null) {
			// This torrent is already being shared
			alreadyShared = true;
		}
		this.torrents.put(torrent.getHexInfoHash(), torrent);
		// Only add this if it's not already shared, or this is the server (where we remove tracked torrents that aren't actively being shared)
		if (!alreadyShared || this.server) {
			this.announce.addTorrent(torrent);
		}
	}
	
	public void start() {
		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-client(" +
				this.self.getShortHexPeerId() + ")");
			this.thread.start();
		}
	}
	
	public void share(String identifier) throws IOException {
		ClientSharedTorrent torrent = torrents.get(identifier);
		if (torrent != null) {
			logger.trace("Sharing torrent with hash " + identifier);
			torrent.share();
		}
	}
	
	@Override
	public void handleNewConnection(SocketChannel socketChannel, String hexInfoHash) {
		try {
			byte[] handshakeData = Handshake.craft(this.torrents.get(hexInfoHash).getInfoHash(),
						this.id.getBytes(Torrent.BYTE_ENCODING)).getBytes();
			this.torrentPeerAssociations.put(socketChannel, new TorrentPeerWrapper(hexInfoHash));
			this.service.send(socketChannel, handshakeData);
		} catch (UnsupportedEncodingException e) {
			logger.error("There was a problem creating the handshake", e);
		}
	}
	
	@Override
	public void handleNewData(SocketChannel socketChannel, List<ByteBuffer> data) {
		for (ByteBuffer singleData : data) {
			handleMessage(singleData, socketChannel);
		}
	}
	
	/**
	 * Takes a byte buffer of data and its originating socket channel, determines what type of message it was and handles it.
	 * @param data
	 * @param socketChannel
	 */
	private void handleMessage(ByteBuffer data, SocketChannel socketChannel) {
		
		int pstrlen = data.getInt();
		TorrentPeerWrapper tpw = this.torrentPeerAssociations.get(socketChannel);
		
		if (pstrlen >= 0 && data.remaining() == Handshake.BASE_HANDSHAKE_LENGTH) {
			try {
				logger.trace("Got a handshake on channel {}", socketChannel);
				Handshake hs = this.validateHandshake(socketChannel, data.array(), null);

				if (tpw == null) {
					logger.trace("We don't know this peer. Send a handshake back");
					byte[] handshakeData = Handshake.craft(hs.getInfoHash(),
							this.id.getBytes(Torrent.BYTE_ENCODING)).getBytes();
					this.service.send(socketChannel, handshakeData);
					this.handleNewPeerConnection(socketChannel, hs.getPeerId(), Torrent.byteArrayToHexString(hs.getInfoHash()));
				} else {
					logger.trace("It was a return handshake from peer. We're good with this peer.", tpw.peer);
					this.handleNewPeerConnection(socketChannel, hs.getPeerId(), Torrent.byteArrayToHexString(hs.getInfoHash()));
				}
			} catch (IOException e) {
				logger.error("There was a problem validating the handshake.", e);
			} catch (ParseException e) {
				logger.error("There was a problem validating the handshake.", e);
			}
		} else {
			if (tpw == null) {
				// We got a message on a socket channel with no torrent or peer associated with it
				// We have to ignore it as we have no idea what to do with it
				return;
			}
			if (tpw.peer != null) {

				SharingPeer peer = tpw.peer;
				try {
					PeerMessage msg;
					msg = PeerMessage.parse(data, this.torrents.get(tpw.torrentInfoHash));
					peer.handleMessage(msg);
				} catch (ParseException e) {
					logger.error("There was a problem parsing the PeerMessage", e);
				}
				
			} else {
				logger.error("Can't find an associated peer.");
			}
		}
	}
	
	/**
	 * Validate an expected handshake on a connection.
	 *
	 * <p>
	 * Reads an expected handshake message from the given connected socket,
	 * parses it and validates that the torrent hash_info corresponds to the
	 * torrent we're sharing, and that the peerId matches the peer ID we expect
	 * to see coming from the remote peer.
	 * </p>
	 *
	 * @param socketChannel The socket channel for the remote peer.
	 * @param data The handshake data
	 * @param peerId The peer ID we expect in the handshake. If <em>null</em>,
	 * any peer ID is accepted (this is the case for incoming connections).
	 * @return The validated handshake message object.
	 */
	public Handshake validateHandshake(SocketChannel socketChannel, byte[] data, byte[] peerId)
			throws IOException, ParseException {

			// Parse and check the handshake
			Handshake hs = Handshake.parse(ByteBuffer.wrap(data));
			
			ClientSharedTorrent hsTorrent = this.torrents.get(Torrent.byteArrayToHexString(hs.getInfoHash()));
			if (hsTorrent != null) {
				if (!Arrays.equals(hs.getInfoHash(), hsTorrent.getInfoHash())) {
					throw new ParseException("Handshake for unknown torrent " +
							Torrent.byteArrayToHexString(hs.getInfoHash()) +
							" from " + PeerCommunicationManager.socketRepr(socketChannel.socket()) + ".", 1); //TODO: Hardcoded as 1 for now
				}
			}
			

			if (peerId != null && !Arrays.equals(hs.getPeerId(), peerId)) {
				throw new ParseException("Announced peer ID " +
						Torrent.byteArrayToHexString(hs.getPeerId()) +
						" did not match expected peer ID " +
						Torrent.byteArrayToHexString(peerId) + ".", 2); //TODO: Hardcoded as 2 for now
			}

			return hs;
		}

	public void handleNewPeerConnection(SocketChannel sc, byte[] peerId, String hexInfoHash) {
		ClientSharedTorrent torrent = this.torrents.get(hexInfoHash);
		
		if (torrent == null) {
			logger.error("Peer connection received for a non-existent torrent {}", hexInfoHash);
			return;
		}
		
 		Peer search = new Peer(
			sc.socket().getInetAddress().getHostAddress(),
			sc.socket().getPort(),
			(peerId != null
				? ByteBuffer.wrap(peerId)
				: (ByteBuffer)null));
	
		SharingPeer peer = torrent.getOrCreatePeer(search);
		peer.setSocketChannel(sc);
		
		// Attach the SharingPeer to the selection key
		this.torrentPeerAssociations.put(sc, new TorrentPeerWrapper(peer, hexInfoHash));
		
		logger.trace("Registering new peer {} with id " + peerId + " for torrent {}", peer, torrent.getHexInfoHash());
		peer.setBound(true);
		peer.resetRates();
		torrent.getConnected().put(peer.getHexPeerId(), peer);
		socketChannelMap.put(sc, peer);
		peer.register(torrent);
		peer.register(this);
	
		// If we have pieces, start by sending a BITFIELD message to the peer.
		BitSet pieces = this.torrents.get(hexInfoHash).getCompletedPieces();
		if (pieces.cardinality() > 0) {
			logger.trace("We have pieces, so send a BITFIELD message to peer {}", peer);
			this.service.send(sc, PeerMessage.BitfieldMessage.craft(pieces).getData().array());
			if (this.server) {
				this.service.send(sc, PeerMessage.ServerMessage.craft((int) torrent.getCompletion()).getData().array());
			}
		}
	}

	@Override
	public void handleFailedConnection(SharingPeer peer, Throwable cause) {
		ClientSharedTorrent torrent = this.torrents.get(peer.getTorrentHexInfoHash());
		
		if (torrent == null) {
			logger.error("Piece completed for unknown torrent {} (this shouldn't happen)", peer.getTorrentHexInfoHash());
			return;
		}
		
		logger.trace("Could not connect to {}: {}.", peer, cause.getMessage());
		torrent.getPeers().remove(peer.getHostIdentifier());
		if (peer.hasPeerId()) {
			torrent.getPeers().remove(peer.getHexPeerId());
		}
	}

	@Override
	public void handlePieceCompleted(SharingPeer peer, Piece piece)
			throws IOException {
		ClientSharedTorrent torrent = this.torrents.get(peer.getTorrentHexInfoHash());
		
		if (torrent == null) {
			logger.error("Piece completed for unknown torrent {} (this shouldn't happen)", peer.getTorrentHexInfoHash());
			return;
		}
		
		synchronized (torrent) {
			if (piece.isValid()) {
				
				// Make sure the piece is marked as completed in the torrent
				// Note: this is required because the order the
				// PeerActivityListeners are called is not defined, and we
				// might be called before the torrent's piece completion
				// handler is.
				torrent.markCompleted(piece);
				logger.trace("Completed download of {}, now has {}/{} pieces.",
					new Object[] {
						piece,
						torrent.getCompletedPieces().cardinality(),
						torrent.getPieceCount()
					});

				// Send a HAVE message to all connected peers
				PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
				for (SharingPeer remote : torrent.getConnected().values()) {
					remote.send(have);
					// If this is the server, send a server completion message as well
					if (this.server) {
						remote.send(PeerMessage.ServerMessage.craft((int) torrent.getCompletion()));
					}
				}

				// Force notify after each piece is completed to propagate download
				// completion information (or new seeding state)
				torrent.change();
			}

			if (torrent.isComplete()) {
				logger.info("Last piece validated and completed, " +
						"download is complete.");
				
				torrent.finish();

				try {
					this.announce.getTrackerClient()
						.announce(TrackerMessage
							.AnnounceRequestMessage
							.RequestEvent.COMPLETED, true);
				} catch (AnnounceException ae) {
					logger.warn("Error announcing completion event to " +
						"tracker: {}", ae.getMessage());
				}

				torrent.seed();
			}
		}
	}

	@Override
	public void handlePeerDisconnected(SharingPeer peer) {
		ClientSharedTorrent torrent = this.torrents.get(peer.getTorrentHexInfoHash());
		
		if (torrent == null) {
			logger.error("Peer disconnected from a torrent we don't know about {} (this shouldn't happen)", peer.getTorrentHexInfoHash());
			return;
		}
		
		if (torrent.getConnected().remove(peer.hasPeerId()
					? peer.getHexPeerId()
					: peer.getHostIdentifier()) != null) {
			logger.trace("Peer {} disconnected, [{}/{}].",
				new Object[] {
					peer,
					torrent.getConnected().size(),
					torrent.getPeers().size()
				});
		}
		
		if (peer.getRequestedPiece() != null) {
			torrent.releasePiece(peer.getRequestedPiece());
		}
		
		socketChannelMap.remove(peer.getSocketChannel());
		torrentPeerAssociations.remove(peer.getSocketChannel());
	
		peer.reset();
	}

	@Override
	public void handleIOException(SharingPeer peer, IOException ioe) {
		logger.error("There was an IOException", ioe);
	}

	@Override
	public void handleAnnounceResponse(int interval, int complete,
			int incomplete) {
		this.announce.setInterval(interval);
	}

	@Override
	public void handleDiscoveredPeers(List<Peer> peers, String hexInfoHash) {
		ClientSharedTorrent torrent = this.torrents.get(hexInfoHash);
		
		if (torrent == null) {
			logger.error("Discovered peers for a non-existent torrent {}", hexInfoHash);
			return;
		}
		
		if (peers == null || peers.isEmpty()) {
			// No peers returned by the tracker. Apparently we're alone on
			// this one for now.
			return;
		}

		logger.trace("Got {} peer(s) in tracker response, initiating " +
			"connections...", peers.size());

		if (!this.service.isAlive()) {
			logger.warn("Connection handler service is not available.");
			return;
		}

		for (Peer peer : peers) {
			SharingPeer match = torrent.getOrCreatePeer(peer);

			synchronized (match) {
				// Attempt to connect to the peer if and only if:
				//   - We're not already connected to it;
				//   - We're not a seeder (we leave the responsibility
				//	   of connecting to peers that need to download
				//     something), or we are a seeder but we're still
				//     willing to initiate some out bound connections.
				if (match.isConnected() ||
					(torrent.isComplete() && torrent.getConnected().size() >=
						MultiTorrentClient.VOLUNTARY_OUTBOUND_CONNECTIONS)) {
					continue;
				}

				try {
					logger.trace("Connecting to new peer {}", match.getPeerId());
					SocketChannel sc = this.service.connect(match.getAddress(), match.getPort(), torrent.getInfoHash());
				} catch (IOException e) {
					logger.error("There was a problem connecting to the peer", e);
				}
			}
		}
	}

	@Override
	public void run() {
		this.announce.start();
		this.service.start();

		int optimisticIterations = 0;
		int rateComputationIterations = 0;
		
		while (!this.stop) {
			optimisticIterations =
					(optimisticIterations == 0 ?
							MultiTorrentClient.OPTIMISTIC_UNCHOKE_ITERATIONS :
								optimisticIterations - 1);

			rateComputationIterations =
					(rateComputationIterations == 0 ?
							MultiTorrentClient.RATE_COMPUTATION_ITERATIONS :
								rateComputationIterations - 1);
			
			for (ClientSharedTorrent torrent : this.torrents.values()) {
				if (!ClientState.SHARING.equals(torrent.getClientState()) && !ClientState.SEEDING.equals(torrent.getClientState())) {
					continue;
				}
				try {
					torrent.unchokePeers(optimisticIterations == 0);
					torrent.info();
					
					if (rateComputationIterations == 0) {
						torrent.resetPeerRates();
					}
				} catch (Exception e) {
					logger.error("An exception occurred during the BitTorrent " +
							"client main loop execution!", e);
				}
			}
			
			try {
				Thread.sleep(MultiTorrentClient.UNCHOKING_FREQUENCY*1000);
			} catch (InterruptedException ie) {
				logger.info("BitTorrent main loop interrupted.");
			}
		}
		
		// Close all peer connections
		logger.info("Closing all remaining peer connections...");
		for (Map.Entry<String, ClientSharedTorrent> entry : this.torrents.entrySet()) {
			for (SharingPeer peer : entry.getValue().getConnected().values()) {
				peer.unbind(true);
			}
		}
	}
	
	public void stop() {
		this.stop(true);
	}
	
	public void stop(boolean wait) {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();
			if (wait) {
				try {
					this.thread.join();
				} catch (InterruptedException ie) {
					// Ignore
				}
			}
		}

		this.thread = null;
	}
	

	/** PeerActivityListener handler(s). **************************************/

	@Override
	public void handlePeerChoked(SharingPeer peer) { /* Do nothing */ }

	@Override
	public void handlePeerReady(SharingPeer peer) { /* Do nothing */ }

	@Override
	public void handlePieceAvailability(SharingPeer peer,
			Piece piece) { /* Do nothing */ }

	@Override
	public void handleBitfieldAvailability(SharingPeer peer,
			BitSet availablePieces) { /* Do nothing */ }

	@Override
	public void handlePieceSent(SharingPeer peer,
			Piece piece) { /* Do nothing */ }

	@Override
	public void sendPeerMessage(SharingPeer peer, PeerMessage message) {
		logger.trace("Sending a {} message to peer {} regarding torrent " + peer.getTorrent().toString(), message.getType(), peer);
		this.service.send(peer.getSocketChannel(), message.getData().array());
	}
	
	@Override
	public void handleBadSocket(SocketChannel socketChannel) {
		logger.warn("Socket Channel " + socketChannel + " is bad. Remove the associated peer.");
		SharingPeer badPeer = socketChannelMap.get(socketChannel);
		if (badPeer != null) {
			logger.warn("Disconnecting peer {}", badPeer);
			handlePeerDisconnected(badPeer);
		}
	}

	@Override
	public void handleNewPeerConnection(Socket s, byte[] peerId,
			String torrentIdentifier) {	/* Do nothing */ }

}
