package com.turn.ttorrent.client.nio;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReadWorker implements Runnable {
	
	private static final Logger logger =
			LoggerFactory.getLogger(ReadWorker.class);
	
	private List<ServerDataEvent> queue = new LinkedList<ServerDataEvent>();
	private final Map<SelectionKey, ByteBuffer> readBuffers = new HashMap<SelectionKey, ByteBuffer>();
	private static int DEFAULT_BUFFER_SIZE = 1024 * 30;
	private ExecutorService executor = Executors.newCachedThreadPool();

	public ReadWorker() {
		new Thread(this).start();
	}
	
	@Override
	public void run() {
		while (true) {
			synchronized (queue) {
				while (queue.isEmpty()) {
					try {
						queue.wait();
					} catch (InterruptedException e) {
					}
				}
				
				// Handle next item in queue
				handleData(queue.remove(0));
			}
		}
	}
	
	public void processData(PeerCommunicationManager server, SocketChannel socket, byte[] data, int count, SelectionKey key) {
		byte[] dataCopy = new byte[count];
		System.arraycopy(data, 0, dataCopy, 0, count);
		synchronized (queue) {
			queue.add(new ServerDataEvent(server, socket, dataCopy, key));
			queue.notify();
		}
	}
	
	private void handleData(ServerDataEvent serverDataEvent) {
		ByteBuffer readBuffer = readBuffers.get(serverDataEvent.key); 
		
		if (readBuffer==null) {
    		// Create a read buffer for this key at the default buffer size
    		readBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE); 
    		readBuffers.put(serverDataEvent.key, readBuffer); 
    	}
		
		try {
			readBuffer.put(serverDataEvent.data);
		} catch (BufferOverflowException e) {
			logger.error("There was a problem reading data into the staging buffer", e);
		}
		
		readBuffer.flip();
		List<ByteBuffer> result = new ArrayList<ByteBuffer>();
		
		// We read data from the socket, now see if we can parse one or more useful messages out of it
		ByteBuffer msg = readMessage(serverDataEvent.key, readBuffer);
		while (msg != null) {
			result.add(msg);
			msg = readMessage(serverDataEvent.key, readBuffer);
		}
		
		try {
			serverDataEvent.addResult(result).call();
		} catch (Exception e) {
			logger.error("There was a problem handling the message", e);
		}
	}
	
	private ByteBuffer readMessage(SelectionKey key, ByteBuffer readBuffer) {
		int bytesToRead;
		
		// The length of the expected length prefix
		TwoByteMessageLength messageLength = new TwoByteMessageLength();
		
		// Need at least enough to read the message length
		if (readBuffer.remaining() >= messageLength.byteLength()) {
			byte[] lengthBytes = new byte[messageLength.byteLength()];
			readBuffer.get(lengthBytes);
			bytesToRead = (int)messageLength.bytesToLength(lengthBytes); // This is the message size
			logger.trace("Expected bytes to read: " + bytesToRead);
			if (readBuffer.limit() - readBuffer.position() < bytesToRead) {
				// Not enough data - prepare for writing again
				if (readBuffer.limit() == readBuffer.capacity()) {
					// Message may be longer than buffer => resize buffer to message size
					int oldCapacity = readBuffer.capacity();
					ByteBuffer tmp = ByteBuffer.allocate(bytesToRead+messageLength.byteLength());
					readBuffer.position(0);
					tmp.put(readBuffer);
					readBuffer = tmp;
					readBuffer.position(oldCapacity);
					readBuffer.limit(readBuffer.capacity());
					readBuffers.put(key, readBuffer);
					logger.trace("Buffer needed expanded: " + bytesToRead);
					return null;
				} else {
					// Reset for writing
					readBuffer.position(readBuffer.limit());
					readBuffer.limit(readBuffer.capacity());
					logger.trace("Not enough data, prepare for writing again. Current buffer: " + readBuffer);
					return null;
				}
			}
		} else {
			// Not enough data - prepare for writing again
			readBuffer.position(readBuffer.limit());
			readBuffer.limit(readBuffer.capacity());
			logger.trace("Not enough data, prepare for writing again. Current buffer: " + readBuffer);
			return null;
		}
		
		byte[] resultMessage = new byte[bytesToRead];
		readBuffer.get(resultMessage, 0, bytesToRead);
		
		logger.trace("Message successfully read. Result size: " + resultMessage.length);
		
		// Remove read message from buffer
		int remaining = readBuffer.remaining();
		readBuffer.limit(readBuffer.capacity());
		readBuffer.compact();
		readBuffer.position(0);
		readBuffer.limit(remaining);
		return ByteBuffer.wrap(resultMessage);
	}
	
	private class ServerDataEvent implements Callable {
		
		public final PeerCommunicationManager server;
		public final SocketChannel socketChannel;
		public final byte[] data;
		public final SelectionKey key;
		public List<ByteBuffer> result;
		
		public ServerDataEvent(PeerCommunicationManager server, SocketChannel socketChannel, byte[] data, SelectionKey key) {
			this.server = server;
			this.socketChannel = socketChannel;
			this.data = data;
			this.key = key;
		}
		
		public ServerDataEvent addResult(List<ByteBuffer> result) {
			this.result = result;
			return this;
		}

		@Override
		public Object call() throws Exception {
			this.server.fireNewDataListeners(this.socketChannel, this.result);
			return null;
		}
	}

}
