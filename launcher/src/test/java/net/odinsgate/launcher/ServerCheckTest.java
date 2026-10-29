package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every outcome of the pre-Play check, against fake servers on loopback: ready, the server offline or half up, no
 * internet at all, a revision the manifest doesn't know, and a served key that isn't the signed one.
 */
public class ServerCheckTest
{
	private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
	/** Short, so the timeout test doesn't hold the suite; generous enough for loopback not to flake. */
	private static final Duration TIMEOUT = Duration.ofMillis(500);

	private HttpServer web;
	private HttpServer internet;
	private ServerSocket game;
	private String status;
	private String yaml;

	@BeforeEach
	public void start() throws IOException
	{
		status = "{\"up\":true,\"revision\":241,\"commit\":\"abc\",\"players\":3,"
			+ "\"acceptingLogins\":true,\"js5Connections\":7}";
		yaml = "config:\n  - name: OSRSPS\n    modulus: " + ManifestTest.MODULUS + "\n";
		web = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
		web.createContext("/", exchange ->
		{
			String body = switch (exchange.getRequestURI().getPath())
			{
				case "/status.json" -> status;
				case "/proxy-targets.yaml" -> yaml;
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
				try (OutputStream out = exchange.getResponseBody())
				{
					out.write(bytes);
				}
			}
			exchange.close();
		});
		web.start();
		internet = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
		internet.createContext("/", exchange ->
		{
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		internet.start();
		game = new ServerSocket(0, 50, LOOPBACK);
	}

	@AfterEach
	public void stop() throws IOException
	{
		web.stop(0);
		internet.stop(0);
		game.close();
	}

	private ServerCheck.Result check() throws Exception
	{
		return check(web.getAddress().getPort(), game.getLocalPort());
	}

	private ServerCheck.Result check(int httpPort, int gamePort) throws Exception
	{
		URI probe = URI.create("http://" + LOOPBACK.getHostAddress() + ":" + internet.getAddress().getPort() + "/");
		return new ServerCheck(LOOPBACK.getHostAddress(), httpPort, gamePort, TIMEOUT, probe)
			.check(Manifest.parse(ManifestTest.good()));
	}

	/** A port nothing listens on: taken, then given back, so a connection there is refused. */
	private static int deadPort() throws IOException
	{
		try (ServerSocket taken = new ServerSocket(0, 1, LOOPBACK))
		{
			return taken.getLocalPort();
		}
	}

	@Test
	public void bothPortsUpTheSignedKeyAndAKnownRevisionIsReady() throws Exception
	{
		assertEquals(new ServerCheck.Result.Ready(241, 3), check());
	}

	@Test
	public void aServerStillLoadingItsCacheIsOffline() throws Exception
	{
		status = status.replace("\"up\":true", "\"up\":false");
		assertInstanceOf(ServerCheck.Result.Offline.class, check());
	}

	@Test
	public void aFullServerIsNotReadyBecauseItsLoginWouldBeRefused() throws Exception
	{
		status = status.replace("\"acceptingLogins\":true", "\"acceptingLogins\":false");
		assertEquals(new ServerCheck.Result.Full(3), check());
	}

	@Test
	public void aStatusPageThatIsntOursIsOffline() throws Exception
	{
		status = "<html>some proxy's error page</html>";
		assertInstanceOf(ServerCheck.Result.Offline.class, check());
	}

	@Test
	public void aDeadServerWithTheInternetUpIsOffline() throws Exception
	{
		assertInstanceOf(ServerCheck.Result.Offline.class, check(deadPort(), game.getLocalPort()));
	}

	@Test
	public void aDeadServerAndADeadProbeIsNoInternet() throws Exception
	{
		int dead = deadPort();
		URI probe = URI.create("http://" + LOOPBACK.getHostAddress() + ":" + deadPort() + "/");
		ServerCheck.Result result = new ServerCheck(LOOPBACK.getHostAddress(), dead, game.getLocalPort(), TIMEOUT, probe)
			.check(Manifest.parse(ManifestTest.good()));
		assertEquals(new ServerCheck.Result.NoInternet(), result);
	}

	@Test
	public void aServerThatAcceptsButNeverAnswersIsOfflineAfterTheTimeout() throws Exception
	{
		try (ServerSocket silent = new ServerSocket(0, 50, LOOPBACK))
		{
			assertInstanceOf(ServerCheck.Result.Offline.class, check(silent.getLocalPort(), game.getLocalPort()));
		}
	}

	@Test
	public void aDeadGamePortIsOfflineEvenWithStatusUp() throws Exception
	{
		assertInstanceOf(ServerCheck.Result.Offline.class, check(web.getAddress().getPort(), deadPort()));
	}

	@Test
	public void aRevisionTheManifestDoesntPinAsksForTheLauncherUpdate() throws Exception
	{
		status = status.replace("\"revision\":241", "\"revision\":242");
		assertEquals(new ServerCheck.Result.UnknownRevision(242), check());
	}

	@Test
	public void aServedKeyThatIsntTheSignedOneStopsTheLaunch() throws Exception
	{
		yaml = yaml.replace(ManifestTest.MODULUS, "ef".repeat(128));
		assertEquals(new ServerCheck.Result.KeyChanged(), check());
	}

	@Test
	public void aMissingYamlIsAHintNotAStop() throws Exception
	{
		yaml = null;
		assertEquals(new ServerCheck.Result.Ready(241, 3), check());
	}
}
