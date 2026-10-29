package net.odinsgate.launcher;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The child JVM's one rule about the network: the game reaches our server and nothing else. Plain sockets, URL
 * connections, OkHttp and {@code java.net.http} all ask the JVM's default {@link ProxySelector} before they connect,
 * and this is that selector: an endpoint on the allow list (our server's game and HTTP ports, as the loader is
 * told them) or on loopback connects directly; anything else is handed a proxy on 127.0.0.1 that closes every
 * connection at once, so the attempt fails on the spot, the host's name is never even looked up (that would be the
 * proxy's job), and nothing reaches it. RuneLite's plugins do try: its Wiki crowdsourcing plugin, on by default,
 * posts what it sees in game to the Wiki, and the hiscore and price lookups go to Jagex's and the Wiki's servers.
 * Each endpoint is logged once, allowed or refused, never a path or a query.
 *
 * <p>An allowed host named by name is also allowed by the addresses it resolves to, since the client may connect
 * to the address it looked up itself, but only while the name still resolves to them: the names are looked up when
 * the guard is made and again, at most once every few seconds, when an address asks, so a name that moves takes
 * its old address with it. RuneLite's own block list ({@code runelite.jagex.domainblock}) can't be
 * set from outside and applies only outside Jagex's live environment, so this is the block. NIO channels don't
 * ask the selector; RuneLite and the client use none for the network.
 */
public final class ConnectGuard extends ProxySelector implements Closeable
{
	/** Four decimal octets: the shape of an IPv4 literal, whose octets {@link #isIpv4} then checks. */
	private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
	/** How long a look-up of the allowed names stands before an address that asks makes the guard look again. */
	static final long RESOLVE_AGAIN_AFTER_NANOS = 10_000_000_000L;

	/** How a name becomes addresses: {@link InetAddress#getAllByName} outside the tests. */
	interface Resolver
	{
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	private final Set<String> endpoints;
	private final PrintStream log;
	private final ServerSocket refuser;
	private final InetSocketAddress refuserAddress;
	private final Set<String> seen = ConcurrentHashMap.newKeySet();
	private final AtomicInteger refusedConnections = new AtomicInteger();
	/** Each allowed name's addresses with its port: one snapshot, replaced whole by {@link #resolveAll}. */
	private volatile Map<String, Set<String>> resolved = Map.of();
	private final Resolver resolver;
	private final LongSupplier clock;
	private volatile long resolvedAt;

	/**
	 * @param endpoints the endpoints the game may reach, as {@code host:port}
	 * @param log where each endpoint's first use goes
	 */
	public ConnectGuard(List<String> endpoints, PrintStream log) throws IOException
	{
		this(endpoints, log, InetAddress::getAllByName, System::nanoTime);
	}

	/** For the tests: names resolved and time told as the test says. */
	ConnectGuard(List<String> endpoints, PrintStream log, Resolver resolver, LongSupplier clock) throws IOException
	{
		this.resolver = resolver;
		this.clock = clock;
		if (endpoints.isEmpty())
		{
			throw new IllegalArgumentException("a guard with nothing allowed would stop the game itself");
		}
		Set<String> set = ConcurrentHashMap.newKeySet();
		for (String endpoint : endpoints)
		{
			set.add(endpoint(endpoint));
		}
		this.endpoints = set;
		this.log = log;
		refuser = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		refuserAddress = new InetSocketAddress(refuser.getInetAddress(), refuser.getLocalPort());
		Thread thread = new Thread(this::refuse, "connect-refuser");
		thread.setDaemon(true);
		thread.start();
		resolveAll();
	}

	/** Makes the guard and sets it as the JVM's default selector; the previous one is not consulted again. */
	public static ConnectGuard install(List<String> endpoints, PrintStream log) throws IOException
	{
		ConnectGuard guard = new ConnectGuard(endpoints, log);
		ProxySelector.setDefault(guard);
		return guard;
	}

	/** The allowed endpoints, as {@code host:port}, hosts lowercased. */
	public Set<String> endpoints()
	{
		return Set.copyOf(endpoints);
	}

	/** Where the refusing proxy listens. */
	InetSocketAddress refuserAddress()
	{
		return refuserAddress;
	}

	/** How many connections the refusing proxy has closed so far. */
	int refusedConnections()
	{
		return refusedConnections.get();
	}

	@Override
	public List<Proxy> select(URI uri)
	{
		Objects.requireNonNull(uri);
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = host(uri.getHost());
		int port = uri.getPort() != -1 ? uri.getPort() : switch (scheme)
		{
			case "http", "ws" -> 80;
			case "https", "wss" -> 443;
			default -> -1;
		};
		if (host != null && port == refuserAddress.getPort() && isLoopback(host))
		{
			// Java's and OkHttp's own socket to the refusing proxy.
			return List.of(Proxy.NO_PROXY);
		}
		if (host != null && port > 0 && (isLoopback(host) || allowed(host, port)))
		{
			if (seen.add(host + ":" + port))
			{
				log.println("[guard] allowed " + host + ":" + port);
			}
			return List.of(Proxy.NO_PROXY);
		}
		String where = scheme + "://" + (host == null ? "?" : host) + ":" + port;
		if (seen.add(where))
		{
			log.println("[guard] refused " + where);
		}
		return List.of(new Proxy(scheme.equals("socket") ? Proxy.Type.SOCKS : Proxy.Type.HTTP, refuserAddress));
	}

	@Override
	public void connectFailed(URI uri, SocketAddress sa, IOException ioe)
	{
		// A refused connection fails by design; an allowed one that fails is the game's to report.
	}

	@Override
	public void close() throws IOException
	{
		refuser.close();
	}

	static boolean isLoopback(String host)
	{
		return host.equals("localhost") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")
			|| (host.startsWith("127.") && isIpv4(host));
	}

	private boolean allowed(String host, int port)
	{
		String endpoint = host + ":" + port;
		if (endpoints.contains(endpoint))
		{
			return true;
		}
		if (!isLiteral(host))
		{
			// A name not on the list is never allowed; only an address can stand for a listed name.
			return false;
		}
		// An address stands for an allowed name only while the name resolves to it. Once the last look-up is old,
		// look the names up again before answering, whether the address is known (the name may have left it) or
		// not (the name may have moved to it), but never for every attempt.
		refreshIfOld();
		return resolvedContains(endpoint);
	}

	private boolean resolvedContains(String endpoint)
	{
		for (Set<String> addresses : resolved.values())
		{
			if (addresses.contains(endpoint))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Looks the names up again once the last look-up is old. An attempt that arrives while a look-up runs waits
	 * for it here, so none is answered from the addresses the look-up is about to replace.
	 */
	private synchronized void refreshIfOld()
	{
		if (clock.getAsLong() - resolvedAt >= RESOLVE_AGAIN_AFTER_NANOS)
		{
			resolveAll();
		}
	}

	/**
	 * Each allowed name's addresses, with its port, as a new snapshot published whole, then the time of the look-up,
	 * so a reader that sees the new time sees the new addresses. A name that doesn't resolve right now keeps the
	 * addresses it last had, so a DNS hiccup doesn't cut a running game off; it is always allowed by name.
	 */
	private synchronized void resolveAll()
	{
		Map<String, Set<String>> fresh = new HashMap<>();
		for (String endpoint : endpoints)
		{
			int colon = endpoint.lastIndexOf(':');
			String host = endpoint.substring(0, colon);
			String port = endpoint.substring(colon + 1);
			if (isLiteral(host))
			{
				continue;
			}
			try
			{
				Set<String> addresses = new HashSet<>();
				for (InetAddress address : resolver.resolve(host))
				{
					addresses.add(host(address.getHostAddress()) + ":" + port);
				}
				fresh.put(endpoint, Set.copyOf(addresses));
			}
			catch (UnknownHostException e)
			{
				// Offline, or a name that isn't one yet: the last addresses and the name itself stay allowed.
				Set<String> last = resolved.get(endpoint);
				if (last != null)
				{
					fresh.put(endpoint, last);
				}
			}
		}
		resolved = Map.copyOf(fresh);
		resolvedAt = clock.getAsLong();
	}

	/** An IPv4 literal (four octets, each 0-255) or an IPv6 one: an address, never looked up as a name. */
	static boolean isLiteral(String host)
	{
		return isIpv4(host) || host.indexOf(':') >= 0;
	}

	static boolean isIpv4(String host)
	{
		Matcher octets = IPV4.matcher(host);
		if (!octets.matches())
		{
			return false;
		}
		for (int i = 1; i <= 4; i++)
		{
			if (Integer.parseInt(octets.group(i)) > 255)
			{
				return false;
			}
		}
		return true;
	}

	private void refuse()
	{
		while (!refuser.isClosed())
		{
			try
			{
				Socket refused = refuser.accept();
				refusedConnections.incrementAndGet();
				refused.close();
			}
			catch (IOException e)
			{
				// Only this connection is lost; the next is refused the same way.
			}
		}
	}

	/** A host for comparison: lowercase, an IPv6 literal without its brackets or zone. */
	private static String host(String host)
	{
		if (host == null)
		{
			return null;
		}
		String plain = host.toLowerCase(Locale.ROOT);
		if (plain.startsWith("[") && plain.endsWith("]"))
		{
			plain = plain.substring(1, plain.length() - 1);
		}
		int zone = plain.indexOf('%');
		return zone < 0 ? plain : plain.substring(0, zone);
	}

	/** Validates and normalizes one {@code host:port}. */
	static String endpoint(String endpoint)
	{
		int colon = endpoint.lastIndexOf(':');
		if (colon <= 0 || colon == endpoint.length() - 1)
		{
			throw new IllegalArgumentException("an endpoint is host:port, not \"" + endpoint + "\"");
		}
		int port;
		try
		{
			port = Integer.parseInt(endpoint.substring(colon + 1));
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException("an endpoint is host:port, not \"" + endpoint + "\"");
		}
		if (port < 1 || port > 65535)
		{
			throw new IllegalArgumentException("port " + port + " is out of range in \"" + endpoint + "\"");
		}
		return host(endpoint.substring(0, colon)) + ":" + port;
	}
}
