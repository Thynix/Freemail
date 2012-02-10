/*
 * Channel.java
 * This file is part of Freemail
 * Copyright (C) 2006,2007,2008 Dave Baker
 * Copyright (C) 2007 Alexander Lehmann
 * Copyright (C) 2009 Martin Nyhus
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package freemail.transport;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.archive.util.Base32;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.params.RSAKeyParameters;

import freemail.AccountManager;
import freemail.Freemail;
import freemail.FreemailAccount;
import freemail.FreemailPlugin;
import freemail.FreenetURI;
import freemail.SlotManager;
import freemail.SlotSaveCallback;
import freemail.FreemailPlugin.TaskType;
import freemail.fcp.ConnectionTerminatedException;
import freemail.fcp.FCPBadFileException;
import freemail.fcp.FCPException;
import freemail.fcp.FCPFetchException;
import freemail.fcp.FCPPutFailedException;
import freemail.fcp.HighLevelFCPClient;
import freemail.fcp.SSKKeyPair;
import freemail.utils.DateStringFactory;
import freemail.utils.Logger;
import freemail.utils.PropsFile;
import freemail.wot.Identity;
import freemail.wot.WoTConnection;
import freemail.wot.WoTProperties;
import freenet.keys.InsertableClientSSK;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;

//FIXME: The message id gives away how many messages has been sent over the channel.
//       Could it be replaced by a different solution that gives away less information?
class Channel {
	private static final String CHANNEL_PROPS_NAME = "props";
	private static final int POLL_AHEAD = 6;
	private static final String ACK_LOG = "acklog";
	private static final long MAX_ACK_DELAY = 12 * 60 * 60 * 1000; //12 hours

	/**
	 * The amount of time before the channel times out, in milliseconds. If the channel is created
	 * at t=0, then messages won't be queued after t=CHANNEL_TIMEOUT, and the fetcher will stop
	 * when t=2*CHANNEL_TIMEOUT. This should provide enough delay for the recipient to fetch all
	 * the messages.
	 */
	private static final long CHANNEL_TIMEOUT = 7 * 24 * 60 * 60 * 1000; //1 week

	/** The amount of time to wait before retrying after a transient failure. */
	private static final long TASK_RETRY_DELAY = 5 * 60 * 1000; //5 minutes

	//The keys used in the props file
	private static class PropsKeys {
		private static final String PRIVATE_KEY = "privateKey";
		private static final String PUBLIC_KEY = "publicKey";
		private static final String FETCH_SLOT = "fetchSlot";
		private static final String SEND_SLOT = "sendSlot";
		private static final String SENDER_STATE = "sender-state";
		private static final String RECIPIENT_STATE = "recipient-state";
		private static final String RTS_SENT_AT = "rts-sent-at";
		private static final String SEND_CODE = "sendCode";
		private static final String FETCH_CODE = "fetchCode";
		private static final String REMOTE_ID = "remoteID";
		private static final String TIMEOUT = "timeout";
		private static final String MSG_SLOT = ".slot";
	}

	private static class RTSKeys {
		private static final String MAILSITE = "mailsite";
		private static final String TO = "to";
		private static final String CHANNEL = "channel";
		private static final String INITIATOR_SLOT = "initiatorSlot";
		private static final String RESPONDER_SLOT = "responderSlot";
		private static final String TIMEOUT = "timeout";
	}

	private final File channelDir;
	private final PropsFile channelProps;
	private final ScheduledExecutorService executor;
	private final HighLevelFCPClient fcpClient;
	private final Freemail freemail;
	private final FreemailAccount account;
	private final Fetcher fetcher = new Fetcher();
	private final RTSSender rtsSender = new RTSSender();
	private final AtomicReference<ChannelEventCallback> channelEventCallback = new AtomicReference<ChannelEventCallback>();
	private final MessageLog ackLog;

	Channel(File channelDir, ScheduledExecutorService executor, HighLevelFCPClient fcpClient, Freemail freemail, FreemailAccount account) throws ChannelTimedOutException {
		if(executor == null) throw new NullPointerException();
		this.executor = executor;

		this.fcpClient = fcpClient;
		this.account = account;

		if(freemail == null) throw new NullPointerException();
		this.freemail = freemail;

		assert channelDir.isDirectory();
		this.channelDir = channelDir;

		ackLog = new MessageLog(new File(channelDir, ACK_LOG));

		File channelPropsFile = new File(channelDir, CHANNEL_PROPS_NAME);
		if(!channelPropsFile.exists()) {
			try {
				if(!channelPropsFile.createNewFile()) {
					Logger.error(this, "Could not create new props file in " + channelDir);
				}
			} catch(IOException e) {
				Logger.error(this, "Could not create new props file in " + channelDir);
			}
		}
		channelProps = PropsFile.createPropsFile(channelPropsFile);

		//Check if the channel has timed out
		synchronized(channelProps) {
			String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
			if(rawTimeout != null) {
				try {
					long timeout = Long.parseLong(rawTimeout);
					if(timeout < (System.currentTimeMillis() - CHANNEL_TIMEOUT)) {
						Logger.debug(this, "Channel has timed out");
						throw new ChannelTimedOutException();
					}
				} catch(NumberFormatException e) {
					Logger.error(this, "Illegal value in " + PropsKeys.TIMEOUT + " field, assuming timed out: " + rawTimeout);
					throw new ChannelTimedOutException();
				}
			} else {
				//If the value doesn't exist it is probably because we haven't sent the RTS yet
				Logger.debug(this, "Adding timeout=Long.MAX_VALUE to channel props");
				channelProps.put(PropsKeys.TIMEOUT, Long.MAX_VALUE);
			}
		}
	}

	void processRTS(PropsFile rtsProps) {
		Logger.debug(this, "Processing RTS");

		synchronized(channelProps) {
			//Because of the way InsertableClientSSK works we need to add a document name (the part
			//after the final /) to the key before it is passed to FreenetURI. This must be removed
			//again when we store the keys to the props file
			String privateKey = "";
			String publicKey = "";
			try {
				final String documentName = "documentName";

				freenet.keys.FreenetURI privateURI = new freenet.keys.FreenetURI(rtsProps.get(RTSKeys.CHANNEL));
				privateURI = privateURI.setDocName(documentName);

				InsertableClientSSK insertableKey = InsertableClientSSK.create(privateURI);

				privateKey = insertableKey.getInsertURI().setDocName("").toString();
				publicKey = insertableKey.getURI().setDocName("").toString();
			} catch(MalformedURLException e) {
				Logger.debug(this, "RTS contained malformed private key: " + rtsProps.get(RTSKeys.CHANNEL));
				return;
			}

			if(channelProps.get(PropsKeys.RECIPIENT_STATE) != null) {
				Logger.debug(this, "Skipping RTS processing because recipient state isn't null");
				return;
			}

			if(channelProps.get(PropsKeys.PRIVATE_KEY) == null) {
				channelProps.put(PropsKeys.PRIVATE_KEY, privateKey);
				channelProps.put(PropsKeys.PUBLIC_KEY, publicKey);
			}

			channelProps.put(PropsKeys.FETCH_SLOT, rtsProps.get(RTSKeys.INITIATOR_SLOT));
			channelProps.put(PropsKeys.FETCH_CODE, "i");

			if(channelProps.get(PropsKeys.SEND_CODE) == null) {
				channelProps.put(PropsKeys.SEND_CODE, "r");
			}

			if(channelProps.get(PropsKeys.SEND_SLOT) == null) {
				channelProps.put(PropsKeys.SEND_SLOT, rtsProps.get(RTSKeys.RESPONDER_SLOT));
			}

			channelProps.put(PropsKeys.TIMEOUT, rtsProps.get("timeout"));
			channelProps.put(PropsKeys.RECIPIENT_STATE, "rts-received");
		}

		//Queue the CTS insert
		try {
			executor.execute(new CTSInserter());
		} catch(RejectedExecutionException e) {
			Logger.debug(this, "Caugth RejectedExecutionException while scheduling CTSInserter");
		}
		startFetcher();
	}

	void setRemoteIdentity(String remoteID) {
		synchronized(channelProps) {
			channelProps.put(PropsKeys.REMOTE_ID, remoteID);
		}
	}

	void setCallback(ChannelEventCallback callback) {
		//At the moment we only need to set the callback after creating the
		//channel, and the rest of the code hasn't been checked to make sure
		//it can handle a change, so be strict about it.
		if(!channelEventCallback.compareAndSet(null, callback)) {
			throw new IllegalStateException("Callback has already been set");
		}
	}

	public static boolean deleteChannel(File channelDir) {
		File channelPropsFile = new File(channelDir, CHANNEL_PROPS_NAME);
		channelPropsFile.delete();

		return channelDir.delete();
	}

	private class CTSInserter implements Runnable {
		@Override
		public void run() {
			Logger.debug(this, "CTSInserter running (" + this + ")");

			//Build the header of the inserted message
			Bucket bucket = new ArrayBucket("messagetype=cts\r\n\r\n".getBytes());

			boolean inserted;
			try {
				inserted = insertMessage(bucket, "cts");
			} catch(IOException e) {
				//The getInputStream() method of ArrayBucket doesn't throw
				throw new AssertionError();
			} catch (InterruptedException e) {
				Logger.debug(this, "CTSInserter (" + this + ") interrupted, quitting");
				return;
			}

			if(inserted) {
				synchronized(channelProps) {
					channelProps.put(PropsKeys.RECIPIENT_STATE, "cts-sent");
				}
			} else {
				try {
					executor.schedule(this, TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				} catch(RejectedExecutionException e) {
					Logger.debug(this, "Caugth RejectedExecutionException while scheduling CTSInserter");
				}
			}
		}
	}

	void startTasks() {
		startFetcher();
		startRTSSender();

		//Start insert of acks that were written to disk but not inserted
		try {
			synchronized(ackLog) {
				Iterator<Entry<Long, String>> it = ackLog.iterator();
				while(it.hasNext()) {
					Entry<Long, String> entry = it.next();
					long insertAfter = 0;
					if(entry.getValue() != null) {
						try {
							insertAfter = Long.parseLong(entry.getValue());
						} catch(NumberFormatException e) {
							//Assume no delay
							insertAfter = 0;
						}
					}

					ScheduledExecutorService senderExecutor = FreemailPlugin.getExecutor(TaskType.SENDER);
					senderExecutor.execute(new AckInserter(entry.getKey(), insertAfter));
				}
			}
		} catch(IOException e) {
			Logger.error(this, "Caugth IOException while checking acklog: " + e);
		} catch(RejectedExecutionException e) {
			// Catch it here instead of inside the loop since there is no point
			// in trying to schedule the rest
			Logger.debug(this, "Caugth RejectedExecutionException while scheduling AckInserter");
		}

		//Start the CTS sender if needed
		synchronized(channelProps) {
			String recipientState = channelProps.get(PropsKeys.RECIPIENT_STATE);
			if("rts-received".equals(recipientState)) {
				try {
					executor.execute(new CTSInserter());
				} catch(RejectedExecutionException e) {
					Logger.debug(this, "Caugth RejectedExecutionException while scheduling CTSInserter");
				}
			}
		}
	}

	private void startFetcher() {
		//Start fetcher if possible
		String fetchSlot;
		String fetchCode;
		String publicKey;
		synchronized(channelProps) {
			fetchSlot = channelProps.get(PropsKeys.FETCH_SLOT);
			fetchCode = channelProps.get(PropsKeys.FETCH_CODE);
			publicKey = channelProps.get(PropsKeys.PUBLIC_KEY);
		}

		if((fetchSlot != null) && (fetchCode != null) && (publicKey != null)) {
			fetcher.execute();
		}
	}

	private void startRTSSender() {
		String state;
		synchronized(channelProps) {
			state = channelProps.get(PropsKeys.SENDER_STATE);
		}

		if((state != null) && state.equals("cts-received")) {
			return;
		}

		rtsSender.execute();
	}

	/**
	 * Sends the message that is read from {@code message}, returning {@code true} if the message
	 * was inserted. The caller is responsible for freeing {@code message}.
	 * @param message the data to be sent
	 * @param messageId the message id that has been assigned to this message
	 * @return {@code true} if the message was sent, {@code false} otherwise
	 * @throws ChannelTimedOutException if the channel has timed out and can't be used for sending
	 *             messages
	 * @throws IOException if any operations on {@code message} throws IOException
	 * @throws InterruptedException if the current thread was interrupted while sending the message
	 * @throws NullPointerException if {@code message} is {@code null}
	 */
	boolean sendMessage(Bucket message, long messageId) throws ChannelTimedOutException, IOException, InterruptedException {
		if(message == null) throw new NullPointerException("Parameter message was null");

		synchronized(channelProps) {
			String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
			if(rawTimeout != null) {
				long timeout;
				try {
					timeout = Long.parseLong(rawTimeout);
				} catch(NumberFormatException e) {
					timeout = 0;
				}

				if(timeout < System.currentTimeMillis()) {
					throw new ChannelTimedOutException();
				}
			}
		}

		//Build the header of the inserted message
		Bucket messageHeader = new ArrayBucket(
				("messagetype=message\r\n" +
				"id=" + messageId + "\r\n" +
				"\r\n").getBytes());

		//Now combine them in a single bucket
		ArrayBucket fullMessage = new ArrayBucket();
		OutputStream messageOutputStream = null;
		try {
			messageOutputStream = fullMessage.getOutputStream();
			BucketTools.copyTo(messageHeader, messageOutputStream, -1);
			BucketTools.copyTo(message, messageOutputStream, -1);
		} finally {
			Closer.close(messageOutputStream);
		}

		return insertMessage(fullMessage, "msg" + messageId);
	}

	/**
	 * Inserts the given message to the next available slot, returning {@code true} if the message
	 * was inserted, {@code false} otherwise.
	 * @param message the message that should be inserted
	 * @return {@code true} if the message was inserted, {@code false} otherwise
	 * @throws IOException if the getInputStream() method of message throws IOException
	 * @throws InterruptedException if the current thread was interrupted while the message was being inserted
	 */
	private boolean insertMessage(Bucket message, String prefix) throws IOException, InterruptedException {
		String privateKey;
		String sendCode;
		synchronized (channelProps) {
			privateKey = channelProps.get(PropsKeys.PRIVATE_KEY);
			sendCode = channelProps.get(PropsKeys.SEND_CODE);

			if(privateKey == null) {
				Logger.debug(this, "Can't insert, missing private key");
				return false;
			}
			if(sendCode == null) {
				Logger.debug(this, "Can't insert, missing send code");
				return false;
			}
		}

		while(true) {
			/* First we get the slot for this message if one has been assigned */
			String sendSlot;
			synchronized(channelProps) {
				/* If a slot has been assigned, use it */
				sendSlot = channelProps.get(prefix + PropsKeys.MSG_SLOT);
				if(sendSlot == null) {
					/* If not, assign the next free slot */
					sendSlot = channelProps.get(PropsKeys.SEND_SLOT);
					String nextSlot = calculateNextSlot(sendSlot);
					channelProps.put(PropsKeys.SEND_SLOT, nextSlot);
					channelProps.put(prefix + PropsKeys.MSG_SLOT, sendSlot);

					Logger.debug(this, "Assigned slot " + sendSlot + " to message " + prefix);
				}
			}

			String insertKey = privateKey + sendCode + "-" + sendSlot;

			InputStream messageStream = null;
			try {
				messageStream = message.getInputStream();
				Logger.debug(this, "Inserting data to " + insertKey);
				FCPPutFailedException fcpMessage;
				try {
					long start = System.nanoTime();
					fcpMessage = fcpClient.put(messageStream, insertKey);
					long end = System.nanoTime();
					Logger.debug(this, "Message insert took " + (end - start) + "ns");
				} catch(FCPBadFileException e) {
					Logger.debug(this, "Caugth " + e);
					return false;
				} catch(ConnectionTerminatedException e) {
					Logger.debug(this, "Caugth " + e);
					return false;
				} catch (FCPException e) {
					Logger.error(this, "Unknown error while inserting message", e);
					return false;
				}

				if(fcpMessage == null) {
					Logger.debug(this, "Insert successful");

					synchronized (channelProps) {
						if(!channelProps.remove(prefix + PropsKeys.MSG_SLOT)) {
							Logger.error(this, "Couldn't remove slot, will try again later");

							/*
							 * The insert succeeded, but we can't leave the slot in the props file
							 * since that would break the forward secrecy of the slot system. By
							 * returning false we will try again later (using the same slot) and
							 * hopefully we can delete it then.
							 */
							return false;
						}
					}

					return true;
				}

				if(fcpMessage.errorcode == FCPPutFailedException.COLLISION) {
					synchronized(channelProps) {
						sendSlot = channelProps.get(PropsKeys.SEND_SLOT);
						String nextSlot = calculateNextSlot(sendSlot);
						channelProps.put(PropsKeys.SEND_SLOT, nextSlot);
						channelProps.put(prefix + PropsKeys.MSG_SLOT, sendSlot);
					}

					Logger.debug(this, "Insert collided, assigned slot " + sendSlot + " to message " + prefix);
				}

				Logger.debug(this, "Insert failed, error code " + fcpMessage.errorcode);
				return false;
			} finally {
				Closer.close(messageStream);
			}
		}
	}

	@Override
	public String toString() {
		return "Channel [" + channelDir + "]";
	}

	String getRemoteIdentity() {
		synchronized(channelProps) {
			return channelProps.get(PropsKeys.REMOTE_ID);
		}
	}

	String getPrivateKey() {
		synchronized(channelProps) {
			return channelProps.get(PropsKeys.PRIVATE_KEY);
		}
	}

	boolean canSendMessages() {
		synchronized(channelProps) {
			String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
			long timeout;
			try {
				timeout = Long.parseLong(rawTimeout);
			} catch(NumberFormatException e) {
				Logger.debug(this, "Returning false from canSendMessages(), parse error: " + rawTimeout);
				return false;
			}

			return timeout >= System.currentTimeMillis();
		}
	}

	private String calculateNextSlot(String slot) {
		byte[] buf = Base32.decode(slot);
		SHA256Digest sha256 = new SHA256Digest();
		sha256.update(buf, 0, buf.length);
		sha256.doFinal(buf, 0);

		return Base32.encode(buf);
	}

	private String generateRandomSlot() {
		SHA256Digest sha256 = new SHA256Digest();
		byte[] buf = new byte[sha256.getDigestSize()];
		SecureRandom rnd = new SecureRandom();
		rnd.nextBytes(buf);
		return Base32.encode(buf);
	}

	private class Fetcher implements Runnable {
		private final AtomicLong lastRun = new AtomicLong();

		@Override
		public synchronized void run() {
			long curTime = System.currentTimeMillis();
			long last = lastRun.getAndSet(curTime);
			if(last != 0) {
				Logger.debug(this, "Fetcher running (" + this + "), last ran " + (curTime - last) + "ms ago");
			} else {
				Logger.debug(this, "Fetcher running (" + this + ")");
			}

			try {
				realRun();
			} catch(RuntimeException e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			} catch(Error e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			} catch (InterruptedException e) {
				Logger.debug(this, "Fetcher (" + this + ") interrupted, quitting");
				return;
			}
		}

		private void realRun() throws InterruptedException {
			synchronized(channelProps) {
				String rawTimeout = channelProps.get(PropsKeys.TIMEOUT);
				long timeout;
				try {
					timeout = Long.parseLong(rawTimeout);
				} catch(NumberFormatException e) {
					//Assume we haven't timed out
					timeout = Long.MAX_VALUE;
				}

				//Check if we've timed out. The extra time is added because we want to stop fetching
				//later than we stop sending. See JavaDoc for CHANNEL_TIMEOUT for details
				if(timeout < (System.currentTimeMillis() - CHANNEL_TIMEOUT)) {
					Logger.debug(this, "Channel has timed out, won't fetch");
					return;
				}
			}

			String slots;
			synchronized(channelProps) {
				slots = channelProps.get(PropsKeys.FETCH_SLOT);
			}
			if(slots == null) {
				Logger.error(this, "Channel " + channelDir.getName() + " is corrupt - account file has no '" + PropsKeys.FETCH_SLOT + "' entry!");
				//TODO: Either delete the channel or resend the RTS
				return;
			}

			HashSlotManager slotManager = new HashSlotManager(new ChannelSlotSaveImpl(channelProps, PropsKeys.FETCH_SLOT), null, slots);
			slotManager.setPollAhead(POLL_AHEAD);

			String basekey;
			synchronized(channelProps) {
				basekey = channelProps.get(PropsKeys.PUBLIC_KEY);
			}
			if(basekey == null) {
				Logger.error(this, "Contact " + channelDir.getName() + " is corrupt - account file has no '" + PropsKeys.PUBLIC_KEY + "' entry!");
				//TODO: Either delete the channel or resend the RTS
				return;
			}

			String fetchCode;
			synchronized(channelProps) {
				fetchCode = channelProps.get(PropsKeys.FETCH_CODE);
			}

			if(fetchCode == null) {
				Logger.error(this, "Contact " + channelDir.getName() + " is corrupt - account file has no '" + PropsKeys.FETCH_CODE + "' entry!");
				//TODO: Either delete the channel or resend the RTS
				return;
			}
			basekey += fetchCode + "-";

			String slot;
			while((slot = slotManager.getNextSlot()) != null) {
				String key = basekey + slot;

				Logger.minor(this, "Attempting to fetch mail on key " + key);
				File result;
				try {
					result = fcpClient.fetch(key);
				} catch(ConnectionTerminatedException e) {
					Logger.debug(this, "Connection terminated");
					return;
				} catch(FCPFetchException e) {
					if(e.getCode() == FCPFetchException.INVALID_URI) {
						//Could be a local bug or we could have gotten a bad key in the RTS
						//TODO: This won't fix itself, so make sure the user notices
						Logger.error(this, "Fetch failed because the URI was invalid");
						return;
					}

					if(e.isFatal()) {
						Logger.normal(this, "Fatal fetch failure, marking slot as used");
						slotManager.slotUsed();
					}

					Logger.minor(this, "No mail in slot (fetch returned " + e.getMessage() + ")");
					continue;
				} catch (FCPException e) {
					Logger.error(this, "Unknown error while fetching message", e);
					return;
				}
				Logger.debug(this, "Fetch successful");

				PropsFile messageProps = PropsFile.createPropsFile(result, true);
				String messageType = messageProps.get("messagetype");

				if(messageType == null) {
					Logger.error(this, "Got message without messagetype, discarding");
					slotManager.slotUsed();
					result.delete();
					continue;
				}

				if(messageType.equals("message")) {
					if(handleMessage(result)) {
						slotManager.slotUsed();
					}
				} else if(messageType.equals("cts")) {
					Logger.minor(this, "Successfully received CTS");

					boolean success;
					synchronized(channelProps) {
						success = channelProps.put("status", "cts-received");
					}

					if(success) {
						slotManager.slotUsed();
					}
				} else if(messageType.equals("ack")) {
					if(handleAck(result)) {
						slotManager.slotUsed();
					}
				} else {
					Logger.error(this, "Got message of unknown type: " + messageType);
					slotManager.slotUsed();
				}

				if(!result.delete()) {
					Logger.error(this, "Deletion of " + result + " failed");
				}
			}

			//Reschedule
			schedule(TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
		}

		public void execute() {
			Logger.debug(this, "Scheduling Fetcher for execution");
			try {
				executor.execute(fetcher);
			} catch(RejectedExecutionException e) {
				Logger.debug(this, "Caugth RejectedExecutionException while scheduling Fetcher");
			}
		}

		public void schedule(long delay, TimeUnit unit) {
			Logger.debug(this, "Scheduling Fetcher for execution in " + delay + " " + unit.toString().toLowerCase());
			try {
				executor.schedule(fetcher, delay, unit);
			} catch(RejectedExecutionException e) {
				Logger.debug(this, "Caugth RejectedExecutionException while scheduling Fetcher");
			}
		}

		@Override
		public String toString() {
			return "Fetcher [" + channelDir + "]";
		}
	}

	private class RTSSender implements Runnable {
		@Override
		public synchronized void run() {
			Logger.debug(this, "RTSSender running (" + this + ")");

			try {
				realRun();
			} catch(RuntimeException e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			} catch(Error e) {
				Logger.debug(this, "Caugth " + e);
				e.printStackTrace();
				throw e;
			} catch (InterruptedException e) {
				Logger.debug(this, "RTSSender (" + this + ") interrupted, quitting");
				return;
			}
		}

		private void realRun() throws InterruptedException {
			//Check when the RTS should be sent
			long sendRtsIn = sendRTSIn();
			if(sendRtsIn < 0) {
				return;
			}
			if(sendRtsIn > 0) {
				Logger.debug(this, "Rescheduling RTSSender in " + sendRtsIn + " ms when the RTS is due to be inserted");
				schedule(sendRtsIn, TimeUnit.MILLISECONDS);
				return;
			}

			//Get or generate RTS values
			String privateKey;
			String publicKey;
			String initiatorSlot;
			String responderSlot;
			long timeout;
			synchronized(channelProps) {
				privateKey = channelProps.get(PropsKeys.PRIVATE_KEY);
				publicKey = channelProps.get(PropsKeys.PUBLIC_KEY);
				initiatorSlot = channelProps.get(PropsKeys.SEND_SLOT);
				responderSlot = channelProps.get(PropsKeys.FETCH_SLOT);

				if((privateKey == null) || (publicKey == null)) {
					SSKKeyPair keyPair;
					try {
						Logger.debug(this, "Making new key pair");
						keyPair = fcpClient.makeSSK();
					} catch(ConnectionTerminatedException e) {
						Logger.debug(this, "FCP connection has been terminated");
						return;
					}
					privateKey = keyPair.privkey;
					publicKey = keyPair.pubkey;
				}
				if(initiatorSlot == null) {
					initiatorSlot = generateRandomSlot();
				}
				if(responderSlot == null) {
					responderSlot = generateRandomSlot();
				}

				timeout = System.currentTimeMillis() + CHANNEL_TIMEOUT;

				channelProps.put(PropsKeys.PUBLIC_KEY, publicKey);
				channelProps.put(PropsKeys.PRIVATE_KEY, privateKey);
				channelProps.put(PropsKeys.SEND_SLOT, initiatorSlot);
				channelProps.put(PropsKeys.FETCH_SLOT, responderSlot);
				channelProps.put(PropsKeys.SEND_CODE, "i");
				channelProps.put(PropsKeys.FETCH_CODE, "r");
				channelProps.put(PropsKeys.TIMEOUT, "" + timeout);
			}

			//Get mailsite key from WoT
			WoTConnection wotConnection = freemail.getWotConnection();
			if(wotConnection == null) {
				//WoT isn't loaded, so try again later
				Logger.debug(this, "WoT not loaded, trying again in 5 minutes");
				schedule(TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				return;
			}

			String remoteId;
			synchronized(channelProps) {
				 remoteId = channelProps.get(PropsKeys.REMOTE_ID);
			}
			if(remoteId == null) {
				Logger.debug(this, "Missing remote identity");
				return;
			}

			String senderId = account.getIdentity();
			Logger.debug(this, "Getting identity from WoT");
			Identity recipient;
			try {
				recipient = wotConnection.getIdentity(remoteId, senderId);
			} catch(PluginNotFoundException e) {
				Logger.error(this, "WoT plugin isn't loaded, can't send RTS");
				recipient = null;
			}
			if(recipient == null) {
				Logger.debug(this, "Didn't get identity from WoT, trying again in 5 minutes");
				schedule(TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				return;
			}

			//Get the mailsite edition
			int mailisteEdition;
			String edition;
			try {
				edition = wotConnection.getProperty(remoteId, WoTProperties.MAILSITE_EDITION);
			} catch(PluginNotFoundException e1) {
				edition = null;
			}
			if(edition == null) {
				mailisteEdition = 1;
			} else {
				try {
					mailisteEdition = Integer.parseInt(edition);
				} catch(NumberFormatException e) {
					mailisteEdition = 1;
				}
			}

			//Strip the WoT part from the key and add the Freemail path
			String mailsiteKey = recipient.getRequestURI();
			mailsiteKey = mailsiteKey.substring(0, mailsiteKey.indexOf("/"));
			mailsiteKey = mailsiteKey + "/mailsite/-" + mailisteEdition + "/mailpage";

			//Fetch the mailsite
			File mailsite;
			try {
				Logger.debug(this, "Fetching mailsite from " + mailsiteKey);
				mailsite = fcpClient.fetch(mailsiteKey);
			} catch(ConnectionTerminatedException e) {
				Logger.debug(this, "FCP connection has been terminated");
				return;
			} catch(FCPFetchException e) {
				Logger.debug(this, "Mailsite fetch failed (" + e + "), trying again in 5 minutes");
				schedule(TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				return;
			} catch (FCPException e) {
				Logger.error(this, "Unknown error while fetching mailsite", e);
				schedule(TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				return;
			}

			//Get RTS KSK
			PropsFile mailsiteProps = PropsFile.createPropsFile(mailsite, false);
			String rtsKey = mailsiteProps.get("rtsksk");

			//Get the senders mailsite key
			Logger.debug(this, "Getting sender identity from WoT");
			Identity senderIdentity;
			try {
				senderIdentity = wotConnection.getIdentity(senderId, senderId);
			} catch(PluginNotFoundException e) {
				Logger.error(this, "WoT plugin not loaded, can't send RTS");
				senderIdentity = null;
			}
			if(senderIdentity == null) {
				Logger.debug(this, "Didn't get identity from WoT, trying again in 5 minutes");
				schedule(TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				return;
			}

			int senderMailsiteEdition;
			String senderEdition;
			try {
				senderEdition = wotConnection.getProperty(account.getIdentity(), WoTProperties.MAILSITE_EDITION);
			} catch(PluginNotFoundException e1) {
				senderEdition = null;
			}
			if(edition == null) {
				senderMailsiteEdition = 1;
			} else {
				try {
					senderMailsiteEdition = Integer.parseInt(senderEdition);
				} catch(NumberFormatException e) {
					senderMailsiteEdition = 1;
				}
			}

			String senderMailsiteKey = senderIdentity.getRequestURI();
			senderMailsiteKey = senderMailsiteKey.substring(0, senderMailsiteKey.indexOf("/"));
			senderMailsiteKey = senderMailsiteKey + "/mailsite/-" + senderMailsiteEdition + "/mailpage";

			//Now build the RTS
			byte[] rtsMessageBytes = buildRTSMessage(senderMailsiteKey, recipient.getIdentityID(), privateKey, initiatorSlot, responderSlot, timeout);
			if(rtsMessageBytes == null) {
				return;
			}

			//Sign the message
			byte[] signedMessage = signRtsMessage(rtsMessageBytes);
			if(signedMessage == null) {
				return;
			}

			//Encrypt the message using the recipients public key
			String keyModulus = mailsiteProps.get("asymkey.modulus");
			if(keyModulus == null) {
				Logger.error(this, "Mailsite is missing public key modulus");
				schedule(1, TimeUnit.HOURS);
				return;
			}

			String keyExponent = mailsiteProps.get("asymkey.pubexponent");
			if(keyExponent == null) {
				Logger.error(this, "Mailsite is missing public key exponent");
				schedule(1, TimeUnit.HOURS);
				return;
			}

			byte[] rtsMessage = encryptMessage(signedMessage, keyModulus, keyExponent);

			//Clean up the fetched mailsite
			mailsiteProps = null;
			if(!mailsite.delete()) {
				Logger.error(this, "Couldn't delete " + mailsite);
			}

			//Insert
			int slot;
			try {
				String key = "KSK@" + rtsKey + "-" + DateStringFactory.getKeyString();
				Logger.debug(this, "Inserting RTS to " + key);
				slot = fcpClient.slotInsert(rtsMessage, key, 1, "");
			} catch(ConnectionTerminatedException e) {
				return;
			}
			if(slot < 0) {
				Logger.debug(this, "Slot insert failed, trying again in 5 minutes");
				schedule(TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				return;
			}

			//Update channel props file
			synchronized(channelProps) {
				//Check if we've gotten the CTS while inserting the RTS
				if(!"cts-received".equals(channelProps.get(PropsKeys.SENDER_STATE))) {
					channelProps.put(PropsKeys.SENDER_STATE, "rts-sent");
				}
				channelProps.put(PropsKeys.RTS_SENT_AT, Long.toString(System.currentTimeMillis()));
			}

			long delay = sendRTSIn();
			if(delay < 0) {
				return;
			}
			Logger.debug(this, "Rescheduling RTSSender to run in " + delay + " ms when the reinsert is due");
			try {
				schedule(delay, TimeUnit.MILLISECONDS);
			} catch(RejectedExecutionException e) {
				Logger.debug(this, "Caugth RejectedExecutionException while scheduling RTSSender");
			}

			//Start the fetcher now that we have keys, slots etc.
			fetcher.execute();
		}

		public void execute() {
			Logger.debug(this, "Scheduling RTSSender for execution");
			try {
				executor.execute(this);
			} catch(RejectedExecutionException e) {
				Logger.debug(this, "Caugth RejectedExecutionException while scheduling RTSSender");
			}
		}

		public void schedule(long delay, TimeUnit unit) {
			Logger.debug(this, "Scheduling RTSSender for execution in " + delay + " " + unit.toString().toLowerCase());
			try {
				executor.schedule(this, delay, unit);
			} catch(RejectedExecutionException e) {
				Logger.debug(this, "Caugth RejectedExecutionException while scheduling RTSSender");
			}
		}

		/**
		 * Returns the number of milliseconds left to when the RTS should be sent, or -1 if it
		 * should not be sent.
		 * @return the number of milliseconds left to when the RTS should be sent
		 */
		private long sendRTSIn() {
			//Check if the CTS has been received
			String senderState;
			String recipientState;
			synchronized(channelProps) {
				senderState = channelProps.get(PropsKeys.SENDER_STATE);
				recipientState = channelProps.get(PropsKeys.RECIPIENT_STATE);
			}
			if((senderState != null) && senderState.equals("cts-received")) {
				Logger.debug(this, "CTS has been received");
				return -1;
			}
			if((recipientState != null) && (recipientState.equals("rts-received") || recipientState.equals("cts-sent"))) {
				//We've received an RTS from the other side
				Logger.debug(this, "RTS received from other side");
				return -1;
			}

			//Check when the RTS should be (re)sent
			String rtsSentAt;
			synchronized(channelProps) {
				rtsSentAt = channelProps.get(PropsKeys.RTS_SENT_AT);
			}

			if(rtsSentAt != null) {
				long sendTime;
				try {
					sendTime = Long.parseLong(rtsSentAt);
				} catch(NumberFormatException e) {
					Logger.debug(this, "Illegal value in " + PropsKeys.RTS_SENT_AT + " field, assuming 0");
					sendTime = 0;
				}

				long timeToResend = (24 * 60 * 60 * 1000) - (System.currentTimeMillis() - sendTime);
				if(timeToResend > 0) {
					return timeToResend;
				}
			}

			//Send the RTS immediately
			return 0;
		}

		private byte[] buildRTSMessage(String senderMailsiteKey, String recipientIdentityID, String channelPrivateKey, String initiatorSlot, String responderSlot, long timeout) {
			assert (FreenetURI.checkUSK(senderMailsiteKey)) : "Malformed sender mailsite: " + senderMailsiteKey;
			assert (recipientIdentityID != null);
			assert (channelPrivateKey.matches("^SSK@\\S{42,44},\\S{42,44},\\S{7}/$")) : "Malformed channel key: " + channelPrivateKey;
			assert (initiatorSlot != null);
			assert (responderSlot != null);
			assert (timeout > System.currentTimeMillis());

			StringBuffer rtsMessage = new StringBuffer();
			rtsMessage.append(RTSKeys.MAILSITE + "=" + senderMailsiteKey + "\r\n");
			rtsMessage.append(RTSKeys.TO + "=" + recipientIdentityID + "\r\n");
			rtsMessage.append(RTSKeys.CHANNEL + "=" + channelPrivateKey + "\r\n");
			rtsMessage.append(RTSKeys.INITIATOR_SLOT + "=" + initiatorSlot + "\r\n");
			rtsMessage.append(RTSKeys.RESPONDER_SLOT + "=" + responderSlot + "\r\n");
			rtsMessage.append(RTSKeys.TIMEOUT + "=" + timeout + "\r\n");
			rtsMessage.append("\r\n");

			byte[] rtsMessageBytes;
			try {
				rtsMessageBytes = rtsMessage.toString().getBytes("UTF-8");
			} catch(UnsupportedEncodingException e) {
				Logger.error(this, "JVM doesn't support UTF-8 charset");
				e.printStackTrace();
				return null;
			}

			return rtsMessageBytes;
		}

		private byte[] signRtsMessage(byte[] rtsMessageBytes) {
			SHA256Digest sha256 = new SHA256Digest();
			sha256.update(rtsMessageBytes, 0, rtsMessageBytes.length);
			byte[] hash = new byte[sha256.getDigestSize()];
			sha256.doFinal(hash, 0);

			RSAKeyParameters ourPrivateKey = AccountManager.getPrivateKey(account.getProps());

			AsymmetricBlockCipher signatureCipher = new RSAEngine();
			signatureCipher.init(true, ourPrivateKey);
			byte[] signature = null;
			try {
				signature = signatureCipher.processBlock(hash, 0, hash.length);
			} catch(InvalidCipherTextException e) {
				Logger.error(this, "Failed to RSA encrypt hash: " + e.getMessage());
				e.printStackTrace();
				return null;
			}

			byte[] signedMessage = new byte[rtsMessageBytes.length + signature.length];
			System.arraycopy(rtsMessageBytes, 0, signedMessage, 0, rtsMessageBytes.length);
			System.arraycopy(signature, 0, signedMessage, rtsMessageBytes.length, signature.length);

			return signedMessage;
		}

		private byte[] encryptMessage(byte[] signedMessage, String keyModulus, String keyExponent) {
			//Make a new symmetric key for the message
			byte[] aesKeyAndIV = new byte[32 + 16];
			SecureRandom rnd = new SecureRandom();
			rnd.nextBytes(aesKeyAndIV);

			//Encrypt the message with the new symmetric key
			PaddedBufferedBlockCipher aesCipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()), new PKCS7Padding());
			KeyParameter aesKeyParameters = new KeyParameter(aesKeyAndIV, 16, 32);
			ParametersWithIV aesParameters = new ParametersWithIV(aesKeyParameters, aesKeyAndIV, 0, 16);
			aesCipher.init(true, aesParameters);

			byte[] encryptedMessage = new byte[aesCipher.getOutputSize(signedMessage.length)];
			int offset = aesCipher.processBytes(signedMessage, 0, signedMessage.length, encryptedMessage, 0);

			try {
				aesCipher.doFinal(encryptedMessage, offset);
			} catch(InvalidCipherTextException e) {
				Logger.error(this, "Failed to perform symmertic encryption on RTS data: " + e.getMessage());
				e.printStackTrace();
				return null;
			}

			RSAKeyParameters recipientPublicKey = new RSAKeyParameters(false, new BigInteger(keyModulus, 32), new BigInteger(keyExponent, 32));
			AsymmetricBlockCipher keyCipher = new RSAEngine();
			keyCipher.init(true, recipientPublicKey);
			byte[] encryptedAesParameters = null;
			try {
				encryptedAesParameters = keyCipher.processBlock(aesKeyAndIV, 0, aesKeyAndIV.length);
			} catch(InvalidCipherTextException e) {
				Logger.error(this, "Failed to perform asymmertic encryption on RTS symmetric key: " + e.getMessage());
				e.printStackTrace();
				return null;
			}

			//Assemble the final message
			byte[] rtsMessage = new byte[encryptedAesParameters.length + encryptedMessage.length];
			System.arraycopy(encryptedAesParameters, 0, rtsMessage, 0, encryptedAesParameters.length);
			System.arraycopy(encryptedMessage, 0, rtsMessage, encryptedAesParameters.length, encryptedMessage.length);

			return rtsMessage;
		}

		@Override
		public String toString() {
			return "RTSSender [" + channelDir + "]";
		}
	}

	private boolean handleMessage(File msg) {
		// parse the Freemail header(s) out.
		PropsFile msgprops = PropsFile.createPropsFile(msg, true);
		String s_id = msgprops.get("id");
		if (s_id == null) {
			Logger.error(this,"Message is missing id. Discarding.");
			msgprops.closeReader();
			return true;
		}

		long id;
		try {
			id = Long.parseLong(s_id);
		} catch (NumberFormatException nfe) {
			Logger.error(this,"Got a message with an invalid (non-integer) id. Discarding.");
			msgprops.closeReader();
			return true;
		}

		long ackDelay = (long)(System.currentTimeMillis() + (Math.random() * MAX_ACK_DELAY));
		synchronized(ackLog) {
			try {
				ackLog.add(id, Long.toString(ackDelay));
			} catch(IOException e) {
				Logger.error(this, "Caugth IOException while writing to ack log: " + e);
				return false;
			}
		}

		BufferedReader br = msgprops.getReader();
		if (br == null) {
			Logger.error(this,"Got an invalid message. Discarding.");
			msgprops.closeReader();
			return true;
		}

		if(!channelEventCallback.get().handleMessage(this, br, id)) {
			return false;
		}

		try {
			ScheduledExecutorService senderExecutor = FreemailPlugin.getExecutor(TaskType.SENDER);
			senderExecutor.execute(new AckInserter(id, ackDelay));
		} catch(RejectedExecutionException e) {
			Logger.debug(this, "Caugth RejectedExecutionException while scheduling AckInserter");
		}

		return true;
	}

	private class AckInserter implements Runnable {
		private final long ackId;
		private final long insertAfter;

		private AckInserter(long ackId, long insertAfter) {
			this.ackId = ackId;
			this.insertAfter = insertAfter;
		}

		@Override
		public void run() {
			Logger.debug(this, "AckInserter(" + ackId + ") for " + Channel.this.toString() + " running");

			if(System.currentTimeMillis() < insertAfter) {
				long remaining = insertAfter - System.currentTimeMillis();
				Logger.debug(this, "Rescheduling in " + remaining + "ms when inserting is allowed");
				ScheduledExecutorService senderExecutor = FreemailPlugin.getExecutor(TaskType.SENDER);
				senderExecutor.schedule(this, remaining, TimeUnit.MILLISECONDS);
				return;
			}

			//Build the header of the inserted message
			Bucket bucket = new ArrayBucket(
					("messagetype=ack\r\n" +
							"id=" + ackId + "\r\n" +
					"\r\n").getBytes());

			boolean inserted;
			try {
				inserted = insertMessage(bucket, "ack" + ackId);
			} catch(IOException e) {
				//The getInputStream() method of ArrayBucket doesn't throw
				throw new AssertionError();
			} catch (InterruptedException e) {
				Logger.debug(this, "AckInserter(" + ackId + ") for " + Channel.this.toString() + " interrupted, quitting");
				return;
			}

			if(inserted) {
				synchronized(ackLog) {
					try {
						ackLog.remove(ackId);
					} catch(IOException e) {
						Logger.error(this, "Caugth IOException while writing to ack log: " + e);
					}
				}
			} else {
				try {
					ScheduledExecutorService senderExecutor = FreemailPlugin.getExecutor(TaskType.SENDER);
					senderExecutor.schedule(this, TASK_RETRY_DELAY, TimeUnit.MILLISECONDS);
				} catch(RejectedExecutionException e) {
					Logger.debug(this, "Caugth RejectedExecutionException while scheduling AckInserter");
				}
			}
		}
	}

	private boolean handleAck(File result) {
		PropsFile ackProps = PropsFile.createPropsFile(result);
		String ackString = ackProps.get("id");
		if(ackString == null) {
			Logger.debug(this, "Received ack without id, discarding");
			return true;
		}

		Logger.debug(this, "Got ack with id " + ackString);

		String[] acks = ackString.split(",");
		for(String ack : acks) {
			long messageId = Long.parseLong(ack);
			channelEventCallback.get().onAckReceived(messageId);
		}

		return true;
	}

	private class HashSlotManager extends SlotManager {
		HashSlotManager(SlotSaveCallback cb, Object userdata, String slotlist) {
			super(cb, userdata, slotlist);
		}

		@Override
		protected String incSlot(String slot) {
			return calculateNextSlot(slot);
		}
	}

	private static class ChannelSlotSaveImpl implements SlotSaveCallback {
		private final PropsFile propsFile;
		private final String keyName;

		private ChannelSlotSaveImpl(PropsFile propsFile, String keyName) {
			this.propsFile = propsFile;
			this.keyName = keyName;
		}

		@Override
		public void saveSlots(String slots, Object userdata) {
			synchronized(propsFile) {
				propsFile.put(keyName, slots);
			}
		}
	}

	public interface ChannelEventCallback {
		public void onAckReceived(long id);
		public boolean handleMessage(Channel channel, BufferedReader message, long id);
	}
}
