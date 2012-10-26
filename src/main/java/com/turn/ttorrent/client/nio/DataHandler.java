package com.turn.ttorrent.client.nio;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.Callable;

import com.turn.ttorrent.client.CommunicationListener;

public class DataHandler implements Callable {
	
	List<CommunicationListener> listeners;
	SocketChannel sc;
	List<ByteBuffer> data;
	
	public DataHandler(List<CommunicationListener> listeners, SocketChannel sc, List<ByteBuffer> data) {
		this.listeners = listeners;
		this.sc = sc;
		this.data = data;
	}

	@Override
	public Object call() throws Exception {
		for (CommunicationListener listener : this.listeners) {
			listener.handleNewData(sc, data);
		}
		
		return null;
	}

}
