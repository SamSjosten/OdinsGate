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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * to the address it looked up itself; the names are resolved when the guard is made and again, at most once every
 * few seconds, when an unknown address asks. RuneLite's own block list ({@code runelite.jagex.domainblock}) can't be
 * set from outside and applies only outside Jagex's live environment, so this is the block. NIO channels don't
 * ask the selector; RuneLite and the client use none for the network.
 */
public final class ConnectGuard extends ProxySelector implements Closeable
{
	/** A dotted IPv4 literal, or an IPv6 one, which is never resolved. */
	private static final Pattern LITERAL = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+|.*:.*");
	/** How long a resolved address list stands before an unknown address makes the guard look the name up again. */
	private static final long RESOLVE_AGAIN_AFTER_NANOS = 10_000_000_000L;

	private final Set<String> endpoints;
	private final PrintStream log;
	private final ServerSocket refuser;
	private final InetSocketAddress refuserAddress;
	private final Set<String> seen = ConcurrentHashMap.newKeySet();
	private final AtomicInteger refusedConnections = new AtomicInteger();
	private final Map<String, Set<String>> resolved = new ConcurrentHashMap<>();
	private volatile long resolvedAt;

	/**
	 * @param endpoints the endpoints the game may reach, as {@code host:port}
	 * @param log where each endpoint's first use goes
	 */
	public ConnectGuard(List<String> endpoints, PrintStream log) throws IOException
	{
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
		return host.equals("localhost") || host.startsWith("127.") || host.equals("::1")
			|| host.equals("0:0:0:0:0:0:0:1");
	}

	private boolean allowed(String host, int port)
	{
		String endpoint = host + ":" + port;
		if (endpoints.contains(endpoint))
		{
			return true;
		}
		if (resolvedContains(endpoint))
		{
			return true;
		}
		// An address that may be a newly resolved allowed name: look the names up again, but not for every attempt.
		if (!LITERAL.matcher(host).matches() || System.nanoTime() - resolvedAt < RESOLVE_AGAIN_AFTER_NANOS)
		{
			return false;
		}
		resolveAll();
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

	/** Each allowed name's addresses, with its port; a name that doesn't resolve now stands by its name alone. */
	private void resolveAll()
	{
		resolvedAt = System.nanoTime();
		for (String endpoint : endpoints)
		{
			int colon = endpoint.lastIndexOf(':');
			String host = endpoint.substring(0, colon);
			String port = endpoint.substring(colon + 1);
			if (LITERAL.matcher(host).matches())
			{
				continue;
			}
			try
			{
				Set<String> addresses = ConcurrentHashMap.newKeySet();
				for (InetAddress address : InetAddress.getAllByName(host))
				{
					addresses.add(host(address.getHostAddress()) + ":" + port);
				}
				resolved.put(endpoint, addresses);
			}
			catch (UnknownHostException e)
			{
				// Offline, or a name that isn't one yet: the name itself stays allowed.
			}
		}
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
