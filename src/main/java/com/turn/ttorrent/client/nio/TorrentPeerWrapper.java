package com.turn.ttorrent.client.nio;

import com.turn.ttorrent.client.peer.SharingPeer;

public class TorrentPeerWrapper {
	public SharingPeer peer;
	public String torrentInfoHash;
	
	public TorrentPeerWrapper(String torrentInfoHash) {
		this.peer = null;
		this.torrentInfoHash = torrentInfoHash;
	}
	
	public TorrentPeerWrapper(SharingPeer peer, String torrentInfoHash) {
		this.peer = peer;
		this.torrentInfoHash = torrentInfoHash;
	}
}