package com.turn.ttorrent.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.Client.ClientState;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;

public class ClientSharedTorrent extends SharedTorrent {
	
	private static final Logger logger =
			LoggerFactory.getLogger(ClientSharedTorrent.class);
	
	private ConcurrentMap<String, SharingPeer> peers;
	private ConcurrentMap<String, SharingPeer> connected;
	private ClientState state;
	private Random random;
	private long seed;
	private boolean stop;
	private String id;
	private boolean serverShared;
	private boolean clientDownloaded;
	
	// This is used to determine the number of times
	// we've announced on the server. We stop after this gets to 0.
	private int announces = 2;
	
	public ClientSharedTorrent(Torrent torrent, File destDir, boolean multiThreadHash, boolean seeder)
			throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		super(torrent, destDir, multiThreadHash, seeder);
		this.peers = new ConcurrentHashMap<String, SharingPeer>();
		this.connected = new ConcurrentHashMap<String, SharingPeer>();
		this.random = new Random(System.currentTimeMillis());
	}
	
	public ClientSharedTorrent(byte[] torrent, File destDir, boolean multiThreadHash, boolean seeder)
			throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		super(torrent, destDir, multiThreadHash, seeder);
		this.peers = new ConcurrentHashMap<String, SharingPeer>();
		this.connected = new ConcurrentHashMap<String, SharingPeer>();
		this.random = new Random(System.currentTimeMillis());
	}
	
	/**
	 * Create a new shared torrent from the given torrent file.
	 *
	 * @param source The <code>.torrent</code> file to read the torrent
	 * meta-info from.
	 * @param parent The parent directory or location of the torrent files.
	 * @throws IOException When the torrent file cannot be read or decoded.
	 * @throws NoSuchAlgorithmException
	 */
	public static ClientSharedTorrent fromFile(File source, File parent, boolean multiThreadHash)
		throws IOException, NoSuchAlgorithmException {
		FileInputStream fis = new FileInputStream(source);
		byte[] data = new byte[(int)source.length()];
		fis.read(data);
		fis.close();
		return new ClientSharedTorrent(data, parent, multiThreadHash, false);
	}
	
	public void initialize() throws IOException {
		// First, analyze the torrent's local data.
		try {
			setState(ClientState.VALIDATING);
			this.init();
		} catch (IOException ioe) {
			logger.warn("Error while initializing torrent data: {}!",
				ioe.getMessage(), ioe);
		} catch (InterruptedException ie) {
			logger.warn("Client was interrupted during initialization. " +
					"Aborting right away.");
		} finally {
			if (!this.isInitialized()) {
				setState(ClientState.ERROR);
				this.close();
				return;
			}
		}
		
		// Initial completion test
		if (this.isComplete()) {
			this.seed();
		} else {
			setState(ClientState.SHARING);
		}
		
		// Detect early stop
		if (this.stop) {
			this.finish();
			return;
		}
	}
	
	/**
	 * Download the torrent without seeding after completion.
	 * @throws IOException 
	 */
	public void download() throws IOException {
		this.share(0);
	}

	/**
	 * Download and share this client's torrent until interrupted.
	 * @throws IOException 
	 */
	public void share() throws IOException {
		this.share(-1);
	}
	
	/**
	 * Set this ClientSharedTorrent to download and share.
	 *
	 * @param seed Seed time in seconds after the download is complete. Pass
	 * <code>0</code> to immediately stop after downloading.
	 * @throws IOException 
	 */
	public synchronized void share(int seed) throws IOException {
		this.seed = seed;
		this.stop = false;
		this.initialize();
	}
	
	public synchronized void seed() {
		// Silently ignore if we're already seeding.
		if (ClientState.SEEDING.equals(this.state)) {
			return;
		}

		logger.trace("Download of {} pieces completed.",
			this.getPieceCount());

		if (this.seed == 0) {
			logger.trace("No seeding requested, stopping client...");
			this.stop();
			return;
		}

		setState(ClientState.SEEDING);
		if (this.seed < 0) {
			logger.trace("Seeding indefinetely...");
			return;
		}

		logger.trace("Seeding for {} seconds...", this.seed);
		Timer timer = new Timer();
		timer.schedule(new TorrentShutdown(this, timer), this.seed*1000);
	}
	
	/**
	 * Timer task to stop seeding.
	 *
	 * <p>
	 * This TimerTask will be called by a timer set after the download is
	 * complete to stop seeding from this client after a certain amount of
	 * requested seed time (might be 0 for immediate termination).
	 * </p>
	 *
	 * <p>
	 * This task simply contains a reference to this client instance and calls
	 * its <code>stop()</code> method to interrupt the client's main loop.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	private static class TorrentShutdown extends TimerTask {

		private final ClientSharedTorrent torrent;
		private final Timer timer;

		TorrentShutdown(ClientSharedTorrent torrent, Timer timer) {
			this.torrent = torrent;
			this.timer = timer;
		}

		@Override
		public void run() {
			this.torrent.stop();
			if (this.timer != null) {
				this.timer.cancel();
			}
		}
	};
	
	public synchronized void unchokePeers(boolean optimistic) {
		// Build a set of all connected peers, we don't care about peers we're
		// not connected to.
		PriorityQueue<SharingPeer> bound = new PriorityQueue<SharingPeer>(10, this.getPeerRateComparator());
		
		bound.addAll(this.connected.values());

		if (bound.size() == 0) {
			logger.trace("No connected peers, skipping unchoking.");
			return;
		} else {
			logger.trace("Running unchokePeers() on {} connected peers.",
					bound.size());
		}

		int downloaders = 0;
		Set<SharingPeer> choked = new HashSet<SharingPeer>();

		// We're interested in the top downloaders first, so use a descending
		// set.
		for (SharingPeer peer : bound) {
			if (downloaders < MultiTorrentClient.MAX_DOWNLOADERS_UNCHOKE) {
				// Unchoke up to MAX_DOWNLOADERS_UNCHOKE interested peers
				if (peer.isChoking()) {
					if (peer.isInterested()) {
						downloaders++;
					}

					logger.info("Unchoking {}", peer);
					peer.unchoke();
				}
			} else {
				// Choke everybody else
				logger.info("Adding {} to choke list");
				choked.add(peer);
			}
		}

		// Actually choke all chosen peers (if any), except the eventual
		// optimistic unchoke.
		if (choked.size() > 0) {
			SharingPeer randomPeer = choked.toArray(
					new SharingPeer[0])[this.random.nextInt(choked.size())];

			for (SharingPeer peer : choked) {
				if (optimistic && peer == randomPeer) {
					continue;
				}

				logger.trace("Choking {}", peer);
				peer.choke();
			}
		}
		
		Iterator<Map.Entry<Integer, PeerAndMillis>> entries = this.requestedPiecesTime.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<Integer, PeerAndMillis> pieceTime = entries.next();
			if (System.currentTimeMillis() - pieceTime.getValue().time >= PIECE_TIMEOUT_MILLIS) {
				// This piece hasn't finished in the alotted time, release it from the requests - we'll try again later
				logger.info("Piece {} timed out - releasing from requests", pieceTime.getKey());
				this.requestedPieces.set(pieceTime.getKey(), false);
				pieceTime.getValue().peer.clearRequests();
				entries.remove();
				
				if (pieceTime.getValue().peer.getSocketChannel().isOpen()) {
					logger.info("Socket channel is still good - tell the peer to request again");
					handlePeerReady(pieceTime.getValue().peer);
				} else {
					logger.info("Socket channel is no good. Don't let it request again.");
				}
			}
		}
	}
	
	public synchronized void releasePiece(Piece piece) {
		this.requestedPieces.set(piece.getIndex(), false);
		this.requestedPiecesTime.remove(piece.getIndex());
	}
	
	public ClientState getClientState() {
		return this.state;
	}
	
	private Comparator<SharingPeer> getPeerRateComparator() {
		if (ClientState.SHARING.equals(this.state)) {
			return new SharingPeer.DLRateComparator();
		} else if (ClientState.SEEDING.equals(this.state)) {
			return new SharingPeer.ULRateComparator();
		} else {
			throw new IllegalStateException("ClientSharedTorrent is neither sharing nor " +
					"seeding, we shouldn't be comparing peers at this point.");
		}
	}
	
	public void resetPeerRates() {
		for (SharingPeer peer : this.connected.values()) {
			peer.getDLRate().reset();
			peer.getULRate().reset();
		}
	}
	
	public synchronized void info() {
		List<Float> rates = getRates();

		/*logger.info("{} {}/{} pieces ({}%) [{}/{}] with {}/{} peers at {}/{} kB/s.",
			new Object[] {
				this.state.name(),
				this.getCompletedPieces().cardinality(),
				this.getPieceCount(),
				String.format("%.2f", this.getCompletion()),
				this.getAvailablePieces().cardinality(),
				this.getRequestedPieces().cardinality(),
				this.connected.size(),
				this.peers.size(),
				String.format("%.2f", rates.get(1)/1024.0),
				String.format("%.2f", rates.get(0)/1024.0),
			});*/
	}
	
	public List<Float> getRates() {
		float dl = 0;
		float ul = 0;
		List<Float> rates = new ArrayList<Float>();
		for (SharingPeer peer : this.connected.values()) {
			dl += peer.getDLRate().get();
			ul += peer.getULRate().get();
			if (peer.isServer()) {
				peer.getTorrent().getCompletion();
			}
		}
		
		rates.add(ul);
		rates.add(dl);
		
		return rates;
	}
	
	public SharingPeer getOrCreatePeer(Peer search) {
		SharingPeer peer;

		synchronized (this.peers) {
			if (search.hasPeerId()) {
				peer = this.peers.get(search.getHexPeerId());
				if (peer != null) {
					this.peers.put(peer.getHostIdentifier(), peer);
					this.peers.put(search.getHostIdentifier(), peer);
					return peer;
				}
			}

			peer = this.peers.get(search.getHostIdentifier());
			if (peer != null) {
				if (search.hasPeerId()) {
					peer.setPeerId(search.getPeerId());
					this.peers.put(search.getHexPeerId(), peer);
				}

				return peer;
			}

			peer = new SharingPeer(search.getIp(), search.getPort(),
				search.getPeerId(), this);

			this.peers.put(peer.getHostIdentifier(), peer);
			if (peer.hasPeerId()) {
				this.peers.put(peer.getHexPeerId(), peer);
			}

			return peer;
		}
	}
	
	public ConcurrentMap<String, SharingPeer> getConnected() {
		return this.connected;
	}
	
	public ConcurrentMap<String, SharingPeer> getPeers() {
		return this.peers;
	}
	
	public void change() {
		this.setChanged();
		this.notifyObservers(this.getClientState());
	}
	
	/**
	 * Change this torrent's state and notify its observers.
	 *
	 * <p>
	 * If the state has changed, this torrent's observers will be notified.
	 * </p>
	 *
	 * @param state The new client state.
	 */
	public synchronized void setState(ClientState state) {
		if (this.state != state) {
			this.setChanged();
		}
		this.state = state;
		this.notifyObservers(this.state);
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}
	
	public boolean isServerShared() {
		return serverShared;
	}

	public void setServerShared(boolean serverShared) {
		this.serverShared = serverShared;
	}

	public boolean isClientDownloaded() {
		return clientDownloaded;
	}

	public void setClientDownloaded(boolean clientDownloaded) {
		this.clientDownloaded = clientDownloaded;
	}

	public int getAnnounces() {
		return announces;
	}

	public void setAnnounces(int announces) {
		this.announces = announces;
	}
}
