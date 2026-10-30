package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The weekly pin health check against a fake static.runelite.net, repository and runtime config on loopback, signed
 * by a throwaway RSA key: a healthy pin passes with HEAD requests only, and each way a pin can go stale fails with
 * a line that says so.
 */
public class PinHealthTest
{
	private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
	private static final KeyPair RUNELITE = generate();
	private static final String COMMIT = "cd".repeat(20);
	private static final byte[] CLIENT_JAR = "the client jar's bytes".getBytes(StandardCharsets.UTF_8);
	private static final byte[] WIN_NATIVES = "the windows natives".getBytes(StandardCharsets.UTF_8);
	private static final byte[] MAC_ARM_NATIVES = "the apple chip natives".getBytes(StandardCharsets.UTF_8);
	private static final byte[] X86_NATIVES = "the 32-bit natives nobody runs".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path dir;

	private HttpServer web;
	private String base;
	private final Map<String, byte[]> served = new HashMap<>();
	/** A served size that differs from the body, for a HEAD that says the jar changed. */
	private final Map<String, Long> sizes = new HashMap<>();
	private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
	private Manifest.Pin pin;
	private Path pins;

	private static KeyPair generate()
	{
		try
		{
			KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
			keys.initialize(2048);
			return keys.generateKeyPair();
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private static byte[] sign(KeyPair key, byte[] data)
	{
		try
		{
			Signature signer = Signature.getInstance("SHA256withRSA");
			signer.initSign(key.getPrivate());
			signer.update(data);
			return signer.sign();
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	@BeforeEach
	public void start() throws IOException
	{
		web = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
		web.createContext("/", exchange ->
		{
			String path = exchange.getRequestURI().getPath();
			hits.computeIfAbsent(exchange.getRequestMethod() + " " + path, p -> new AtomicInteger()).incrementAndGet();
			byte[] body = served.get(path);
			if (body == null)
			{
				exchange.sendResponseHeaders(404, -1);
			}
			else if (exchange.getRequestMethod().equals("HEAD"))
			{
				long size = sizes.getOrDefault(path, (long) body.length);
				exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
				exchange.sendResponseHeaders(200, -1);
			}
			else
			{
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream o = exchange.getResponseBody())
				{
					o.write(body);
				}
			}
			exchange.close();
		});
		web.start();
		base = "http://" + LOOPBACK.getHostAddress() + ":" + web.getAddress().getPort();
		byte[] bootstrap = ("{\"version\":\"1.13.4\",\"clientJvm17Arguments\":[\"-Xmx768m\"],\"artifacts\":["
			+ jar("client.jar", CLIENT_JAR, null) + ","
			+ jar("natives-win.jar", WIN_NATIVES, "{\"name\":\"win\"}") + ","
			+ jar("natives-mac-arm.jar", MAC_ARM_NATIVES, "{\"name\":\"macos\",\"arch\":\"aarch64\"}") + ","
			+ jar("natives-x86.jar", X86_NATIVES, "{\"name\":\"win\",\"arch\":\"x86\"}")
			+ "]}").getBytes(StandardCharsets.UTF_8);
		pin = new Manifest.Pin(241, "1.13.4", COMMIT, RuneLiteCache.sha256(bootstrap));
		served.put("/static/" + COMMIT + "/bootstrap.json", bootstrap);
		served.put("/static/" + COMMIT + "/bootstrap.json.sha256", sign(RUNELITE, bootstrap));
		served.put("/jars/client.jar", CLIENT_JAR);
		served.put("/jars/natives-win.jar", WIN_NATIVES);
		served.put("/jars/natives-mac-arm.jar", MAC_ARM_NATIVES);
		served.put("/jars/natives-x86.jar", X86_NATIVES);
		served.put("/config.json", "{\"outdatedClientVersions\":[\"1.12.31\"],\"updateRollout\":1.0}"
			.getBytes(StandardCharsets.UTF_8));
		pins = dir.resolve("runelite-pins.json");
		writePins(pin);
	}

	@AfterEach
	public void stop()
	{
		web.stop(0);
	}

	private String jar(String name, byte[] bytes, String platform)
	{
		return "{\"name\":\"" + name + "\",\"path\":\"" + base + "/jars/" + name + "\",\"size\":" + bytes.length
			+ ",\"hash\":\"" + RuneLiteCache.sha256(bytes) + "\""
			+ (platform == null ? "" : ",\"platform\":[" + platform + "]") + "}";
	}

	private void writePins(Manifest.Pin pin) throws IOException
	{
		Files.writeString(pins, "{\"pins\":[{\"revision\":" + pin.revision() + ",\"runelite\":\"" + pin.runelite()
			+ "\",\"commit\":\"" + pin.commit() + "\",\"bootstrapSha256\":\"" + pin.bootstrapSha256()
			+ "\",\"source\":\"a test\"}]}\n");
	}

	private int run()
	{
		return PinHealth.run(new String[]{"--pins", pins.toString(), "--static-base", base + "/static/",
			"--config", base + "/config.json"}, List.of(RUNELITE.getPublic()), out);
	}

	private String output()
	{
		return outBytes.toString(StandardCharsets.UTF_8);
	}

	private int hits(String methodAndPath)
	{
		AtomicInteger count = hits.get(methodAndPath);
		return count == null ? 0 : count.get();
	}

	@Test
	public void aHealthyPinPassesWithHeadRequestsOnly()
	{
		assertEquals(0, run(), output());
		String out = output();
		assertTrue(out.contains("ok   revision 241 (RuneLite 1.13.4 at cdcdcdcd): the bootstrap carries"), out);
		assertTrue(out.contains("client.jar answers HEAD with its " + CLIENT_JAR.length + " bytes"), out);
		assertTrue(out.contains("natives-win.jar answers HEAD"), out);
		assertTrue(out.contains("natives-mac-arm.jar answers HEAD"), out);
		assertFalse(out.contains("natives-x86.jar"), "no system the launcher runs on wants the x86 natives: " + out);
		assertTrue(out.contains("doesn't list 1.13.4 as outdated"), out);
		assertTrue(out.contains("pin health: every pin is healthy (1 checked)"), out);
		assertEquals(1, hits("GET /static/" + COMMIT + "/bootstrap.json"));
		assertEquals(1, hits("GET /config.json"));
		assertEquals(1, hits("HEAD /jars/client.jar"));
		assertEquals(1, hits("HEAD /jars/natives-win.jar"));
		assertEquals(0, hits("GET /jars/client.jar"), "a jar is asked about, never downloaded");
		assertEquals(0, hits("GET /jars/natives-win.jar"));
		assertEquals(0, hits("GET /jars/natives-mac-arm.jar"));
		assertTrue(hits.keySet().stream().noneMatch(key -> key.startsWith("GET /jars/")), hits.toString());
	}

	@Test
	public void aJarGoneFromTheRepositoryFails()
	{
		served.remove("/jars/natives-win.jar");
		assertEquals(1, run(), output());
		assertTrue(output().contains("FAIL revision 241 (RuneLite 1.13.4 at cdcdcdcd): natives-win.jar answered 404 at "
			+ base + "/jars/natives-win.jar"), output());
		assertTrue(output().contains("pin health: FAILED"), output());
	}

	@Test
	public void aJarWhoseSizeChangedFails()
	{
		sizes.put("/jars/client.jar", CLIENT_JAR.length + 7L);
		assertEquals(1, run(), output());
		assertTrue(output().contains("FAIL revision 241 (RuneLite 1.13.4 at cdcdcdcd): client.jar is "
			+ (CLIENT_JAR.length + 7) + " bytes at " + base + "/jars/client.jar, the bootstrap says "
			+ CLIENT_JAR.length), output());
	}

	@Test
	public void anOutdatedListNamingThePinFails()
	{
		served.put("/config.json",
			"{\"outdatedClientVersions\":[\"1.12.31\",\"1.13.4\"]}".getBytes(StandardCharsets.UTF_8));
		assertEquals(1, run(), output());
		assertTrue(output().contains(
			"ok   RuneLite's runtime config lists 2 outdated client version(s): 1.12.31, 1.13.4"), output());
		assertTrue(output().contains("FAIL revision 241 (RuneLite 1.13.4 at cdcdcdcd): RuneLite's runtime config lists "
			+ "1.13.4 as outdated"), output());
	}

	@Test
	public void aRuntimeConfigThatCannotBeReadFails()
	{
		served.remove("/config.json");
		assertEquals(1, run(), output());
		assertTrue(output().contains("FAIL RuneLite's runtime config at " + base + "/config.json answered 404"),
			output());
		assertTrue(output().contains("the bootstrap carries"), "the pin's own checks still run: " + output());
		assertFalse(output().contains("as outdated"), output());
	}

	@Test
	public void aRuntimeConfigThatIsntRuneLitesFails()
	{
		served.put("/config.json", "<html>a proxy's page</html>".getBytes(StandardCharsets.UTF_8));
		assertEquals(1, run(), output());
		assertTrue(output().contains("FAIL RuneLite's runtime config at " + base + "/config.json "), output());
	}

	@Test
	public void aBootstrapThatNoLongerCarriesThePinnedHashFails() throws IOException
	{
		writePins(new Manifest.Pin(241, "1.13.4", COMMIT, "ab".repeat(32)));
		assertEquals(1, run(), output());
		assertTrue(output().contains("FAIL revision 241 (RuneLite 1.13.4 at cdcdcdcd): the bootstrap for " + COMMIT
			+ " doesn't carry the pinned SHA-256"), output());
		assertEquals(0, hits("HEAD /jars/client.jar"), "a bootstrap that doesn't verify names no jar to ask about");
	}

	@Test
	public void aBootstrapOfAnotherVersionThanThePinSaysFails() throws IOException
	{
		writePins(new Manifest.Pin(241, "1.13.5", COMMIT, pin.bootstrapSha256()));
		assertEquals(1, run(), output());
		assertTrue(output().contains("FAIL revision 241 (RuneLite 1.13.5 at cdcdcdcd): the bootstrap is RuneLite "
			+ "1.13.4, the pin says 1.13.5"), output());
	}

	@Test
	public void aStaticHostThatIsGoneFails() throws IOException
	{
		int dead;
		try (java.net.ServerSocket taken = new java.net.ServerSocket(0, 1, LOOPBACK))
		{
			dead = taken.getLocalPort();
		}
		int code = PinHealth.run(new String[]{"--pins", pins.toString(), "--static-base",
			"http://" + LOOPBACK.getHostAddress() + ":" + dead + "/static/", "--config", base + "/config.json"},
			List.of(RUNELITE.getPublic()), out);
		assertEquals(1, code, output());
		assertTrue(output().contains("FAIL revision 241 (RuneLite 1.13.4 at cdcdcdcd): "), output());
	}

	@Test
	public void aRepositoryThatAcceptsAndNeverAnswersFailsAfterItsOwnShortTimeout() throws Exception
	{
		try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 50, LOOPBACK))
		{
			// A bootstrap whose one jar sits on the silent port; the other checks are as before.
			String silentJar = "{\"name\":\"client.jar\",\"path\":\"http://" + LOOPBACK.getHostAddress() + ":"
				+ silent.getLocalPort() + "/client.jar\",\"size\":" + CLIENT_JAR.length + ",\"hash\":\""
				+ RuneLiteCache.sha256(CLIENT_JAR) + "\"}";
			byte[] bootstrap = ("{\"version\":\"1.13.4\",\"clientJvm17Arguments\":[],\"artifacts\":["
				+ silentJar + "]}").getBytes(StandardCharsets.UTF_8);
			served.put("/static/" + COMMIT + "/bootstrap.json", bootstrap);
			served.put("/static/" + COMMIT + "/bootstrap.json.sha256", sign(RUNELITE, bootstrap));
			List<Manifest.Pin> table =
				List.of(new Manifest.Pin(241, "1.13.4", COMMIT, RuneLiteCache.sha256(bootstrap)));
			long started = System.nanoTime();
			boolean healthy = new PinHealth(base + "/static/", base + "/config.json", List.of(RUNELITE.getPublic()),
				Duration.ofSeconds(5), Duration.ofMillis(300), out).check(table);
			long tookMillis = (System.nanoTime() - started) / 1_000_000;
			assertFalse(healthy, output());
			assertTrue(output().contains("FAIL revision 241 (RuneLite 1.13.4 at cdcdcdcd): client.jar at http://"),
				output());
			assertTrue(tookMillis < 4000, "the jar's own timeout, not the download one: " + tookMillis + " ms");
			assertTrue(output().contains("doesn't list 1.13.4 as outdated"), "the rest still ran: " + output());
		}
	}

	@Test
	public void theCommittedPinTableReadsAndAgreesWithTheProvenPin() throws IOException
	{
		// The table the check runs against weekly (launcher/runelite-pins.json; the server keeps a copy in step with
		// it), next to the launcher's committed proof of the 241 entry.
		List<Manifest.Pin> table = PinHealth.readPins(Path.of("runelite-pins.json"));
		assertTrue(table.contains(Fixtures.REAL_PIN), table.toString());
	}

	@Test
	public void aPinsFileThatDoesntReadIsAUsageError() throws IOException
	{
		Files.writeString(pins, "{\"pins\":[]}");
		assertEquals(2, run(), output());
		assertTrue(output().contains(pins + ": \"pins\" is empty"), output());
		Files.writeString(pins, "{\"pins\":[{\"revision\":241,\"runelite\":\"1.13.4\",\"commit\":\"short\","
			+ "\"bootstrapSha256\":\"" + "ab".repeat(32) + "\"}]}");
		assertEquals(2, run(), output());
		assertTrue(output().contains("revision 241: \"commit\" isn't 40 lowercase hex digits"), output());
		Files.writeString(pins, "not json");
		assertEquals(2, run(), output());
		assertTrue(output().contains(pins + ": isn't JSON"), output());
		Files.delete(pins);
		assertEquals(2, run(), output());
		assertTrue(output().lines().anyMatch(line -> line.startsWith(pins + ": ")),
			"a missing file is named: " + output());
	}

	@Test
	public void theCommandLineIsChecked()
	{
		assertEquals(2, PinHealth.run(new String[]{}, List.of(), out));
		assertTrue(output().contains(PinHealth.USAGE), output());
		assertEquals(2, PinHealth.run(new String[]{"--pins"}, List.of(), out));
		assertTrue(output().contains("--pins needs a value"), output());
		assertEquals(2, PinHealth.run(new String[]{"--pins", pins.toString(), "--what", "x"}, List.of(), out));
		assertTrue(output().contains("unknown argument --what"), output());
	}

	@Test
	public void everyArtifactIsEachJarOnceAcrossTheSystems() throws Exception
	{
		Bootstrap bootstrap = Bootstrap.parse(new String(served.get("/static/" + COMMIT + "/bootstrap.json"),
			StandardCharsets.UTF_8));
		List<String> names = PinHealth.everyArtifact(bootstrap).stream().map(Bootstrap.Artifact::name).toList();
		assertEquals(List.of("client.jar", "natives-win.jar", "natives-mac-arm.jar"), names);
	}
}
