package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The RuneLite cache against a fake static.runelite.net on loopback, signed by a throwaway RSA key made here: a
 * fresh download fills the cache, a filled cache starts offline, and a tampered file - cached or fresh off the
 * network - never reaches the classpath.
 */
public class RuneLiteCacheTest
{
	private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
	private static final Duration TIMEOUT = Duration.ofMillis(500);
	private static final KeyPair RUNELITE = generate();
	private static final String COMMIT = "ab".repeat(20);
	private static final Platform WINDOWS = new Platform(Platform.Os.WINDOWS, Platform.Arch.X64);
	private static final Platform LINUX = new Platform(Platform.Os.LINUX, Platform.Arch.X64);
	private static final byte[] CLIENT_JAR = "the client jar's bytes".getBytes(StandardCharsets.UTF_8);
	private static final byte[] NATIVES_JAR = "the windows natives".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path root;

	private HttpServer web;
	private final Map<String, byte[]> served = new HashMap<>();
	private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
	private final Set<String> stalled = ConcurrentHashMap.newKeySet();
	private final CountDownLatch release = new CountDownLatch(1);
	private byte[] bootstrapBytes;
	private Manifest.Pin pin;

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
			hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
			byte[] body = served.get(path);
			if (body != null && stalled.contains(path))
			{
				// The headers and half the body, then nothing until the test ends: a download that stalls.
				exchange.sendResponseHeaders(200, body.length);
				OutputStream out = exchange.getResponseBody();
				out.write(body, 0, body.length / 2);
				out.flush();
				try
				{
					release.await();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
				exchange.close();
				return;
			}
			if (body == null)
			{
				exchange.sendResponseHeaders(404, -1);
			}
			else
			{
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody())
				{
					out.write(body);
				}
			}
			exchange.close();
		});
		web.start();

		String base = "http://" + LOOPBACK.getHostAddress() + ":" + web.getAddress().getPort();
		bootstrapBytes = ("{\"version\":\"1.13.4\",\"clientJvm17Arguments\":[\"-Xmx768m\"],\"artifacts\":["
			+ jar(base, "client.jar", CLIENT_JAR, null) + ","
			+ jar(base, "natives-win.jar", NATIVES_JAR, "{\"name\":\"win\"}")
			+ "]}").getBytes(StandardCharsets.UTF_8);
		pin = new Manifest.Pin(241, "1.13.4", COMMIT, RuneLiteCache.sha256(bootstrapBytes));
		served.put("/static/" + COMMIT + "/bootstrap.json", bootstrapBytes);
		served.put("/static/" + COMMIT + "/bootstrap.json.sha256", sign(RUNELITE, bootstrapBytes));
		served.put("/jars/client.jar", CLIENT_JAR);
		served.put("/jars/natives-win.jar", NATIVES_JAR);
	}

	@AfterEach
	public void stop()
	{
		release.countDown();
		web.stop(0);
	}

	private static String jar(String base, String name, byte[] bytes, String platforms)
	{
		return "{\"name\":\"" + name + "\",\"path\":\"" + base + "/jars/" + name + "\","
			+ "\"size\":" + bytes.length + ",\"hash\":\"" + RuneLiteCache.sha256(bytes) + "\""
			+ (platforms == null ? "" : ",\"platform\":[" + platforms + "]") + "}";
	}

	private RuneLiteCache cache()
	{
		String base = "http://" + LOOPBACK.getHostAddress() + ":" + web.getAddress().getPort() + "/static/";
		return new RuneLiteCache(root, base, List.of(RUNELITE.getPublic()), TIMEOUT);
	}

	/** {@link #cache()} on a clock that reads {@code now}. */
	private RuneLiteCache cacheAt(Instant now)
	{
		String base = "http://" + LOOPBACK.getHostAddress() + ":" + web.getAddress().getPort() + "/static/";
		return new RuneLiteCache(root, base, List.of(RUNELITE.getPublic()), TIMEOUT, Clock.fixed(now, ZoneOffset.UTC));
	}

	/** A cache whose every fetch would fail: the static base points at a port nothing listens on. */
	private RuneLiteCache offlineCache()
	{
		return new RuneLiteCache(root, "http://" + LOOPBACK.getHostAddress() + ":1/static/",
			List.of(RUNELITE.getPublic()), TIMEOUT);
	}

	@Test
	public void aJarWhoseBodyStallsFailsAtItsDeadlineInsteadOfHanging() throws Exception
	{
		stalled.add("/jars/client.jar");
		RuneLiteCache cache = cache();
		Bootstrap bootstrap = cache.bootstrap(pin);
		// Bounded, so a download that never gives up fails this test instead of hanging the suite: the handler
		// stays stalled until the test ends, which a missing deadline would otherwise wait for forever.
		IOException stall = assertTimeoutPreemptively(Duration.ofMinutes(1),
			() -> assertThrows(IOException.class, () -> cache.jars(pin, bootstrap, WINDOWS)),
			"the download has a deadline");
		assertTrue(stall.getMessage().contains("client.jar didn't arrive whole within"), stall.getMessage());
		assertFalse(Files.exists(root.resolve(COMMIT).resolve("client.jar")), "nothing half-written is kept");
	}

	@Test
	public void fetchingAWarmPinMarksItUsed() throws Exception
	{
		// Filled first and then left alone, so the fetch below writes nothing: only the mark can move the date.
		Bootstrap bootstrap = cache().bootstrap(pin);
		cache().jars(pin, bootstrap, WINDOWS);
		Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
		Files.setLastModifiedTime(root.resolve(COMMIT), FileTime.from(longAgo));

		Instant now = Instant.parse("2026-11-01T12:00:00Z");
		cacheAt(now).jars(pin, cacheAt(now).bootstrap(pin), WINDOWS);
		assertEquals(now, Files.getLastModifiedTime(root.resolve(COMMIT)).toInstant(),
			"a pin started from the cache is in use, so another window's prune spares it");
	}

	@Test
	public void aJarsDeadlineGrowsWithItsSize()
	{
		RuneLiteCache cache = cache();
		assertEquals(TIMEOUT, cache.deadlineFor(0));
		assertEquals(TIMEOUT.plusSeconds(10), cache.deadlineFor(10 * RuneLiteCache.SLOWEST_BYTES_PER_SECOND));
	}

	@Test
	public void aFreshDownloadFillsTheCacheAndThenStartsOffline() throws Exception
	{
		Bootstrap bootstrap = cache().bootstrap(pin);
		List<Path> classpath = cache().jars(pin, bootstrap, WINDOWS);
		assertEquals(List.of("client.jar", "natives-win.jar"),
			classpath.stream().map(p -> p.getFileName().toString()).toList());
		assertArrayEquals(CLIENT_JAR, Files.readAllBytes(classpath.get(0)));

		// Everything verified on the way in, so the next start needs no network at all.
		Bootstrap offline = offlineCache().bootstrap(pin);
		assertEquals("1.13.4", offline.version);
		assertEquals(classpath, offlineCache().jars(pin, offline, WINDOWS));
	}

	@Test
	public void theRealBootstrapVerifiesThroughTheProductionKeys() throws Exception
	{
		// The committed revision-241 release files, served by the fake, checked by the real runelite.crt.
		served.put("/static/" + Fixtures.REAL_COMMIT + "/bootstrap.json", Fixtures.realBootstrap());
		served.put("/static/" + Fixtures.REAL_COMMIT + "/bootstrap.json.sha256", Fixtures.realBootstrapSignature());
		String base = "http://" + LOOPBACK.getHostAddress() + ":" + web.getAddress().getPort() + "/static/";
		RuneLiteCache real = new RuneLiteCache(root, base, RuneLiteSignature.trustedKeys(), TIMEOUT);
		assertEquals("1.13.4", real.bootstrap(Fixtures.REAL_PIN).version);
	}

	@Test
	public void aTamperedCachedBootstrapIsReplacedFromTheNetwork() throws Exception
	{
		cache().bootstrap(pin);
		Path cached = root.resolve(COMMIT).resolve("bootstrap.json");
		Files.write(cached, "{\"not\":\"what was fetched\"}".getBytes(StandardCharsets.UTF_8));

		assertEquals("1.13.4", cache().bootstrap(pin).version);
		assertArrayEquals(bootstrapBytes, Files.readAllBytes(cached));
	}

	@Test
	public void aBootstrapWithoutThePinnedHashIsRefusedAndNotCached()
	{
		Manifest.Pin wrongPin = new Manifest.Pin(241, "1.13.4", COMMIT, "cd".repeat(32));
		assertThrows(BootstrapException.class, () -> cache().bootstrap(wrongPin));
		assertFalse(Files.exists(root.resolve(COMMIT).resolve("bootstrap.json")));
	}

	@Test
	public void aBootstrapSignedByTheWrongKeyIsRefused()
	{
		served.put("/static/" + COMMIT + "/bootstrap.json.sha256", sign(generate(), bootstrapBytes));
		assertThrows(BootstrapException.class, () -> cache().bootstrap(pin));
	}

	@Test
	public void aTamperedCachedJarIsFetchedAgain() throws Exception
	{
		Bootstrap bootstrap = cache().bootstrap(pin);
		cache().jars(pin, bootstrap, WINDOWS);
		Path jar = root.resolve(COMMIT).resolve("client.jar");
		// The same length, so only the hash can tell.
		Files.write(jar, "the client jar's BYTES".getBytes(StandardCharsets.UTF_8));
		int fetchesBefore = hits.get("/jars/client.jar").get();

		cache().jars(pin, bootstrap, WINDOWS);
		assertArrayEquals(CLIENT_JAR, Files.readAllBytes(jar));
		assertEquals(fetchesBefore + 1, hits.get("/jars/client.jar").get());
	}

	@Test
	public void aWrongDownloadStopsTheLaunchAndLeavesNoFile() throws Exception
	{
		Bootstrap bootstrap = cache().bootstrap(pin);
		served.put("/jars/natives-win.jar", "not the natives at all".getBytes(StandardCharsets.UTF_8));
		BootstrapException refused =
			assertThrows(BootstrapException.class, () -> cache().jars(pin, bootstrap, WINDOWS));
		assertTrue(refused.getMessage().contains("natives-win.jar"));
		assertFalse(Files.exists(root.resolve(COMMIT).resolve("natives-win.jar")));
	}

	@Test
	public void eachSystemGetsOnlyItsJars() throws Exception
	{
		Bootstrap bootstrap = cache().bootstrap(pin);
		assertEquals(List.of("client.jar"),
			cache().jars(pin, bootstrap, LINUX).stream().map(p -> p.getFileName().toString()).toList());
	}

	@Test
	public void pruneKeepsOnlyTheNamedPins() throws Exception
	{
		Path keep = Files.createDirectories(root.resolve(COMMIT));
		Files.write(keep.resolve("client.jar"), CLIENT_JAR);
		Path old = Files.createDirectories(root.resolve("cd".repeat(20)).resolve("nested"));
		Files.write(old.resolve("stale.jar"), new byte[]{1});

		// The old pin was last used eight days ago; one another window fetched an hour ago is spared.
		Instant now = Instant.parse("2026-11-01T12:00:00Z");
		Files.setLastModifiedTime(root.resolve("cd".repeat(20)), FileTime.from(now.minus(Duration.ofDays(8))));
		Path recent = Files.createDirectories(root.resolve("ef".repeat(20)));
		Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofHours(1))));

		cacheAt(now).prune(Set.of(COMMIT));
		assertTrue(Files.exists(keep.resolve("client.jar")));
		assertFalse(Files.exists(root.resolve("cd".repeat(20))));
		assertTrue(Files.exists(recent), "a pin used within the week may be about to start in another window");

		// A root that was never created is nothing to prune, not an error.
		new RuneLiteCache(root.resolve("never-made"), "http://127.0.0.1:1/", List.of(), TIMEOUT)
			.prune(Set.of(COMMIT));
	}
}
