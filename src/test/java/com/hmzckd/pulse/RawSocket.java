package com.hmzckd.pulse;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class RawSocket implements AutoCloseable {

	public static final int NO_CLOSE_FRAME = -1;

	private final Socket socket = new Socket();
	private final DataInputStream in;

	private RawSocket(int port, String token) throws IOException {
		socket.setReceiveBufferSize(4096);
		socket.connect(new InetSocketAddress("localhost", port), 5_000);
		OutputStream out = socket.getOutputStream();
		out.write(("GET /ws?token=" + token + " HTTP/1.1\r\n"
				+ "Host: localhost:" + port + "\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
				+ "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
		in = new DataInputStream(socket.getInputStream());
		String headers = readHeaders();
		if (!headers.startsWith("HTTP/1.1 101")) {
			throw new IOException("handshake failed: " + headers);
		}
	}

	public static RawSocket connect(int port, String token) throws IOException {
		return new RawSocket(port, token);
	}

	public int awaitCloseCode(Duration timeout) throws IOException {
		socket.setSoTimeout((int) timeout.toMillis());
		try {
			while (true) {
				int opcode = in.readUnsignedByte() & 0x0F;
				int length = in.readUnsignedByte() & 0x7F;
				long payloadLength = switch (length) {
					case 126 -> in.readUnsignedShort();
					case 127 -> in.readLong();
					default -> length;
				};
				byte[] payload = in.readNBytes((int) payloadLength);
				if (opcode == 0x8) {
					return payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : NO_CLOSE_FRAME;
				}
			}
		}
		catch (EOFException | SocketTimeoutException ex) {
			return NO_CLOSE_FRAME;
		}
	}

	private String readHeaders() throws IOException {
		ByteArrayOutputStream headers = new ByteArrayOutputStream();
		int matched = 0;
		byte[] end = { '\r', '\n', '\r', '\n' };
		while (matched < end.length) {
			int b = in.read();
			if (b < 0) {
				throw new EOFException("connection closed during handshake");
			}
			headers.write(b);
			matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
		}
		return headers.toString(StandardCharsets.US_ASCII);
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
