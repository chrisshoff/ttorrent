package com.turn.ttorrent.client.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.CommunicationListener;
import com.turn.ttorrent.common.Torrent;

public class PeerCommunicationManager extends Thread {
	
	private static final Logger logger =
			LoggerFactory.getLogger(PeerCommunicationManager.class);
	
	public static final int PORT_RANGE_START = 6881;
	public static final int PORT_RANGE_END = 6889;
	
	Selector selector; // Main selector that handles all requests
	ServerSocketChannel serverSocketChannel;
	InetSocketAddress address;
	List<ChangeRequest> changeRequests = new LinkedList<ChangeRequest>();
	List<CommunicationListener> listeners = new ArrayList<CommunicationListener>();
	ByteBuffer stagingBuffer = ByteBuffer.allocate(8192);
	ReadWorker readWorker;
	WriteWorker writeWorker;
	
	public PeerCommunicationManager(InetAddress address)
			throws IOException {
		this.readWorker = new ReadWorker();
		this.writeWorker = new WriteWorker();
		
		// Bind to the first available port in the range
		// [PORT_RANGE_START; PORT_RANGE_END].
		for (int port = PORT_RANGE_START;
				port <= PORT_RANGE_END;
				port++) {
			InetSocketAddress tryAddress =
				new InetSocketAddress(address, port);

			try {
				this.selector = SelectorProvider.provider().openSelector();
				this.serverSocketChannel = ServerSocketChannel.open();
				this.serverSocketChannel.configureBlocking(false);
				this.serverSocketChannel.socket().bind(tryAddress);
				this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
				
				this.address = tryAddress;
				break;
			} catch (IOException ioe) {
				// Ignore, try next port
				logger.warn("Could not bind to {} !", tryAddress);
			}
		}
	}
	
	public static String socketRepr(Socket s) {
		return new StringBuilder(s.getInetAddress().getHostName())
			.append(":").append(s.getPort()).toString();
	}
	
	public void run() {
		while(true) {
			try {
				// Look for pending requests to change a key or socket
				synchronized (this.changeRequests) {
					Iterator<ChangeRequest> changes = this.changeRequests.iterator();
					while (changes.hasNext()) {
						ChangeRequest change = (ChangeRequest) changes.next();
						switch (change.type) {
						case ChangeRequest.CHANGEOPS:
							SelectionKey key = change.socket.keyFor(this.selector);
							key.interestOps(change.ops);
							break;
						case ChangeRequest.REGISTER:
							change.socket.register(this.selector, change.ops, change.additionalData);
							break;
						}
					}
					
					this.changeRequests.clear();
				}
				
				this.selector.select(); // Blocking select call
				
				// We found keys ready for selection
				Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey key = (SelectionKey) selectedKeys.next();
					selectedKeys.remove();
					
					if (!key.isValid()) {
						continue;
					}

					// Determine what to do with the socket channel
					if (key.isConnectable()) {
						this.finishConnection(key);
					} else if (key.isAcceptable()) {
						this.accept(key);
					} else if (key.isReadable()) {
						this.read(key);
					} else if (key.isWritable()) {
						this.write(key);
					}
				}
			} catch (IOException e) {
				logger.error("The NIO selector threw an exception", e);
			}
		}
	}

	private void write(SelectionKey key) throws IOException {
		this.writeWorker.processData(key);
	}

	private void read(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		
		this.stagingBuffer.clear();
    	
		int numRead;
		try {
			numRead = socketChannel.read(this.stagingBuffer);
		} catch (IOException e) {
			key.cancel();
			socketChannel.close();
			return;
		}
		
		if (numRead == -1) {
			key.channel().close();
			key.cancel();
			return;
		}
		
		logger.trace("Reading {} bytes from socket channel {}", numRead, socketChannel);
		
		this.readWorker.processData(this, socketChannel, this.stagingBuffer.array(), numRead, key);
		
	}

	private void accept(SelectionKey key) throws IOException {
		// Accept a new connection - set up the resulting socket channel for read
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
		SocketChannel socketChannel = serverSocketChannel.accept();
		socketChannel.configureBlocking(false);
		socketChannel.register(this.selector, SelectionKey.OP_READ);
	}

	private void finishConnection(SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		  
	    // Finish the connection. If the connection operation failed
	    // this will raise an IOException.
	    try {
	      socketChannel.finishConnect();
	    } catch (IOException e) {
	      // Cancel the channel's registration with our selector
	      key.cancel();
	      return;
	    }
	    
	    // Let our listeners know we've completed a connection
	    fireNewConnectionListeners(socketChannel, (String) key.attachment());
	}
	
	private void fireNewConnectionListeners(SocketChannel socketChannel, String hexInfoHash) {
		for (CommunicationListener listener : this.listeners) {
			listener.handleNewConnection(socketChannel, hexInfoHash);
		}
	}
	
	public void fireNewDataListeners(SocketChannel socketChannel, List<ByteBuffer> data) {
		for (CommunicationListener listener : this.listeners) {
			listener.handleNewData(socketChannel, data);
		}
	}
	
	public void register(CommunicationListener listener) {
		this.listeners.add(listener);
	}
	
	public InetSocketAddress getAddress() {
		return this.address;
	}

	public SocketChannel connect(InetAddress address, int port, byte[] infoHash) throws IOException {
		logger.trace("Initiating connection with {}", address.toString() + ":" + port);
		SocketChannel socketChannel = SocketChannel.open();
		socketChannel.configureBlocking(false);
		socketChannel.connect(new InetSocketAddress(address, port));
		
		// We can't directly change the key, so set up a change request
		synchronized(this.changeRequests) {
			this.changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT, Torrent.byteArrayToHexString(infoHash)));
		}
		
		this.selector.wakeup();
		
		return socketChannel;
	}
	
	public void send(SocketChannel socketChannel, byte[] data) {
		// We can't directly set a socket to write, so set up a change request
		synchronized(this.changeRequests) {
			this.changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));
			
			// Put the data to be written in the pending data list
			synchronized (this.writeWorker.pendingData) {
				List<byte[]> queue = this.writeWorker.pendingData.get(socketChannel);
				if (queue == null) {
					queue = new ArrayList<byte[]>();
					this.writeWorker.pendingData.put(socketChannel, queue);
				}
				queue.add(data);
			}
		}
		
		this.selector.wakeup();
	}
}
