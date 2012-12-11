/**
 * Copyright (C) 2012 Turn, Inc.
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
package com.turn.ttorrent.client.announce;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.ClientSharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerMessage;

/**
 * Announcer for HTTP trackers.
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Request_Parameters">BitTorrent tracker request specification</a>
 */
public class MultiTorrentHTTPTrackerClient extends MultiTorrentTrackerClient {
	
	protected boolean server;

	protected static final Logger logger =
		LoggerFactory.getLogger(MultiTorrentHTTPTrackerClient.class);

	/**
	 * Create a new HTTP announcer for the given torrent.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param peer Our own peer specification.
	 */
	protected MultiTorrentHTTPTrackerClient(Peer peer, boolean server) {
		super(peer);
		this.server = server;
	}

	/**
	 * Build, send and process a tracker announce request.
	 *
	 * <p>
	 * This function first builds an announce request for the specified event
	 * with all the required parameters. Then, the request is made to the
	 * tracker and the response analyzed.
	 * </p>
	 *
	 * <p>
	 * All registered {@link AnnounceResponseListener} objects are then fired
	 * with the decoded payload.
	 * </p>
	 *
	 * @param event The announce event type (can be AnnounceEvent.NONE for
	 * periodic updates).
	 * @param inhibitEvents Prevent event listeners from being notified.
	 */
	@Override
	public void announce(AnnounceRequestMessage.RequestEvent event,
		boolean inhibitEvents) throws AnnounceException {

		URLConnection conn = null;

		try {
			List<HTTPAnnounceRequestMessage> requests =
				this.buildAnnounceRequests(event);

			// Send announce request (HTTP GET)
			for (HTTPAnnounceRequestMessage request : requests) {
				URL target = request.buildAnnounceURL(this.tracker.toURL());
				conn = target.openConnection();
	
				InputStream is = new AutoCloseInputStream(conn.getInputStream());
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				baos.write(is);
	
				// Parse and handle the response
				HTTPTrackerMessage message =
					HTTPTrackerMessage.parse(ByteBuffer.wrap(baos.toByteArray()));
				this.handleTrackerAnnounceResponse(message, inhibitEvents, request.getHexInfoHash());
				
				if (this.server) {
					// This is the server - we don't want to make it keep announcing if it doesn't need to
					ClientSharedTorrent torrent = request.getTorrent();
					if (torrent.getAnnounces() == 0) {
						// This torrent has been announced as much as it needs to - remove it
						this.torrents.remove(request.getTorrent().getId());
					} else {
						// Decrease the announce count 
						this.torrents.get(request.getTorrent().getId()).setAnnounces(torrent.getAnnounces() - 1);
					}
				}
			}
		} catch (MalformedURLException mue) {
			throw new AnnounceException("Invalid announce URL (" +
				mue.getMessage() + ")", mue);
		} catch (MessageValidationException mve) {
			throw new AnnounceException("Tracker message violates expected " +
				"protocol (" + mve.getMessage() + ")", mve);
		} catch (IOException ioe) {
			throw new AnnounceException(ioe.getMessage(), ioe);
		} finally {
			if (conn != null && conn instanceof HttpURLConnection) {
				InputStream err = ((HttpURLConnection) conn).getErrorStream();
				if (err != null) {
					try {
						err.close();
					} catch (IOException ioe) {
						logger.warn("Problem ensuring error stream closed!", ioe);
					}
				}
			}
		}
	}

	/**
	 * Build the announce request tracker message.
	 *
	 * @param event The announce event (can be <tt>NONE</tt> or <em>null</em>)
	 * @return Returns an instance of a {@link HTTPAnnounceRequestMessage}
	 * that can be used to generate the fully qualified announce URL, with
	 * parameters, to make the announce request.
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 * @throws MessageValidationException
	 * 
	 */
	private synchronized List<HTTPAnnounceRequestMessage> buildAnnounceRequests(
		AnnounceRequestMessage.RequestEvent event)
		throws UnsupportedEncodingException, IOException,
			MessageValidationException {
		// Build announce request messages
		List<HTTPAnnounceRequestMessage> messages = new ArrayList<HTTPAnnounceRequestMessage>();
		for (ClientSharedTorrent torrent : this.torrents.values()) {
			messages.add(
					HTTPAnnounceRequestMessage.craft(
					torrent,
					torrent.getInfoHash(),
					this.peer.getPeerId().array(),
					this.peer.getPort(),
					torrent.getUploaded(),
					torrent.getDownloaded(),
					torrent.getLeft(),
					true, false, event,
					this.peer.getIp(),
					AnnounceRequestMessage.DEFAULT_NUM_WANT)
					);
		}
		
		return messages;
	}

	public boolean isServer() {
		return server;
	}

	public void setServer(boolean server) {
		this.server = server;
	}
}
