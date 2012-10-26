package com.turn.ttorrent.client.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WriteWorker implements Runnable {
	
	private static final Logger logger =
			LoggerFactory.getLogger(WriteWorker.class);
	
	public Map<SocketChannel, List<byte[]>> pendingData = new HashMap<SocketChannel, List<byte[]>>();
	private List<SelectionKey> queue = new LinkedList<SelectionKey>();

	public WriteWorker() {
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
				try {
					writeData(queue.remove(0));
				} catch (IOException e) {
					logger.error("There was an error writing data to a socket channel", e);
				}
			}
		}
	}
	
	public void processData(SelectionKey key) {
		synchronized(queue) {
			this.queue.add(key);
			this.queue.notify();
		}
	}
	
	private void writeData(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		
		synchronized (this.pendingData) {
			List<byte[]> queue = (List<byte[]>) this.pendingData.get(socketChannel);
			
			while (!queue.isEmpty()) {
				byte[] data = (byte[]) queue.get(0);
				short len = (short) data.length;
				
				// Create 2-bytes to prepend the message with indicating the length
				byte[] lengthBytes = new TwoByteMessageLength().lengthToBytes(len);
				
				// Allocate a byte buffer of the message length, plus the length of the length prefix
				ByteBuffer buf = ByteBuffer.allocate(len+lengthBytes.length);
				buf.position(0);
				buf.put(lengthBytes);
				buf.put(data);
				buf.flip();
				
				if (buf != null) {
					while(buf.remaining() > 0) {
						int bytesWritten;
						SocketChannel channel = (SocketChannel) key.channel();
						synchronized (channel) {
							bytesWritten = channel.write(buf);
						}
						
						if (bytesWritten == -1) {
							System.out.println("No bytes written");
						}
						
						logger.trace("Writing {} bytes to socket channel {}", bytesWritten, socketChannel);
					}
				}
				
				queue.remove(0);
			}
			
			if (queue.isEmpty()) {
				// Set this key back to read after we're done writing
				key.interestOps(SelectionKey.OP_READ);
			}
		}
	}

}
