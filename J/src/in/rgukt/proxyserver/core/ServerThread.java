package in.rgukt.proxyserver.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;

/**
 * This does all the heavy duty work. Each HTTP transaction is managed by a
 * ServerThread. The thread is alive until it sees Connection: close header or
 * an explicit TCP connection tear down by the client or server.
 * 
 * @author Venkata Jaswanth
 *
 */
public class ServerThread implements Runnable {
	private Socket clientSocket;
	private Socket serverSocket;
	private BufferedWriter clientSocketWriter;
	private BufferedWriter serverSocketWriter;
	private InputStream serverSocketByteReader;
	private InputStream clientSocketByteReader;
	private OutputStream clientSocketByteWriter;
	private OutputStream serverSocketByteWriter;
	private HTTPRequest httpRequest;
	private HTTPResponse httpResponse;
	private boolean closeConnection;

	public ServerThread(Socket clientSocket) {
		this.clientSocket = clientSocket;
		serverSocket = new Socket();
	}

	/**
	 * Waits for the client to send HTTP request. It receives it and creates a
	 * HTTPRequest object to represent it.
	 */
	@SuppressWarnings("unused")
	private void readHTTPRequest() {
		try {
			clientSocketByteReader = clientSocket.getInputStream();
			httpRequest = new HTTPRequest();
			StringBuilder responseLine = new StringBuilder();
			int data = 0;
			while (true) {
				data = clientSocketByteReader.read();
				// If Connection : keep-alive is set and still doesn't get any
				// request
				if (data == -1) {
					closeConnection = true;
					return;
				}
				responseLine.append((char) data);
				if (data == '\n') {
					String header = responseLine.toString();
					if (header.equals("\r\n")) {
						httpRequest.addToRequest("\r\n");
						break;
					} else if (header.equals("\n")) {
						httpRequest.addToRequest("\n");
						break;
					}
					httpRequest.setHeader(header);
					responseLine = new StringBuilder();
				}
			}
			// If this is a POST request
			if (httpRequest.hasHeader("Content-Length")) {
				int bodyLength = Integer.parseInt(httpRequest
						.getHeader("Content-Length"));
				byte[] body = new byte[bodyLength];
				int readData = clientSocketByteReader.read(body, 0, bodyLength);
				while (readData < bodyLength)
					readData += clientSocketByteReader.read(body, readData,
							bodyLength - readData);
				httpRequest.body = body;
			}
		} catch (IOException e) {
			e.printStackTrace();
			closeConnection = true;
		}
	}

	/**
	 * Sends the HTTPRequest to the intended server.
	 */
	private void sendHTTPRequest() {
		if (closeConnection)
			return;
		try {
			if (serverSocket.isConnected() == false)
				serverSocket.connect(HTTPUtils.getSocketAddress("10.20.3.2:3128"));
			serverSocketWriter = new BufferedWriter(new OutputStreamWriter(
					serverSocket.getOutputStream()));
			serverSocketWriter.write(httpRequest.getCompleteRequest());
			serverSocketWriter.flush();
			if (httpRequest.hasHeader("Content-Length")) {
				serverSocketByteWriter = serverSocket.getOutputStream();
				serverSocketByteWriter.write(httpRequest.body, 0,
						httpRequest.body.length);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Receives HTTP response from the server and creates a HTTPResponse object
	 * to represent it.
	 */
	private void readHTTPResponse() {
		if (closeConnection)
			return;
		try {
			httpResponse = new HTTPResponse();
			serverSocketByteReader = serverSocket.getInputStream();
			StringBuilder responseLine = new StringBuilder();
			int data = 0;
			// int count = 0;
			while (true) {
				data = serverSocketByteReader.read();
				responseLine.append((char) data);
				// DEBUG: System.out.print((char) data);
				if (data == '\n') {
					String header = responseLine.toString();
					if (header.equals("\r\n")) {
						httpResponse.addToResponse("\r\n");
						break;
					} else if (header.equals("\n")) {
						httpResponse.addToResponse("\n");
						break;
					}
					httpResponse.setHeader(header);
					responseLine = new StringBuilder();
				}
				// TODO: DEBUG
				/*
				 * count++; if (count > 1200) System.exit(0);
				 */
			}
			int bodyLength = -1;
			if (httpResponse.hasHeader("Content-Length")) {
				bodyLength = Integer.parseInt(httpResponse
						.getHeader("Content-Length"));
				byte[] body = new byte[bodyLength];
				int dataRead = serverSocketByteReader.read(body, 0, bodyLength);
				while (dataRead < bodyLength)
					dataRead += serverSocketByteReader.read(body, dataRead,
							bodyLength - dataRead);
				httpResponse.setBody(body);
			} else if (httpResponse.hasHeader("Transfer-Encoding")) {
				while ((bodyLength = getBodyLength(serverSocketByteReader)) != 0) {
					while (bodyLength-- > -2) {
						data = serverSocketByteReader.read();
						httpResponse.chunkedBody.add((byte) data);
					}
				}
				data = serverSocketByteReader.read();
				httpResponse.chunkedBody.add((byte) data);
				if (data == '\r')
					httpResponse.chunkedBody.add((byte) serverSocketByteReader
							.read());
			} else {
				System.err.println("Error : "
						+ httpRequest.getInitialRequestLine());
				System.err.println(httpResponse.getCompletHTTPResponse());
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private int getBodyLength(InputStream serverSocketByteReader)
			throws IOException {
		int data = 0;
		StringBuilder length = new StringBuilder();
		while (true) {
			data = serverSocketByteReader.read();
			httpResponse.chunkedBody.add((byte) data);
			if (data == ';')
				break;
			else if (data == '\r') {
				data = serverSocketByteReader.read();
				httpResponse.chunkedBody.add((byte) data);
				break;
			} else if (data == '\n')
				break;
			length.append((char) data);
		}
		if (data == ';') {
			while (true) {
				data = serverSocketByteReader.read();
				httpResponse.chunkedBody.add((byte) data);
				if (data == '\n')
					break;
			}
		}
		String str = length.toString();
		return Integer.parseInt(str, 16);
	}

	/**
	 * Sends the received HTTP response to the client.
	 */
	/*
	 * This sends the HTTP status and headers through buffered IO then sends
	 * body of the response using byte oriented IO.
	 */
	private void sendHTTPResponse() {
		if (closeConnection)
			return;
		try {
			clientSocketWriter = new BufferedWriter(new OutputStreamWriter(
					clientSocket.getOutputStream()));
			clientSocketWriter.write(httpResponse.getCompletHTTPResponse());
			clientSocketWriter.flush();
			clientSocketByteWriter = clientSocket.getOutputStream();
			if (httpResponse.hasHeader("Content-Length")) {
				clientSocketByteWriter.write(httpResponse.getBody(), 0, Integer
						.parseInt(httpResponse.getHeader("Content-Length")));
			} else {
				for (Object data : httpResponse.chunkedBody.toArray())
					clientSocketByteWriter.write((byte) data);
			}
			clientSocketByteWriter.flush();
			if (closeConnectionClient() || closeConnectionServer())
				closeConnection = true;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private boolean closeConnectionClient() {
		if (httpRequest.hasHeader("Connection") == false)
			return true;
		else if (httpRequest.getHeader("Connection").equals("close"))
			return true;
		return false;
	}

	private boolean closeConnectionServer() {
		if (httpResponse.hasHeader("Connection") == false)
			return true;
		else if (httpResponse.getHeader("Connection").equals("close"))
			return true;
		return false;
	}

	@Override
	public void run() {
		while (closeConnection == false) {
			readHTTPRequest();
			sendHTTPRequest();
			readHTTPResponse();
			sendHTTPResponse();
			break;
		}
		try {
			// System.out.println("Closing Connection!");
			clientSocket.close();
			serverSocket.close();
			return;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}