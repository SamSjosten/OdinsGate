package net.odinsgate.launcher;

import com.sun.net.httpserver.HttpServer;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * {@code --self-test}: what CI runs on every built installer, on the system it is for, and what a friend can run to
 * check an installation. Nothing real is dialed and nothing downloaded; a fake server on this machine stands in for
 * ours. In order: the bundled runtime still has its java (jpackage strips it unless the runtime is made first, as
 * package.gradle does); the server check, on the runtime's HTTP client, finds the fake server ready; the game's
 * command line, exactly as a launch builds it ({@link ClientCommand}), starts a child JVM from that java on this
 * code's jars, whose {@link Loader} sets the connect guard, reaches the fake server through it and is refused
 * elsewhere; and where there is a display, the window opens with its title and closes again (the frame itself is
 * the one piece of the window no headless test can show). Each step logs {@code [self-test] ok} or
 * {@code [self-test] FAIL}; the exit code is 0 when all passed, else {@value #EXIT_FAILED}.
 */
public final class SelfTest
{
	static final int EXIT_FAILED = 70;
	/** How long the child JVM gets to start, connect twice and exit. */
	static final Duration CHILD_TIMEOUT = Duration.ofSeconds(60);
	/** Where the self-test keeps its game home, runtime config and the child's output, under the data folder. */
	static final String DIR = "self-test";
	static final String CHILD_LOG = "self-test-child.log";
	static final int REVISION = 241;
	private static final String MODULUS = "ab".repeat(128);
	private static final String COMMIT = "0".repeat(40);
	private static final String SHA256 = "0".repeat(64);
	private static final String STATUS = "{\"up\":true,\"revision\":" + REVISION + ",\"commit\":\"self-test\","
		+ "\"players\":0,\"acceptingLogins\":true,\"js5Connections\":0}";

	private final Platform platform;
	private final Launch.Installed installed;
	private final Path dataDir;
	private final PrintStream log;
	private final List<String> failures = new ArrayList<>();

	private SelfTest(Platform platform, Launch.Installed installed, Path dataDir, PrintStream log)
	{
		this.platform = platform;
		this.installed = installed;
		this.dataDir = dataDir;
		this.log = log;
	}

	/**
	 * The whole self-test, logged to {@code log}; the exit code.
	 *
	 * @param installed this JVM's runtime and class path ({@link Main#thisJvm()}), which the child is started from
	 * @param dataDir the launcher's data folder; the self-test's files go under {@value #DIR} in it
	 */
	static int run(Platform platform, Launch.Installed installed, Path dataDir, PrintStream log)
	{
		SelfTest test = new SelfTest(platform, installed, dataDir, log);
		log.println("[self-test] launcher " + Main.VERSION + " on " + platform.os() + " " + platform.arch()
			+ ", runtime " + installed.runtimeDir() + ", data in " + dataDir);
		Path java = test.runtime();
		FakeServer server = null;
		try
		{
			server = FakeServer.start();
			test.serverCheck(server);
			if (java != null)
			{
				test.childJvm(java, server);
			}
		}
		catch (IOException e)
		{
			test.fail("the fake server could not start: " + e.getMessage());
		}
		finally
		{
			if (server != null)
			{
				server.stop();
			}
		}
		test.window();
		if (test.failures.isEmpty())
		{
			log.println("[self-test] passed");
			return 0;
		}
		log.println("[self-test] FAILED: " + test.failures.size() + " step(s): " + String.join("; ", test.failures));
		return EXIT_FAILED;
	}

	/** The bundled runtime's java, or null (and a failure) when it was stripped out. */
	private Path runtime()
	{
		Path java = platform.javaExecutable(installed.runtimeDir());
		if (!Files.isRegularFile(java))
		{
			fail("no java at " + java + ": the bundled runtime has no commands, so no game could be started");
			return null;
		}
		ok("the runtime at " + installed.runtimeDir() + " has its java");
		return java;
	}

	/** The real check, on this runtime's HTTP client and sockets, against the fake server: it must come up ready. */
	private void serverCheck(FakeServer server)
	{
		ServerCheck.Result result;
		try
		{
			Manifest manifest = Manifest.parse(manifest(server));
			result = new ServerCheck(server.host(), server.httpPort(), server.gamePort(), ServerCheck.DEFAULT_TIMEOUT,
				URI.create("http://" + server.host() + ":" + server.httpPort() + "/")).check(manifest);
		}
		catch (ManifestException e)
		{
			fail("the self-test's own manifest doesn't parse: " + e.getMessage());
			return;
		}
		if (result instanceof ServerCheck.Result.Ready ready)
		{
			ok("the server check finds the fake server at " + server.host() + ":" + server.gamePort()
				+ " ready at revision " + ready.revision());
		}
		else
		{
			fail("the server check didn't find the fake server ready: " + result);
		}
	}

	/**
	 * The game's command line as a launch builds it, with the loader's self-test in place of the jars: the child
	 * must start from the bundled java, print {@value Loader#SELF_TEST_OK} and exit 0 within {@link #CHILD_TIMEOUT}.
	 */
	private void childJvm(Path java, FakeServer server)
	{
		Path dir = dataDir.resolve(DIR);
		Path childLog = dir.resolve(CHILD_LOG);
		List<String> command;
		try
		{
			Files.createDirectories(dir);
			Files.deleteIfExists(childLog);
			Manifest.Server fake = new Manifest.Server(server.host(), server.gamePort(), server.httpPort(), MODULUS);
			ClientCommand.Spec spec = new ClientCommand.Spec(platform, installed.runtimeDir(), List.of(),
				dir.resolve(Launch.GAME_HOME), RuntimeConfig.write(dir.resolve(Launch.RTCONF)), fake.host(),
				fake.httpPort(), installed.loaderClasspath(), Loader.class.getName(), Loader.selfTestArguments(fake),
				false);
			command = ClientCommand.build(spec);
		}
		catch (IOException e)
		{
			fail("the self-test's files could not be written under " + dir + ": " + e.getMessage());
			return;
		}
		Process child;
		try
		{
			child = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(childLog.toFile())
				.start();
		}
		catch (IOException e)
		{
			fail("the child JVM could not be started from " + java + ": " + e.getMessage());
			return;
		}
		boolean exited;
		try
		{
			exited = child.waitFor(CHILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			exited = false;
		}
		if (!exited)
		{
			child.destroyForcibly();
			fail("the child JVM didn't exit within " + CHILD_TIMEOUT.toSeconds() + " seconds");
		}
		List<String> lines;
		try
		{
			lines = Files.readAllLines(childLog, StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			lines = List.of("(its output could not be read: " + e.getMessage() + ")");
		}
		lines.forEach(line -> log.println("[self-test]   child: " + line));
		if (!exited)
		{
			return;
		}
		boolean said = lines.stream().anyMatch(line -> line.startsWith(Loader.SELF_TEST_OK));
		if (child.exitValue() == 0 && said)
		{
			ok("the child JVM from " + java + " reached the fake server through the guard and exited 0");
		}
		else
		{
			fail("the child JVM exited with " + child.exitValue() + (said ? "" : " without saying "
				+ Loader.SELF_TEST_OK) + "; its output is above and in " + childLog);
		}
	}

	/** The frame opens with the panel and the app's title, and closes; not a failure where there is no display. */
	private void window()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			log.println("[self-test] no display here, so the window isn't opened (not a failure)");
			return;
		}
		boolean[] shown = new boolean[1];
		String[] title = new String[1];
		try
		{
			SwingUtilities.invokeAndWait(() ->
			{
				JFrame frame = new JFrame(Names.APP_NAME);
				LauncherPanel panel = new LauncherPanel(() -> { }, () -> { }, () -> { });
				panel.render(new Screen.View(Screen.Phase.CHECKING, "Self-test", "The window opens and closes.",
					true, false, false, "", true));
				frame.setContentPane(panel);
				frame.pack();
				frame.setLocationRelativeTo(null);
				frame.setVisible(true);
				shown[0] = frame.isShowing();
				title[0] = frame.getTitle();
				frame.dispose();
			});
		}
		catch (Exception e)
		{
			fail("the window could not open: " + e);
			return;
		}
		if (shown[0] && Names.APP_NAME.equals(title[0]))
		{
			ok("the window opened as \"" + title[0] + "\" and closed");
		}
		else
		{
			fail("the window " + (shown[0] ? "opened titled \"" + title[0] + "\"" : "never showed"));
		}
	}

	private void ok(String what)
	{
		log.println("[self-test] ok " + what);
	}

	private void fail(String what)
	{
		failures.add(what);
		log.println("[self-test] FAIL " + what);
	}

	/** A manifest the check can read: the fake server, and a pin for the revision it reports. */
	static String manifest(FakeServer server)
	{
		return "{ \"format\": " + Manifest.FORMAT + ", \"version\": \"0.0.0\", \"minBootstrapVersion\": \"0.0.0\","
			+ " \"server\": { \"host\": \"" + server.host() + "\", \"gamePort\": " + server.gamePort()
			+ ", \"httpPort\": " + server.httpPort() + ", \"modulus\": \"" + MODULUS + "\" },"
			+ " \"pins\": [ { \"revision\": " + REVISION + ", \"runelite\": \"0.0.0\", \"commit\": \"" + COMMIT
			+ "\", \"bootstrapSha256\": \"" + SHA256 + "\" } ],"
			+ " \"files\": [ { \"path\": \"lib/odinsgate-launcher-0.0.0.jar\", \"sha256\": \"" + SHA256
			+ "\", \"size\": 1 } ] }";
	}

	/**
	 * Our server on loopback, as far as a check and the child look: {@code /status.json} up at {@link #REVISION}
	 * and taking logins, {@code /proxy-targets.yaml} with the manifest's key, and a game port that accepts and
	 * closes. Bound to {@value #LOOPBACK} by the numbers, never to {@code InetAddress.getLoopbackAddress()}: with
	 * {@code -Djava.net.preferIPv6Addresses=true} (a friend's {@code JAVA_TOOL_OPTIONS}, say) that is {@code ::1},
	 * which the manifest's host rule doesn't take and a URL would need in brackets.
	 */
	static final class FakeServer
	{
		static final String LOOPBACK = "127.0.0.1";
		private static final byte[] LOOPBACK_BYTES = {127, 0, 0, 1};

		private final HttpServer web;
		private final ServerSocket game;
		private final Thread accepting;

		private FakeServer(HttpServer web, ServerSocket game)
		{
			this.web = web;
			this.game = game;
			accepting = new Thread(() ->
			{
				while (!game.isClosed())
				{
					try (Socket client = game.accept())
					{
						client.getOutputStream().flush();
					}
					catch (IOException e)
					{
						// Closed, or a client gone: either way, the next accept or the end.
					}
				}
			}, "self-test-game-port");
			accepting.setDaemon(true);
		}

		static FakeServer start() throws IOException
		{
			InetAddress loopback = InetAddress.getByAddress(LOOPBACK, LOOPBACK_BYTES);
			HttpServer web = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
			web.createContext("/", exchange ->
			{
				String body = switch (exchange.getRequestURI().getPath())
				{
					case "/status.json" -> STATUS;
					case "/proxy-targets.yaml" -> "config:\n  - name: self-test\n    modulus: " + MODULUS + "\n";
					default -> null;
				};
				if (body == null)
				{
					exchange.sendResponseHeaders(404, -1);
				}
				else
				{
					byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(200, bytes.length);
					exchange.getResponseBody().write(bytes);
				}
				exchange.close();
			});
			web.start();
			ServerSocket game = new ServerSocket(0, 50, loopback);
			FakeServer server = new FakeServer(web, game);
			server.accepting.start();
			return server;
		}

		String host()
		{
			return LOOPBACK;
		}

		int httpPort()
		{
			return web.getAddress().getPort();
		}

		int gamePort()
		{
			return game.getLocalPort();
		}

		void stop()
		{
			web.stop(0);
			try
			{
				game.close();
			}
			catch (IOException e)
			{
				// Nothing to do with a port that won't close on the way out.
			}
		}
	}
}
