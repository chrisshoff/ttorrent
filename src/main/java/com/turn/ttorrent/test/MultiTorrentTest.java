package com.turn.ttorrent.test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.turn.ttorrent.client.ClientSharedTorrent;
import com.turn.ttorrent.client.MultiTorrentClient;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;


public class MultiTorrentTest {
	
	private static String ROOT_FILE_DIR = "/Users/cshoff/Desktop/torrent/";
	
	private static String CLIENT_TYPE = "MULTI";
	
	private static List<String> torrents = Arrays.asList("file1");

	/**
	 * @param args
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InterruptedException 
	 */
	public static void main(String[] args) throws IOException, NoSuchAlgorithmException, InterruptedException {
		
		// Clean up client 2 folder
		File client2Folder = new File(ROOT_FILE_DIR + "client2");
		for (File file : client2Folder.listFiles()) {
			file.delete();
		}
		
		// Clean up client 3 folder
		File client3Folder = new File(ROOT_FILE_DIR + "client3");
		for (File file : client3Folder.listFiles()) {
			file.delete();
		}
		
		// Clean up client 4 folder
		File client4Folder = new File(ROOT_FILE_DIR + "client4");
		for (File file : client4Folder.listFiles()) {
			file.delete();
		}
		
		// Clean up client 5 folder
		File client5Folder = new File(ROOT_FILE_DIR + "client5");
		for (File file : client5Folder.listFiles()) {
			file.delete();
		}
		
		// Clean up client 6 folder
		File client6Folder = new File(ROOT_FILE_DIR + "client6");
		for (File file : client6Folder.listFiles()) {
			file.delete();
		}
		
		Tracker tracker = new Tracker(new InetSocketAddress(6969));
		
		for (String torrent : torrents) { 
			tracker.announce(TrackedTorrent.load(new File(ROOT_FILE_DIR + torrent + ".torrent")));
		}
		
		tracker.start();
		
		List<ClientSharedTorrent> csts1 = new ArrayList<ClientSharedTorrent>();
		List<ClientSharedTorrent> csts2 = new ArrayList<ClientSharedTorrent>();
		/*List<ClientSharedTorrent> csts3 = new ArrayList<ClientSharedTorrent>();
		/*List<ClientSharedTorrent> csts4 = new ArrayList<ClientSharedTorrent>();
		List<ClientSharedTorrent> csts5 = new ArrayList<ClientSharedTorrent>();
		List<ClientSharedTorrent> csts6 = new ArrayList<ClientSharedTorrent>();*/
		
		for (String torrent : torrents) {
			ClientSharedTorrent cst1 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + torrent + ".torrent"), 
					new File(ROOT_FILE_DIR + "client1"), true);
			csts1.add(cst1);
			ClientSharedTorrent cst2 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + torrent + ".torrent"), 
					new File(ROOT_FILE_DIR + "client2"), true);
			csts2.add(cst2);
			/*ClientSharedTorrent cst3 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + torrent + ".torrent"), 
					new File(ROOT_FILE_DIR + "client3"), true);
			cst3.setIntid(3);
			csts3.add(cst3);
			/*csts4.add(ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + torrent + ".torrent"), 
					new File(ROOT_FILE_DIR + "client4"), true));
			csts5.add(ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + torrent + ".torrent"), 
					new File(ROOT_FILE_DIR + "client5"), true));
			csts6.add(ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + torrent + ".torrent"), 
					new File(ROOT_FILE_DIR + "client6"), true));*/
		}

		MultiTorrentClient client1 = new MultiTorrentClient(InetAddress.getLocalHost());
		for (ClientSharedTorrent torrent : csts1) {
			torrent.init();
			client1.addTorrent(torrent);
			client1.share(torrent.getHexInfoHash());
		}
		
		MultiTorrentClient client2 = new MultiTorrentClient(InetAddress.getLocalHost());
		for (ClientSharedTorrent torrent : csts2) {
			torrent.init();
			client2.addTorrent(torrent);
			client2.share(torrent.getHexInfoHash());
		}
		
		/*MultiTorrentClient client3 = new MultiTorrentClient(InetAddress.getLocalHost(), true);
		client3.setIntid(3);
		for (ClientSharedTorrent torrent : csts3) {
			torrent.init();
			client3.addTorrent(torrent);
			client3.share(torrent.getHexInfoHash());
		}
		
		/*MultiTorrentClient client4 = new MultiTorrentClient(InetAddress.getLocalHost(), true);
		for (ClientSharedTorrent torrent : csts4) {
			torrent.init();
			client4.addTorrent(torrent);
			client4.share(torrent.getHexInfoHash());
		}
		
		MultiTorrentClient client5 = new MultiTorrentClient(InetAddress.getLocalHost(), true);
		for (ClientSharedTorrent torrent : csts5) {
			torrent.init();
			client5.addTorrent(torrent);
			client5.share(torrent.getHexInfoHash());
		}
		
		MultiTorrentClient client6 = new MultiTorrentClient(InetAddress.getLocalHost(), true);
		for (ClientSharedTorrent torrent : csts6) {
			torrent.init();
			client6.addTorrent(torrent);
			client5.share(torrent.getHexInfoHash());
		}*/
	}

}
