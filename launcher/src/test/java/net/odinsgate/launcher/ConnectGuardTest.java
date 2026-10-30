package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The guard that lets the game reach our server and nothing else: what it answers Java's selector calls, and, once
 * installed, that a refused connection fails on the spot through plain sockets, URL connections and
 * {@code java.net.http}, while an allowed one connects. Nothing here leaves loopback: the refused addresses are
 * never dialed, since the refusing proxy is what a refused attempt reaches.
 */
public class ConnectGuardTest
{
	/** TEST-NET-1, never routed: an attempt that got past the guard could only fail slowly, not reach anything. */
	private static final String NOWHERE = "192.0.2.1";

	private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
	private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
	private ProxySelector selectorBefore;
	private ConnectGuard guard;

	@BeforeEach
	public void keepTheSelector()
	{
		selectorBefore = ProxySelector.getDefault();
	}

	@AfterEach
	public void restoreTheSelector() throws IOException
	{
		ProxySelector.setDefault(selectorBefore);
		if (guard != null)
		{
			guard.close();
		}
	}

	/** The log so far, with println's line separator as {@code \n} whatever the system (Windows runs this too). */
	private String logText()
	{
		return logBytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	private static Proxy refused(List<Proxy> answer)
	{
		assertEquals(1, answer.size(), answer.toString());
		return answer.get(0);
	}

	@Test
	public void theAllowedEndpointsAndLoopbackConnectDirectlyAndEverythingElseMeetsTheRefuser() throws Exception
	{
		guard = new ConnectGuard(List.of("odinsgate.net:43594", "Odinsgate.NET:43595"), log);
		assertEquals(Set.of("odinsgate.net:43594", "odinsgate.net:43595"), guard.endpoints(), "hosts lowercased");

		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://odinsgate.net:43594")));
		assertEquals(List.of(Proxy.NO_PROXY),
			guard.select(URI.create("http://ODINSGATE.net:43595/launcher/jav_config.ws?x=1")));
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://127.0.0.1:5000")), "loopback");
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("http://localhost:8080/x")), "loopback");

		Proxy otherPort = refused(guard.select(URI.create("socket://odinsgate.net:443")));
		assertEquals(Proxy.Type.SOCKS, otherPort.type(), "a plain socket is sent to a SOCKS proxy");
		assertEquals(guard.refuserAddress(), otherPort.address());
		Proxy wiki = refused(guard.select(URI.create("https://crowdsource.runescape.wiki/runelite/submit?a=b")));
		assertEquals(Proxy.Type.HTTP, wiki.type(), "a URL goes to an HTTP proxy, which also tunnels https");
		assertEquals(guard.refuserAddress(), wiki.address());
		assertEquals(Proxy.Type.HTTP, refused(guard.select(URI.create("http://static.runelite.net/x"))).type());
		assertEquals(Proxy.Type.HTTP, refused(guard.select(URI.create("ftp://odinsgate.net/x"))).type(),
			"an unknown scheme without a port is refused too");

		// The refuser itself must be reachable, or the proxy could never be dialed.
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://127.0.0.1:"
			+ guard.refuserAddress().getPort())));

		guard.select(URI.create("https://crowdsource.runescape.wiki/runelite/submit?a=b"));
		String text = logText();
		assertTrue(text.contains("[guard] allowed odinsgate.net:43594\n"), text);
		assertTrue(text.contains("[guard] allowed odinsgate.net:43595\n"), text);
		assertTrue(text.contains("[guard] refused socket://odinsgate.net:443\n"), text);
		assertTrue(text.contains("[guard] refused https://crowdsource.runescape.wiki:443\n"), text);
		assertTrue(text.contains("[guard] refused ftp://odinsgate.net:-1\n"), text);
		assertFalse(text.contains("/runelite/submit") || text.contains("a=b"), "never a path or a query: " + text);
		assertEquals(1, text.split("refused https://crowdsource.runescape.wiki:443", -1).length - 1,
			"each endpoint once");
	}

	@Test
	public void onceInstalledARefusedConnectionFailsAtOnceThroughEveryApiAndAnAllowedOneConnects() throws Exception
	{
		try (ServerSocket ours = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
		{
			guard = ConnectGuard.install(List.of("odinsgate.net:43594"), log);
			assertTrue(ProxySelector.getDefault() == guard);
			assertEquals("10", Security.getProperty("networkaddress.cache.ttl"),
				"the JVM's name cache keeps an answer no longer than the guard's window");

			// Allowed by the loopback rule: a direct connection, which the listener sees.
			try (Socket socket = new Socket())
			{
				socket.connect(new InetSocketAddress(ours.getInetAddress(), ours.getLocalPort()), 5000);
				assertTrue(socket.isConnected());
				try (Socket accepted = ours.accept())
				{
					assertTrue(accepted.isConnected());
				}
			}
			assertEquals(0, guard.refusedConnections());

			// A plain socket to anywhere else: Java dials the SOCKS proxy, which closes, and the connect fails.
			assertThrows(IOException.class, () ->
			{
				try (Socket socket = new Socket())
				{
					socket.connect(new InetSocketAddress(NOWHERE, 9), 5000);
				}
			});
			assertEquals(1, guard.refusedConnections(), "the attempt reached the refuser, not the address");

			// A URL connection: the HTTP proxy closes, the request fails.
			assertThrows(IOException.class, () ->
			{
				HttpURLConnection connection = (HttpURLConnection) URI.create("http://" + NOWHERE + ":9/x").toURL()
					.openConnection();
				connection.setConnectTimeout(5000);
				connection.setReadTimeout(5000);
				connection.getResponseCode();
			});
			assertTrue(guard.refusedConnections() >= 2, "refused " + guard.refusedConnections());

			// java.net.http asks the same selector.
			int before = guard.refusedConnections();
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			assertThrows(IOException.class, () -> client.send(
				HttpRequest.newBuilder(URI.create("http://" + NOWHERE + ":9/x")).timeout(Duration.ofSeconds(5)).build(),
				HttpResponse.BodyHandlers.discarding()));
			assertTrue(guard.refusedConnections() > before, "refused " + guard.refusedConnections());

			String text = logText();
			assertTrue(text.contains("[guard] refused socket://" + NOWHERE + ":9\n"), text);
			assertTrue(text.contains("[guard] refused http://" + NOWHERE + ":9\n"), text);
		}
	}

	@Test
	public void anAllowedNameIsAllowedByTheAddressesItResolvesTo() throws Exception
	{
		InetAddress local = InetAddress.getLocalHost();
		Assumptions.assumeFalse(local.isLoopbackAddress(), "this machine's name resolves to loopback");
		Assumptions.assumeFalse(local.getHostName().equals(local.getHostAddress()), "no name for this machine");
		guard = new ConnectGuard(List.of(local.getHostName() + ":43594"), log);
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://" + local.getHostAddress() + ":43594")),
			"the address the client resolved the allowed name to");
		assertEquals(Proxy.Type.SOCKS,
			refused(guard.select(URI.create("socket://" + local.getHostAddress() + ":43595"))).type(),
			"the same address on another port is not allowed");
	}

	@Test
	public void anAddressStandsForAnAllowedNameOnlyWhileTheNameResolvesToIt() throws Exception
	{
		AtomicReference<String> address = new AtomicReference<>("192.0.2.10");
		AtomicLong now = new AtomicLong();
		AtomicInteger lookups = new AtomicInteger();
		ConnectGuard.Resolver resolver = host ->
		{
			lookups.incrementAndGet();
			if (!host.equals("odinsgate.net") || address.get() == null)
			{
				throw new UnknownHostException(host);
			}
			return new InetAddress[]{InetAddress.getByName(address.get())}; // a literal: no look-up
		};
		guard = new ConnectGuard(List.of("odinsgate.net:43594"), log, resolver, now::get);
		assertEquals(1, lookups.get(), "looked up once when made");
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://192.0.2.10:43594")));
		assertEquals(1, lookups.get(), "a fresh look-up stands");

		// The name moves: its old address stands until the look-up is old, then is refused and the new one allowed.
		address.set("192.0.2.11");
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://192.0.2.10:43594")));
		assertEquals(1, lookups.get(), "not looked up again within the window");
		now.addAndGet(ConnectGuard.RESOLVE_AGAIN_AFTER_NANOS);
		assertEquals(Proxy.Type.SOCKS, refused(guard.select(URI.create("socket://192.0.2.10:43594"))).type(),
			"the address the name left is no longer allowed");
		assertEquals(2, lookups.get(), "looked up again once the last look-up was old");
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://192.0.2.11:43594")));
		assertEquals(2, lookups.get(), "a known address within the window asks for no look-up");

		// A name that stops resolving keeps the addresses it last had.
		address.set(null);
		now.addAndGet(ConnectGuard.RESOLVE_AGAIN_AFTER_NANOS);
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://192.0.2.11:43594")));
		assertEquals(3, lookups.get());
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://odinsgate.net:43594")), "by name");

		// A name not on the list is never allowed and never looked up.
		now.addAndGet(ConnectGuard.RESOLVE_AGAIN_AFTER_NANOS);
		assertEquals(Proxy.Type.SOCKS, refused(guard.select(URI.create("socket://other.example:43594"))).type());
		assertEquals(3, lookups.get());
	}

	@Test
	public void anAttemptDuringALookUpWaitsForItRatherThanTakingTheOldAddresses() throws Exception
	{
		AtomicReference<String> address = new AtomicReference<>("192.0.2.10");
		AtomicLong now = new AtomicLong();
		AtomicBoolean slow = new AtomicBoolean();
		CountDownLatch inLookUp = new CountDownLatch(1);
		CountDownLatch letLookUpFinish = new CountDownLatch(1);
		ConnectGuard.Resolver resolver = host ->
		{
			if (slow.get())
			{
				inLookUp.countDown();
				try
				{
					letLookUpFinish.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
			}
			return new InetAddress[]{InetAddress.getByName(address.get())};
		};
		guard = new ConnectGuard(List.of("odinsgate.net:43594"), log, resolver, now::get);
		assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://192.0.2.10:43594")));

		// The name moves and the window passes; the next look-up is slow, and a second attempt arrives during it.
		address.set("192.0.2.11");
		now.addAndGet(ConnectGuard.RESOLVE_AGAIN_AFTER_NANOS);
		slow.set(true);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try
		{
			Future<List<Proxy>> first = pool.submit(() -> guard.select(URI.create("socket://192.0.2.10:43594")));
			assertTrue(inLookUp.await(10, TimeUnit.SECONDS), "the first attempt is in the look-up");
			Future<List<Proxy>> second = pool.submit(() -> guard.select(URI.create("socket://192.0.2.10:43594")));
			assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS),
				"the second attempt waits for the look-up instead of taking the old addresses");
			letLookUpFinish.countDown();
			assertEquals(Proxy.Type.SOCKS, refused(first.get(10, TimeUnit.SECONDS)).type(), "the old address is gone");
			assertEquals(Proxy.Type.SOCKS, refused(second.get(10, TimeUnit.SECONDS)).type(), "for both");
			assertEquals(List.of(Proxy.NO_PROXY), guard.select(URI.create("socket://192.0.2.11:43594")));
		}
		finally
		{
			letLookUpFinish.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	public void endpointsAreValidatedAndNoneAtAllIsRefused()
	{
		for (String bad : List.of("odinsgate.net", "odinsgate.net:", ":43594", "odinsgate.net:0", "odinsgate.net:70000",
			"odinsgate.net:x"))
		{
			assertThrows(IllegalArgumentException.class, () -> ConnectGuard.endpoint(bad), bad);
		}
		assertEquals("odinsgate.net:43594", ConnectGuard.endpoint("ODINSGATE.net:43594"));
		assertEquals("::1:443", ConnectGuard.endpoint("[::1]:443"));
		assertThrows(IllegalArgumentException.class, () -> new ConnectGuard(List.of(), log));
		assertTrue(ConnectGuard.isLoopback("127.0.0.1") && ConnectGuard.isLoopback("127.1.2.3")
			&& ConnectGuard.isLoopback("localhost") && ConnectGuard.isLoopback("::1"));
		assertFalse(ConnectGuard.isLoopback("odinsgate.net") || ConnectGuard.isLoopback("192.168.0.210")
			|| ConnectGuard.isLoopback("127.example.com") || ConnectGuard.isLoopback("127.0.0.999"),
			"a name is never loopback, whatever it starts with, and four numbers over 255 are a name");
		assertTrue(ConnectGuard.isIpv4("192.168.0.210") && ConnectGuard.isLiteral("::1")
			&& ConnectGuard.isLiteral("2001:db8::1"));
		assertFalse(ConnectGuard.isIpv4("192.168.0.256") || ConnectGuard.isIpv4("1.2.3")
			|| ConnectGuard.isIpv4("1.2.3.4.5") || ConnectGuard.isLiteral("odinsgate.net"));
	}
}
