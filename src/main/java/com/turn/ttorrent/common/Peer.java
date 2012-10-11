/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.common;

import com.turn.ttorrent.common.Torrent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;


/**
 * A basic BitTorrent peer.
 *
 * <p>
 * This class is meant to be a common base for the tracker and client, which
 * would presumably subclass it to extend its functionality and fields.
 * </p>
 *
 * @author mpetazzoni
 */
public class Peer {

	private final InetSocketAddress address;
	private final String hostId;

	private ByteBuffer peerId;
	private String hexPeerId;
	
	protected boolean server = false;
	protected int serverCompletion = 0;

	/**
	 * Instantiate a new peer.
	 *
	 * @param address The peer's address, with port.
	 */
	public Peer(InetSocketAddress address) {
		this(address, false);
	}
	public Peer(InetSocketAddress address, boolean server) {
		this(address, null, server);
	}

	/**
	 * Instantiate a new peer.
	 *
	 * @param ip The peer's IP address.
	 * @param port The peer's port.
	 */
	public Peer(String ip, int port) {
		this(ip, port, false);
	}
	public Peer(String ip, int port, boolean server) {
		this(new InetSocketAddress(ip, port), null, server);
	}

	/**
	 * Instantiate a new peer.
	 *
	 * @param ip The peer's IP address.
	 * @param port The peer's port.
	 * @param peerId The byte-encoded peer ID.
	 */
	public Peer(String ip, int port, ByteBuffer peerId) {
		this(ip, port, peerId, false);
	}
	public Peer(String ip, int port, ByteBuffer peerId, boolean server) {
		this(new InetSocketAddress(ip, port), peerId, server);
	}

	/**
	 * Instantiate a new peer.
	 *
	 * @param address The peer's address, with port.
	 * @param peerId The byte-encoded peer ID.
	 */
	public Peer(InetSocketAddress address, ByteBuffer peerId) {
		this(address, peerId, false);
	}
	public Peer(InetSocketAddress address, ByteBuffer peerId, boolean server) {
		this.address = address;
		this.hostId = String.format("%s:%d",
			this.address.getAddress(),
			this.address.getPort());

		this.setPeerId(peerId);
		this.server = server;
	}

	/**
	 * Tells whether this peer has a known peer ID yet or not.
	 */
	public boolean hasPeerId() {
		return this.peerId != null;
	}

	/**
	 * Returns the raw peer ID as a {@link ByteBuffer}.
	 */
	public ByteBuffer getPeerId() {
		return this.peerId;
	}

	/**
	 * Set a peer ID for this peer (usually during handshake).
	 *
	 * @param peerId The new peer ID for this peer.
	 */
	public void setPeerId(ByteBuffer peerId) {
		if (peerId != null) {
			this.peerId = peerId;
			this.hexPeerId = Torrent.byteArrayToHexString(peerId.array());
		} else {
			this.peerId = null;
			this.hexPeerId = null;
		}
	}

	/**
	 * Get the hexadecimal-encoded string representation of this peer's ID.
	 */
	public String getHexPeerId() {
		return this.hexPeerId;
	}

	/**
	 * Get the shortened hexadecimal-encoded peer ID.
	 */
	public String getShortHexPeerId() {
		return String.format("..%s",
			this.hexPeerId.substring(this.hexPeerId.length()-6).toUpperCase());
	}

	/**
	 * Returns this peer's IP address.
	 */
	public String getIp() {
		return this.address.getAddress().getHostAddress();
	}

	/**
	 * Returns this peer's InetAddress.
	 */
	public InetAddress getAddress() {
		return this.address.getAddress();
	}

	/**
	 * Returns this peer's port number.
	 */
	public int getPort() {
		return this.address.getPort();
	}

	/**
	 * Returns this peer's host identifier ("host:port").
	 */
	public String getHostIdentifier() {
		return this.hostId;
	}

	/**
	 * Returns a binary representation of the peer's IP.
	 */
	public byte[] getRawIp() {
		return this.address.getAddress().getAddress();
	}

	public boolean isServer() {
		return server;
	}
	public void setServer(boolean server) {
		this.server = server;
	}
	public int getServerCompletion() {
		return serverCompletion;
	}
	public void setServerCompletion(int serverCompletion) {
		this.serverCompletion = serverCompletion;
	}
	/**
	 * Returns a human-readable representation of this peer.
	 */
	public String toString() {
		StringBuilder s = new StringBuilder("peer://")
			.append(this.getIp()).append(":").append(this.getPort())
			.append("/");

		if (this.hasPeerId()) {
			s.append(this.hexPeerId.substring(this.hexPeerId.length()-6));
		} else {
			s.append("?");
		}

		return s.toString();
	}

	/**
	 * Tells if two peers seem to look alike (i.e. they have the same IP, port
	 * and peer ID if they have one).
	 */
	public boolean looksLike(Peer other) {
		if (other == null) {
			return false;
		}

		return this.hostId.equals(other.hostId) &&
			(this.hasPeerId()
				 ? this.hexPeerId.equals(other.hexPeerId)
				 : true);
	}
}
