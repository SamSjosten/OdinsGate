package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real fetcher against a server on loopback: a whole answer comes back; a 404 and a body past the limit are
 * refused, whether its Content-Length announces that, it is measured at its end, or it never ends and is cut off as
 * it comes; and a body that stalls after its headers is given up on at the deadline rather than holding the
 * launcher. The deadline is the fetcher's own argument: thirty seconds where only a fault could reach it, half a
 * second for the one test about reaching it, whose stall is a latch the test holds until it is over, not a sleep.
 */
public class UpdaterFetcherTest
{
	private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
	private static final Duration AMPLE = Duration.ofSeconds(30);
	/** Short, so the stalled test doesn't hold the suite; generous enough for loopback headers not to flake. */
	private static final Duration SHORT = Duration.ofMillis(500);
	private static final long MAX_BYTES = 100;

	private HttpServer server;
	private ExecutorService handlers;
	/** Released at the end of each test: until then the stalled and endless answers never finish. */
	private final CountDownLatch over = new CountDownLatch(1);
	/** Counted down once the stalled answer's headers and first bytes are out: the stall is after them. */
	private final CountDownLatch stalledAfterHeaders = new CountDownLatch(1);
	private Updater.Fetcher fetcher;
	private Updater.Fetcher impatient;

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
		server.createContext("/endless", exchange ->
		{
			exchange.sendResponseHeaders(200, 0);
			byte[] chunk = "x".repeat(1024).getBytes(StandardCharsets.UTF_8);
			try (OutputStream out = exchange.getResponseBody())
			{
				// Until the fetcher cuts the connection (the write then fails) or the test is over.
				for (long sent = 0; over.getCount() > 0 && sent < 64L * 1024 * 1024; sent += chunk.length)
				{
					out.write(chunk);
					out.flush();
				}
			}
			catch (IOException e)
			{
				// The fetcher gave up on us: the end this answer waits for.
			}
			exchange.close();
		});
		server.createContext("/stalled", exchange ->
		{
			exchange.sendResponseHeaders(200, 10);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write("abc".getBytes(StandardCharsets.UTF_8));
				out.flush();
				stalledAfterHeaders.countDown();
				over.await();
				out.write("defghij".getBytes(StandardCharsets.UTF_8));
			}
			catch (InterruptedException | IOException e)
			{
				// The test is over, or the fetcher gave up and closed the connection: either ends the stall.
			}
			exchange.close();
		});
		server.start();
		HttpClient http = HttpClient.newBuilder().connectTimeout(AMPLE).build();
		fetcher = Updater.fetcher(http, AMPLE, MAX_BYTES);
		impatient = Updater.fetcher(http, SHORT, MAX_BYTES);
	}

	@AfterEach
	public void stop()
	{
		over.countDown();
		server.stop(0);
		handlers.shutdownNow();
	}

	/** {@code length} 0 means chunked, no Content-Length; -1 means no body. */
	private static void answer(HttpExchange exchange, int status, long length, String body) throws IOException
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

	/** The server's URI for {@code path}, with the loopback address bracketed when it is the IPv6 one. */
	private URI at(String path) throws URISyntaxException
	{
		return new URI("http", null, LOOPBACK.getHostAddress(), server.getAddress().getPort(), path, null, null);
	}

	@Test
	public void aWholeAnswerComesBack() throws Exception
	{
		assertArrayEquals("a release file".getBytes(StandardCharsets.UTF_8), fetcher.fetch(at("/whole")));
	}

	@Test
	public void anythingButOkIsRefusedWithItsStatus() throws Exception
	{
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/gone")));
		assertTrue(refused.getMessage().endsWith(" answered 404"), refused.getMessage());
	}

	@Test
	public void aBodyAnnouncedPastTheLimitIsDroppedUnread() throws Exception
	{
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/announced-big")));
		assertTrue(refused.getMessage().contains("more than " + MAX_BYTES + " bytes"), refused.getMessage());
	}

	@Test
	public void aBodyMeasuredPastTheLimitIsRefused() throws Exception
	{
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/measured-big")));
		assertTrue(refused.getMessage().contains("more than " + MAX_BYTES + " bytes"), refused.getMessage());
	}

	@Test
	public void aBodyThatNeverEndsIsCutOffAtTheLimitAsItComes() throws Exception
	{
		// Nothing but the limit can end this fetch within its ample deadline: the body is counted as it arrives.
		IOException refused = assertThrows(IOException.class, () -> fetcher.fetch(at("/endless")));
		assertTrue(refused.getMessage().contains("more than " + MAX_BYTES + " bytes"), refused.getMessage());
	}

	@Test
	public void aBodyThatStallsAfterItsHeadersIsGivenUpOnAtTheDeadline() throws Exception
	{
		IOException refused = assertThrows(IOException.class, () -> impatient.fetch(at("/stalled")));
		assertTrue(refused.getMessage().contains("didn't answer whole within " + SHORT.toMillis() + " ms"),
			refused.getMessage());
		assertEquals(0, stalledAfterHeaders.getCount(), "the headers and first bytes were out before the deadline");
	}
}
