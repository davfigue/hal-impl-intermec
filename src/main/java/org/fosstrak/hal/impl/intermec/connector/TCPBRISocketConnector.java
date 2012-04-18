package org.fosstrak.hal.impl.intermec.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import org.apache.log4j.Logger;

/**
 * Simple TCP connector for Intermec's BRI
 * 
 * @author David Figueroa Escalante
 */
public class TCPBRISocketConnector {

	static Logger log = Logger.getLogger(TCPBRISocketConnector.class);

	/**
	 * TCP socket to the device
	 */
	private Socket s;

	private int timeout;

	/**
	 * 
	 */
	private SocketAddress address;

	/**
	 * Input stream to send data to the device
	 */
	private BufferedReader in;

	/**
	 * Output stream to receive data from the device
	 */
	private PrintWriter out;

	/** flag to indicate that a sendRequest method is proceeding */
	boolean sendRequestActive = false;

	private boolean initialized = false;

	// boolean autoReconnect;

	/**
	 * 
	 * @param host
	 * @param port
	 * @param autoReconnect
	 * @throws IOException
	 */
	public TCPBRISocketConnector(String host, int port, int timeout) throws IOException {

		// this.autoReconnect = autoReconnect;
		this.timeout = timeout;

		address = new InetSocketAddress(host, port);

		s = new Socket();
		s.setSoTimeout(timeout);
		s.connect(address);

		log.info("TCPBRISocketConnector: " + host + ":" + port + " ,connection established");

		System.out.println("TCPBRISocketConnector: " + host + ":" + port + " ,connection established");

		in = new BufferedReader(new InputStreamReader(s.getInputStream()));
		out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);

		// Getting the Initial Information data that could be sent by the BRI
		getResponse();

		initialized = true;
	}

	/**
	 * Connector's socket string representation
	 */
	public String toString() {
		return s.getInetAddress().getHostAddress() + ":" + s.getPort();
	}

	/**
	 * Returns the RFID tags list or null
	 * 
	 * @param data
	 * @return
	 * @throws IOException
	 */
	public synchronized String[] sendReadRequest(String data) throws IOException {

		String response = sendRequest(data);

		// If not, then there are no tags in the message
		if (!response.startsWith("H")) {
			return null;
		}

		// The "H" just indicate that the id is an HEX string then is not needed
		response = response.replaceAll("H", "");

		String[] list = response.split("\n");

		return list;
	}

	/**
	 * This Method Allows to send the any command to the BRI client, works
	 * synchronized and returns the response inmediately.
	 * 
	 * @param data
	 * @return
	 * @throws IOException
	 */
	public synchronized String sendRequest(String data) throws IOException {

		while (sendRequestActive) {
			try {
				wait();
			} catch (InterruptedException e) {
			}
		}

		sendRequestActive = true;

		log.debug("Command send: " + data);

		out.println(data);
		out.flush();
		String response = getResponse();

		sendRequestActive = false;

		notifyAll();

		return response;
	}

	/**
	 * Returns the response for the previous send command, returns an empty
	 * string if a SocketTimeoutException is triggered
	 * 
	 * @return
	 * @throws IOException
	 */
	private synchronized String getResponse() throws IOException {

		StringBuffer buffer = new StringBuffer();

		try {

			String line = in.readLine();

			while (line != null && !line.equals("OK>")) {

				buffer.append(line + "\n");
				line = in.readLine();
			}

		} catch (SocketTimeoutException e) {
			log.warn("Timeout of: " + timeout + " ms. in read operation");

			return "";
		}

		return buffer.toString();
	}

	/**
	 * Close the socket connection, after calling this method a new connector
	 * should be created
	 */
	public void close() throws IOException {

		out.close();
		in.close();
		s.close();
	}

	/**
	 * Returns the status of the connection
	 * 
	 * @return
	 */
	public boolean isConnected() {
		return s.isConnected();
	}

	/*
	 * Method for testing purposes
	 */
	public static void main(String[] args) {

		try {

			TCPBRISocketConnector connector = new TCPBRISocketConnector("10.2.4.217", 2189, 2000);

			System.out.println("Querying the reader");

			String[] a = connector.sendReadRequest("ATTRIB ANTS=1,2,3,4;R");

			System.out.println("Response:");

			if (a == null) {
				System.out.println("null list");
				return;
			}

			for (String b : a) {
				System.out.println(b);
			}

			connector.close();

		} catch (IOException e) {

			e.printStackTrace();
		}

	}
}
