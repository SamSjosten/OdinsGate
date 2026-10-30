package net.odinsgate.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.UIManager;

/**
 * The child JVM's entry point: what the launcher's {@link ClientCommand} starts from the bundled runtime. It runs
 * {@link Preflight} over the pinned jars, refuses to go on if any patch point is missing, sets the
 * {@link ConnectGuard} so the game can reach our server and nothing else, and otherwise loads RuneLite through
 * {@link PatchingClassLoader} and calls its main method, the way RuneLite itself starts the client on a Mac, only
 * in a process of its own, so the launcher stays free to watch the server and restart the game.
 *
 * <pre>
 * --jar &lt;path&gt;...       the pinned jars, in bootstrap order (the launcher has hashed them already)
 * --modulus &lt;hex&gt;       the server's RSA key
 * --port &lt;n&gt;            the game port the client must dial
 * --allow &lt;host:port&gt;... the endpoints the game may reach: our server's game and HTTP ports, and RuneLite's own
 *                       Plugin Hub and static data servers
 * --main &lt;class&gt;        RuneLite's main class
 * --                    everything after it is RuneLite's own command line
 * --self-test           first: no jars, no RuneLite; the guard is set for the --allow endpoints, the first is reached
 *                       through it, a host that isn't allowed is refused, and {@value #SELF_TEST_OK} is printed
 *                       ({@link SelfTest}, which starts this child from the bundled runtime)
 * </pre>
 * Exit codes: {@value #EXIT_USAGE} for a bad command line, {@value #EXIT_PATCH} when the pin no longer fits the
 * patches, {@value #EXIT_START} when RuneLite's main method throws. Every line it prints starts with
 * {@code [loader]}, so the launcher's log tells it from RuneLite's.
 */
public final class Loader
{
	public static final String RUNELITE_MAIN = "net.runelite.client.RuneLite";
	public static final String SELF_TEST = "--self-test";
	/**
	 * RuneLite's own servers the game may reach besides ours (Sam's decision, 2026-10-30): the Plugin Hub
	 * ({@code repo.runelite.net}: its signed plugin list and the plugin jars) and the static data
	 * ({@code static.runelite.net}: item and NPC stats). Both are RuneLite's, never the game publisher's; RuneLite
	 * checks the hub's signature itself. Its API ({@code api.runelite.net}) is not needed: the world list, prices and
	 * the rest of {@code runelite.http-service.url} come from our server.
	 */
	public static final List<String> RUNELITE_ENDPOINTS = List.of("repo.runelite.net:443", "static.runelite.net:443");
	public static final String SELF_TEST_OK = "[loader] self-test ok";
	/** A host no packet should ever leave for (TEST-NET-1, never routed): what the guard must refuse on the spot. */
	static final String NOWHERE = "192.0.2.1";
	static final int EXIT_USAGE = 64;
	static final int EXIT_PATCH = 2;
	static final int EXIT_START = 3;
	private static final int SELF_TEST_CONNECT_MILLIS = 5000;
	private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

	/** The parsed command line. */
	public record Options(List<Path> jars, String modulus, int gamePort, List<String> allowed, String mainClass,
		List<String> runeliteArgs)
	{
	}

	private Loader()
	{
	}

	/**
	 * The loader's arguments for {@link ClientCommand.Spec#loaderArgs()}: what {@link #parse} reads back. The game
	 * may reach the server's two ports and {@link #RUNELITE_ENDPOINTS}, and nothing else.
	 */
	public static List<String> arguments(List<Path> jars, Manifest.Server server, String mainClass)
	{
		List<String> args = new ArrayList<>();
		for (Path jar : jars)
		{
			args.add("--jar");
			args.add(jar.toString());
		}
		args.add("--modulus");
		args.add(server.modulus());
		args.add("--port");
		args.add(Integer.toString(server.gamePort()));
		args.add("--allow");
		args.add(server.host() + ":" + server.gamePort());
		args.add("--allow");
		args.add(server.host() + ":" + server.httpPort());
		for (String endpoint : RUNELITE_ENDPOINTS)
		{
			args.add("--allow");
			args.add(endpoint);
		}
		args.add("--main");
		args.add(mainClass);
		return List.copyOf(args);
	}

	/** The arguments of a self-test child: the endpoints the guard allows, as for a game, and no jars. */
	public static List<String> selfTestArguments(Manifest.Server server)
	{
		return List.of(SELF_TEST, "--allow", server.host() + ":" + server.gamePort(),
			"--allow", server.host() + ":" + server.httpPort());
	}

	/** Parses the command line; an {@link IllegalArgumentException} says what is wrong with it. */
	static Options parse(String[] args)
	{
		List<Path> jars = new ArrayList<>();
		String modulus = null;
		int gamePort = 0;
		List<String> allowed = new ArrayList<>();
		String mainClass = null;
		List<String> runeliteArgs = List.of();
		for (int i = 0; i < args.length; i++)
		{
			String arg = args[i];
			if (arg.equals("--"))
			{
				runeliteArgs = List.of(args).subList(i + 1, args.length);
				break;
			}
			if (i + 1 >= args.length)
			{
				throw new IllegalArgumentException(arg + " needs a value");
			}
			String value = args[++i];
			switch (arg)
			{
				case "--jar" -> jars.add(Path.of(value));
				case "--modulus" -> modulus = value;
				case "--port" -> gamePort = Integer.parseInt(value);
				case "--allow" -> allowed.add(ConnectGuard.endpoint(value));
				case "--main" -> mainClass = value;
				default -> throw new IllegalArgumentException("unknown argument " + arg);
			}
		}
		if (jars.isEmpty() || modulus == null || gamePort == 0 || allowed.isEmpty() || mainClass == null)
		{
			throw new IllegalArgumentException("--jar, --modulus, --port, --allow and --main are all needed");
		}
		if (!HEX.matcher(modulus).matches() || gamePort < 1 || gamePort > 65535)
		{
			throw new IllegalArgumentException("the modulus must be hex and the port 1-65535");
		}
		return new Options(List.copyOf(jars), modulus, gamePort, List.copyOf(allowed), mainClass,
			List.copyOf(runeliteArgs));
	}

	public static void main(String[] args)
	{
		if (args.length > 0 && args[0].equals(SELF_TEST))
		{
			System.exit(selfTest(args, System.out));
			return;
		}
		Options options;
		try
		{
			options = parse(args);
		}
		catch (IllegalArgumentException e)
		{
			System.out.println("[loader] " + e.getMessage());
			System.exit(EXIT_USAGE);
			return;
		}
		try
		{
			start(options, System.out, failure ->
			{
				System.out.println("[loader] RuneLite did not start");
				failure.printStackTrace(System.out);
				System.exit(EXIT_START);
			});
		}
		catch (PatchException | IOException e)
		{
			System.out.println("[loader] " + e.getMessage());
			System.exit(EXIT_PATCH);
		}
	}

	/**
	 * The self-test child ({@link SelfTest}): proof that the bundled runtime starts a JVM on this code's jars and
	 * that the guard works in it. The guard is installed as for a game, the first allowed endpoint (the fake game
	 * port) is reached through it, and a connection to {@link #NOWHERE} is refused by the guard itself, counted, so
	 * a guard that wasn't there (and a connect that merely timed out) can't pass. Nothing of RuneLite is loaded.
	 * Returns the exit code: 0, {@value #EXIT_USAGE} for a bad command line, {@value #EXIT_START} when a step
	 * failed.
	 */
	static int selfTest(String[] args, PrintStream log)
	{
		List<String> allowed = new ArrayList<>();
		for (int i = 1; i < args.length; i++)
		{
			if (args[i].equals("--"))
			{
				break;
			}
			if (!args[i].equals("--allow") || i + 1 >= args.length)
			{
				log.println("[loader] self-test: unknown argument " + args[i] + "; only --allow host:port... is read");
				return EXIT_USAGE;
			}
			try
			{
				allowed.add(ConnectGuard.endpoint(args[++i]));
			}
			catch (IllegalArgumentException e)
			{
				log.println("[loader] self-test: " + e.getMessage());
				return EXIT_USAGE;
			}
		}
		if (allowed.isEmpty())
		{
			log.println("[loader] self-test: at least one --allow host:port is needed");
			return EXIT_USAGE;
		}
		String first = allowed.get(0);
		int colon = first.lastIndexOf(':');
		InetSocketAddress target = new InetSocketAddress(first.substring(0, colon),
			Integer.parseInt(first.substring(colon + 1)));
		try
		{
			ConnectGuard guard = ConnectGuard.install(allowed, log);
			try (Socket socket = new Socket())
			{
				socket.connect(target, SELF_TEST_CONNECT_MILLIS);
			}
			log.println("[loader] self-test: reached " + first + " through the guard");
			try (Socket socket = new Socket())
			{
				socket.connect(new InetSocketAddress(NOWHERE, 80), SELF_TEST_CONNECT_MILLIS);
				log.println("[loader] self-test FAILED: " + NOWHERE + ":80 was not refused");
				return EXIT_START;
			}
			catch (IOException expected)
			{
				log.println("[loader] self-test: " + NOWHERE + ":80 refused (" + expected.getMessage() + ")");
			}
			if (guard.refusedConnections() != 1)
			{
				log.println("[loader] self-test FAILED: the guard refused " + guard.refusedConnections()
					+ " connection(s), not the one");
				return EXIT_START;
			}
		}
		catch (IOException e)
		{
			log.println("[loader] self-test FAILED: " + e.getMessage());
			return EXIT_START;
		}
		log.println(SELF_TEST_OK + ": java " + System.getProperty("java.version") + " at "
			+ System.getProperty("java.home"));
		return 0;
	}

	/**
	 * Scans, refuses or patches, sets the guard, and starts RuneLite's main method on a thread of its own, which is
	 * returned so a caller can wait for it. A throw out of that main method goes to {@code onFailure} on that
	 * thread.
	 *
	 * @throws PatchException when the pin no longer fits the patches, before any class is loaded
	 */
	static Thread start(Options options, PrintStream log, Consumer<Throwable> onFailure)
		throws IOException, PatchException
	{
		for (Path jar : options.jars())
		{
			if (!Files.isRegularFile(jar))
			{
				throw new IOException("no jar at " + jar);
			}
		}
		Preflight scan = Preflight.scan(options.jars(), options.modulus(), options.gamePort());
		scan.report().forEach(line -> log.println("[loader] " + line));
		scan.missing().forEach(line -> log.println("[loader] MISSING " + line));
		scan.requireComplete();
		scan.patches().values().forEach(patch ->
			log.println("[loader] will patch " + patch.className().replace('/', '.') + ": " + patch.summary()));
		// Before any RuneLite class loads: from here on the JVM reaches the allowed endpoints and nothing else.
		ConnectGuard guard = ConnectGuard.install(options.allowed(), log);
		log.println("[loader] the game may reach " + String.join(", ", new TreeSet<>(guard.endpoints()))
			+ " and loopback; every other host is refused");
		PatchingClassLoader loader = new PatchingClassLoader(options.jars(), scan.patches());
		String[] runeliteArgs = options.runeliteArgs().toArray(String[]::new);
		Thread thread = new Thread(() ->
		{
			try
			{
				// As RuneLite's own ReflectionLauncher does: Swing loads the look and feel through this when it isn't
				// on the class path. Here, so the event queue it sets up takes this loader as the event dispatch
				// thread's context class loader.
				UIManager.put("ClassLoader", loader);
				Method main = loader.loadClass(options.mainClass()).getMethod("main", String[].class);
				log.println("[loader] starting " + options.mainClass() + " " + String.join(" ", runeliteArgs));
				main.invoke(null, (Object) runeliteArgs);
			}
			catch (InvocationTargetException e)
			{
				onFailure.accept(e.getCause() != null ? e.getCause() : e);
			}
			catch (Throwable e)
			{
				onFailure.accept(e);
			}
		}, "RuneLite");
		// Unlike ReflectionLauncher, which leaves it at the class path's loader: anything that looks classes up
		// through the context class loader then finds RuneLite's, as it would with RuneLite on the class path.
		thread.setContextClassLoader(loader);
		thread.start();
		return thread;
	}
}
