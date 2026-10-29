package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real fetcher against a server on loopback: a whole answer comes back, a 404 and a body past the limit are
 * refused (announced by Content-Length, or measured when there is none), and a body that stalls after its headers
 * is given up on within the timeout rather than holding the launcher.
 */
public class UpdaterFetcherTest
{
	private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
	/** Short, so the stalled test doesn't hold the suite; generous enough for loopback not to flake. */
	private static final Duration TIMEOUT = Duration.ofMillis(500);
	private static final long MAX_BYTES = 100;

	private HttpServer server;
	private ExecutorService handlers;
	private Updater.Fetcher fetcher;

	@BeforeEach
	public void start() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
		handlers = Executors.newCachedThreadPool();
		server.setExecutor(handlers);
		server.createContext("/whole", exchange -> answer(exchange, 200, "a release file".length(), "a release file"));
		server.createContext("/gone", exchange -> answer(exchange, 404, -1, ""));
		server.createContext("/announced-big", exchange -> answer(exchange, 200, MAX_BYTES + 1, "x".repeat(101)));
		server.createContext("/measured-big", exchange -> answer(exchange, 200, 0, "x".repeat(101)));
		server.createContext("/stalled", exchange ->
		{
			exchange.sendResponseHeaders(200, 10);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write("abc".getBytes(StandardCharsets.UTF_8));
				out.flush();
				Thread.sleep(2000);
				out.write("defghij".getBytes(StandardCharsets.UTF_8));
			}
			catch (InterruptedException | IOException e)
			{
				// The fetcher gave up and closed the connection, or the server stopped: either ends the stall.
			}
			exchange.close();
		});
		server.start();
		fetcher = Updater.fetcher(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), TIMEOUT, MAX_BYTES);
	}

	@AfterEach
	public void stop()
	{
		server.stop(0);
		handlers.shutdownNow();
	}

	/** {@code length} 0 means chunked, no Content-Length; -1 means no body. */
	private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status, long length, String body)
		throws IOException
	{
		exchange.sendResponseHeaders(status, length);
		if (length != -1)
		{
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		exchange.close();
	}

	private URI at(String path)
	{
		return URI.create("http://" + LOOPBACK.getHostAddress() + ":" + server.getAddress().getPort() + path);
	}

	@Test
	public void aWholeAnswerComesBack() throws Exception
	{
		assertArrayEquals("a release file".getBytes(StandardCharsets.UTF_8), fetcher.fetch(at("/whole")));
	}

	@Test
	public void anythingButOkIsRefusedWithItsStatus()
	{
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/gone")));
		assertTrue(refused.getMessage().endsWith(" answered 404"), refused.getMessage());
	}

	@Test
	public void aBodyAnnouncedPastTheLimitIsDroppedUnread()
	{
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/announced-big")));
		assertTrue(refused.getMessage().contains("more than " + MAX_BYTES + " bytes"), refused.getMessage());
	}

	@Test
	public void aBodyMeasuredPastTheLimitIsRefused()
	{
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/measured-big")));
		assertTrue(refused.getMessage().contains("more than " + MAX_BYTES + " bytes"), refused.getMessage());
	}

	@Test
	public void aBodyThatStallsAfterItsHeadersIsGivenUpOnWithinTheTimeout()
	{
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/stalled")));
		assertTrue(refused.getMessage().contains("didn't answer whole within " + TIMEOUT.toMillis() + " ms"),
			refused.getMessage());
	}
}
