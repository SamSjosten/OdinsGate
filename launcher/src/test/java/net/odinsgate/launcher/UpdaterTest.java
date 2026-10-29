package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The self-update bootstrap against a fake GitHub on a map and throwaway Ed25519 keys: one test per way an update
 * can go wrong, as the plan lists them, and the ways it goes right.
 */
public class UpdaterTest
{
	private static final KeyPair PRIMARY = generate();
	private static final KeyPair BACKUP = generate();
	private static final KeyPair NEW_PRIMARY = generate();
	private static final KeyPair STRANGER = generate();
	private static final String RELEASES = "http://releases.test";
	private static final Instant AT = Instant.parse("2026-10-29T21:00:00Z");
	private static final byte[] JAR_A = FakeJar.of("version a");
	private static final byte[] JAR_B = FakeJar.of("version b");

	/** Tiny stand-ins for release files: the updater checks sizes and hashes, never what is inside. */
	private static final class FakeJar
	{
		static byte[] of(String text)
		{
			return text.getBytes(StandardCharsets.UTF_8);
		}
	}

	@TempDir
	Path dir;

	private Path data;
	private final Map<String, byte[]> served = new LinkedHashMap<>();
	private final List<String> fetched = new ArrayList<>();
	private final List<Duration> slept = new ArrayList<>();
	private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
	private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
	private Updater.Mover mover = (from, to) -> Files.move(from, to);

	private static KeyPair generate()
	{
		try
		{
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private static String sign(KeyPair key, byte[] data)
	{
		try
		{
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(key.getPrivate());
			signer.update(data);
			return Base64.getEncoder().encodeToString(signer.sign());
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private static String spki(KeyPair key)
	{
		return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
	}

	@BeforeEach
	public void start()
	{
		data = dir.resolve("data");
	}

	/** The manifest JSON for {@code version} naming {@code files} (name to bytes, under lib/). */
	private static String manifest(String version, String minBootstrap, Map<String, byte[]> files, String nextPrimary)
	{
		StringBuilder entries = new StringBuilder();
		for (Map.Entry<String, byte[]> file : files.entrySet())
		{
			if (entries.length() > 0)
			{
				entries.append(',');
			}
			entries.append("{ \"path\": \"lib/").append(file.getKey()).append("\", \"sha256\": \"")
				.append(RuneLiteCache.sha256(file.getValue())).append("\", \"size\": ").append(file.getValue().length)
				.append(" }");
		}
		return "{ \"format\": 1, \"version\": \"" + version + "\", \"minBootstrapVersion\": \"" + minBootstrap + "\",\n"
			+ "  \"server\": { \"host\": \"odinsgate.net\", \"gamePort\": 43594, \"httpPort\": 43595, \"modulus\": \""
			+ ManifestTest.MODULUS + "\" },\n"
			+ "  \"pins\": [ { \"revision\": 241, \"runelite\": \"1.13.4\", \"commit\": \"" + ManifestTest.COMMIT
			+ "\", \"bootstrapSha256\": \"" + ManifestTest.SHA256 + "\" } ],\n"
			+ "  \"files\": [ " + entries + " ]"
			+ (nextPrimary == null ? "" : ",\n  \"keys\": { \"primary\": \"" + nextPrimary + "\" }") + " }\n";
	}

	/** Publishes a release: the manifest and signature at latest/download, the files at download/v<version>/. */
	private void release(KeyPair signer, String version, Map<String, byte[]> files)
	{
		release(signer, version, "0.1.0", files, null);
	}

	private void release(KeyPair signer, String version, String minBootstrap, Map<String, byte[]> files,
		String nextPrimary)
	{
		byte[] bytes = manifest(version, minBootstrap, files, nextPrimary).getBytes(StandardCharsets.UTF_8);
		publish(bytes, sign(signer, bytes), version, files);
	}

	private void publish(byte[] manifestBytes, String signature, String version, Map<String, byte[]> files)
	{
		served.put(RELEASES + "/latest/download/manifest.json", manifestBytes);
		served.put(RELEASES + "/latest/download/manifest.json.sig", signature.getBytes(StandardCharsets.UTF_8));
		for (Map.Entry<String, byte[]> file : files.entrySet())
		{
			served.put(RELEASES + "/download/v" + version + "/" + file.getKey(), file.getValue());
		}
	}

	private Updater updater(String shellVersion)
	{
		return updater(shellVersion, null, List.of(PRIMARY.getPublic(), BACKUP.getPublic()));
	}

	private Updater updater(String shellVersion, Path appDir, List<java.security.PublicKey> keys)
	{
		Updater.Fetcher fetcher = uri ->
		{
			fetched.add(uri.toString());
			byte[] body = served.get(uri.toString());
			if (body == null)
			{
				throw new IOException(uri + " answered 404");
			}
			return body;
		};
		return new Updater(data, shellVersion, appDir, keys, RELEASES, fetcher, (from, to) -> mover.move(from, to),
			slept::add, Clock.fixed(AT, ZoneOffset.UTC), log);
	}

	private String logText()
	{
		return logBytes.toString(StandardCharsets.UTF_8);
	}

	private Updater.Run run(Updater.Outcome outcome)
	{
		assertInstanceOf(Updater.Run.class, outcome, logText());
		return (Updater.Run) outcome;
	}

	private long fetchesOf(String suffix)
	{
		return fetched.stream().filter(uri -> uri.endsWith(suffix)).count();
	}

	/** The started marker a launcher that crashed left: a process that is long gone, and no healthy marker. */
	private void crashed(String version) throws IOException
	{
		Path dir = data.resolve("versions").resolve(version);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(".started"), Long.MAX_VALUE + " 1 " + AT + "\n");
		Files.deleteIfExists(dir.resolve(".healthy"));
	}

	@Test
	public void aNewerReleaseIsDownloadedCheckedKeptAndMadeCurrent() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("odinsgate-launcher-0.2.0.jar", JAR_A));
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertFalse(run.installed());
		assertEquals(data.resolve("versions").resolve("0.2.0"), run.versionDir());
		assertEquals("0.2.0", run.manifest().version);
		assertEquals(data.resolve("manifest.json"), run.manifestFile());
		assertTrue(Files.exists(run.versionDir().resolve("lib").resolve("odinsgate-launcher-0.2.0.jar")));
		assertEquals(List.of(run.versionDir().resolve("lib").resolve("odinsgate-launcher-0.2.0.jar")), run.jars(),
			"the class path is the jars the manifest names");
		assertTrue(Files.exists(run.versionDir().resolve("manifest.json")), "the manifest is kept beside the files");
		assertTrue(Files.exists(run.versionDir().resolve("manifest.json.sig")));
		assertEquals("0.2.0", Files.readString(data.resolve("current")).trim(), "the pointer switched");
		assertTrue(Files.exists(data.resolve("manifest.json")), "the accepted manifest is kept");
		assertFalse(Files.exists(data.resolve("versions").resolve("0.2.0.downloading")));
		assertEquals(1, fetchesOf("/download/v0.2.0/odinsgate-launcher-0.2.0.jar"));
		assertTrue(logText().contains("[updater] downloaded version 0.2.0 (1 files)"), logText());

		// The next start, with GitHub unchanged: nothing is downloaded again.
		fetched.clear();
		Updater.Run again = run(updater("0.1.0").update());
		assertEquals("0.2.0", again.version());
		assertEquals(0, fetchesOf(".jar"), "a version that is here and verifies isn't fetched again");
	}

	@Test
	public void aReleaseOfTheShellsOwnVersionRunsTheInstalledLauncher() throws Exception
	{
		release(PRIMARY, "0.1.0", Map.of("odinsgate-launcher-0.1.0.jar", JAR_A));
		Updater.Run run = run(updater("0.1.0").update());
		assertTrue(run.installed());
		assertEquals("0.1.0", run.version());
		assertNull(run.versionDir());
		assertEquals(0, fetchesOf(".jar"));
		assertFalse(Files.exists(data.resolve("versions")));
		assertTrue(Files.exists(data.resolve("manifest.json")), "the manifest is still kept for an offline start");
	}

	@Test
	public void aTamperedManifestIsIgnoredAndTheAcceptedOneStands() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		// A stranger's manifest, and the accepted one's bytes with a bit flipped under a real signature.
		release(STRANGER, "0.3.0", Map.of("b.jar", JAR_B));
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertTrue(logText().contains("signature doesn't verify against any trusted key: ignored"), logText());
		assertEquals(0, fetchesOf("b.jar"));
		byte[] bytes = manifest("0.3.0", "0.1.0", Map.of("b.jar", JAR_B), null).getBytes(StandardCharsets.UTF_8);
		String signature = sign(PRIMARY, bytes);
		bytes[bytes.length - 3] ^= 1;
		publish(bytes, signature, "0.3.0", Map.of("b.jar", JAR_B));
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
		assertEquals(0, fetchesOf("b.jar"));
	}

	@Test
	public void anOlderManifestThanTheAcceptedOneIsRefused() throws Exception
	{
		release(PRIMARY, "0.3.0", Map.of("c.jar", JAR_A));
		run(updater("0.1.0").update());
		release(PRIMARY, "0.2.5", Map.of("old.jar", JAR_B));
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.3.0", run.version());
		assertTrue(logText().contains("version 0.2.5, older than the accepted 0.3.0: ignored"), logText());
		assertEquals(0, fetchesOf("old.jar"));
		assertEquals("0.3.0", Manifest.parse(Files.readString(data.resolve("manifest.json"))).version);
	}

	private void installTwo() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		release(PRIMARY, "0.3.0", Map.of("b.jar", JAR_B));
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
	}

	@Test
	public void aCrashBeforeHealthyMarksTheVersionBadAndThePreviousOneRuns() throws Exception
	{
		installTwo();
		Updater updater = updater("0.1.0");
		// A launcher started 0.3.0, and no healthy marker ever came: the next start finds the crash.
		crashed("0.3.0");
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertEquals(List.of("0.3.0"), updater.badVersions());
		assertTrue(logText().contains("version 0.3.0 is bad (its last start never became healthy): never again"),
			logText());
		assertTrue(logText().contains("names 0.3.0, which doesn't verify: falling back to 0.2.0")
			|| logText().contains("falling back to 0.2.0"), logText());
	}

	@Test
	public void aHealthyStartIsNoCrash() throws Exception
	{
		installTwo();
		Updater updater = updater("0.1.0");
		updater.markStarted("0.3.0");
		Files.writeString(updater.healthyMarker("0.3.0"), AT + "\n");
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		assertEquals(List.of(), updater.badVersions());
		// And the next start clears the old healthy marker before the new started one.
		updater.markStarted("0.3.0");
		assertFalse(updater.isHealthy("0.3.0"));
	}

	@Test
	public void aSecondLauncherStartingWhileTheFirstComesUpIsNoCrash() throws Exception
	{
		installTwo();
		Updater first = updater("0.1.0");
		// The first launcher (this process) has written the started marker and isn't healthy yet when a second
		// launcher starts and looks: the start is under way, not crashed.
		first.markStarted("0.3.0");
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		assertEquals(List.of(), first.badVersions());
		assertFalse(logText().contains("is bad"), logText());
	}

	@Test
	public void theCurrentPointerIsWrittenAgainOnceAnEarlierWriteFailed() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		// A folder (not empty, so no rename replaces it) where the pointer file goes: the write fails.
		Files.createDirectories(data.resolve("current"));
		Files.writeString(data.resolve("current").resolve("in-the-way"), "x");
		Updater.Run run = run(updater("0.1.0").update());
		assertTrue(logText().contains("the current pointer could not be written"), logText());
		assertEquals("0.2.0", run.version(), "the downloaded version is in place and runs by the fallback");
		assertTrue(logText().contains("is missing or unreadable: falling back to 0.2.0"), logText());
		// The way is clear at the next start: the pointer is written, nothing is downloaded again.
		RuneLiteCache.deleteTree(data.resolve("current"));
		fetched.clear();
		run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertEquals("0.2.0", Files.readString(data.resolve("current")).trim());
		assertEquals(0, fetchesOf("a.jar"));
		assertTrue(logText().contains("version 0.2.0 is in place and current"), logText());
	}

	@Test
	public void aJarDroppedIntoAVersionsFolderIsNeverOnItsClassPath() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		Updater.Run run = run(updater("0.1.0").update());
		Files.write(run.versionDir().resolve("lib").resolve("0-extra.jar"), JAR_B);
		run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version(), "the version still verifies: every file the manifest names is right");
		assertEquals(List.of(run.versionDir().resolve("lib").resolve("a.jar")), run.jars());
	}

	@Test
	public void aHangIsTheWatchdogsCall() throws Exception
	{
		installTwo();
		Updater updater = updater("0.1.0");
		updater.markStarted("0.3.0");
		Thread watchdog = updater.watchdog("0.3.0");
		watchdog.start();
		watchdog.join(5000);
		assertFalse(watchdog.isAlive());
		assertEquals(List.of(Updater.HEALTHY_WITHIN), slept, "the watchdog waits the healthy minute");
		assertEquals(List.of("0.3.0"), updater.badVersions());
		assertTrue(logText().contains("hadn't become healthy within 60 seconds"), logText());
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
	}

	@Test
	public void aVersionHealthyInTimeSatisfiesTheWatchdog() throws Exception
	{
		installTwo();
		Updater updater = updater("0.1.0");
		updater.markStarted("0.3.0");
		Files.writeString(updater.healthyMarker("0.3.0"), AT + "\n");
		updater.markBadUnlessHealthy("0.3.0");
		assertEquals(List.of(), updater.badVersions());
	}

	@Test
	public void aBadVersionIsNeverReinstalledUntilANewerOneAppears() throws Exception
	{
		installTwo();
		Updater updater = updater("0.1.0");
		updater.markBad("0.3.0", "a test says so");
		fetched.clear();
		// GitHub still offers 0.3.0: it isn't fetched, and 0.2.0 runs.
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
		assertEquals(0, fetchesOf("b.jar"));
		assertEquals(0, fetchesOf("a.jar"));
		// Even with its folder gone, 0.3.0 isn't downloaded again.
		RuneLiteCache.deleteTree(updater.versionDir("0.3.0"));
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
		assertEquals(0, fetchesOf("b.jar"));
		// A newer version is.
		release(PRIMARY, "0.4.0", Map.of("d.jar", JAR_A));
		assertEquals("0.4.0", run(updater("0.1.0").update()).version());
		assertEquals(1, fetchesOf("d.jar"));
	}

	@Test
	public void aCorruptPointerIsWrittenAgainForTheVersionTheManifestNames() throws Exception
	{
		installTwo();
		Files.writeString(data.resolve("current"), "not a version\n");
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		assertEquals("0.3.0", Files.readString(data.resolve("current")).trim(), "pointed at the accepted version");
		Files.delete(data.resolve("current"));
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		assertEquals("0.3.0", Files.readString(data.resolve("current")).trim());
	}

	@Test
	public void aCorruptPointerFallsBackToTheNewestVersionThatVerifies() throws Exception
	{
		// The accepted manifest names 0.4.0, which can't be fetched (no internet now), so nothing re-points; the
		// pointer is garbage, and the newest downloaded version that verifies is the one to run.
		installTwo();
		release(PRIMARY, "0.4.0", Map.of("c.jar", JAR_A));
		run(updater("0.4.0").update());
		served.clear();
		Files.writeString(data.resolve("current"), "not a version\n");
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		assertTrue(logText().contains("the current pointer is missing or unreadable: falling back to 0.3.0"),
			logText());
	}

	@Test
	public void aTamperedFileFallsBackToTheNewestVersionThatVerifies() throws Exception
	{
		installTwo();
		Path jar = data.resolve("versions").resolve("0.3.0").resolve("lib").resolve("b.jar");
		Files.write(jar, "not version b".getBytes(StandardCharsets.UTF_8));
		served.clear(); // No GitHub either: the hashing before launch alone decides.
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
		assertTrue(logText().contains("version 0.3.0 doesn't verify: lib/b.jar is missing or isn't the file"),
			logText());
		assertTrue(logText().contains("names 0.3.0, which doesn't verify: falling back to 0.2.0"), logText());
	}

	@Test
	public void aMissingFileFallsBackToTheInstalledLauncher() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		Files.delete(data.resolve("versions").resolve("0.2.0").resolve("lib").resolve("a.jar"));
		served.clear();
		Updater.Run run = run(updater("0.1.0").update());
		assertTrue(run.installed(), logText());
		assertEquals("0.2.0", run.manifest().version, "with the accepted manifest's pins and key");
	}

	@Test
	public void noReleaseAt404StartsTheLastGoodVersion() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		served.clear();
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertTrue(logText().contains("[updater] no release manifest from GitHub: " + RELEASES
			+ "/latest/download/manifest.json answered 404"), logText());
	}

	@Test
	public void aKeepStoppedBetweenItsMovesIsFinishedAtTheNextStart() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		// The 0.3.0 manifest was being kept: its signature had moved into place, its manifest hadn't, and then the
		// launcher stopped. The pair on disk is torn (0.2.0's manifest, 0.3.0's signature).
		byte[] next = manifest("0.3.0", "0.1.0", Map.of("b.jar", JAR_B), null).getBytes(StandardCharsets.UTF_8);
		Files.write(data.resolve("manifest.json.new"), next);
		Files.writeString(data.resolve("manifest.json.sig"), sign(PRIMARY, next));
		served.clear();
		Updater.Run run = run(updater("0.1.0").update());
		assertTrue(logText().contains("finished keeping the accepted manifest"), logText());
		assertArrayEquals(next, Files.readAllBytes(data.resolve("manifest.json")), "the pair is whole again");
		assertFalse(Files.exists(data.resolve("manifest.json.new")));
		assertEquals("0.3.0", run.manifest().version, "the finished manifest is the accepted one");
		assertEquals("0.2.0", run.version(), "its version isn't here (no internet), so the last good one runs");
	}

	@Test
	public void aKeepStoppedBeforeItsMovesLeavesTheOldPairStanding() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		byte[] next = manifest("0.3.0", "0.1.0", Map.of("b.jar", JAR_B), null).getBytes(StandardCharsets.UTF_8);
		Files.write(data.resolve("manifest.json.new"), next);
		Files.writeString(data.resolve("manifest.json.new.sig"), sign(PRIMARY, next));
		served.clear();
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.manifest().version, "nothing had moved, so the old pair stands");
		assertEquals("0.2.0", run.version());
		assertFalse(Files.exists(data.resolve("manifest.json.new")));
		assertFalse(Files.exists(data.resolve("manifest.json.new.sig")));
	}

	@Test
	public void aStopBeforeTheSignatureWasStagedLeavesTheOldPairStanding() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		// The launcher stopped with the 0.3.0 manifest written under its .new name and its signature not yet
		// staged: the signature in place is still 0.2.0's, which the staged manifest doesn't verify against.
		byte[] next = manifest("0.3.0", "0.1.0", Map.of("b.jar", JAR_B), null).getBytes(StandardCharsets.UTF_8);
		Files.write(data.resolve("manifest.json.new"), next);
		served.clear();
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.manifest().version, "the old pair stands");
		assertFalse(Files.exists(data.resolve("manifest.json.new")));
		assertFalse(logText().contains("finished keeping"), logText());
		// The same for a manifest cut short mid-write.
		Files.write(data.resolve("manifest.json.new"), java.util.Arrays.copyOf(next, next.length / 2));
		run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.manifest().version);
		assertFalse(Files.exists(data.resolve("manifest.json.new")));
	}

	@Test
	public void versionsThatCompareEqualButAreNamedApartStayApart() throws Exception
	{
		release(PRIMARY, "0.2", Map.of("a.jar", JAR_A));
		assertEquals("0.2", run(updater("0.1.0").update()).version());
		// 0.2.0 compares equal to 0.2, so it isn't older than the accepted manifest and is taken; it is another
		// folder, with other files, and the pointer names it.
		release(PRIMARY, "0.2.0", Map.of("b.jar", JAR_B));
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertEquals("0.2.0", Files.readString(data.resolve("current")).trim());
		assertEquals(List.of(data.resolve("versions").resolve("0.2.0").resolve("lib").resolve("b.jar")), run.jars());
		served.clear();
		run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertEquals(List.of(data.resolve("versions").resolve("0.2.0").resolve("lib").resolve("b.jar")), run.jars());
	}

	@Test
	public void aTornAcceptedPairIsStoodInForByTheNewestVersionsManifest() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		// The accepted pair no longer verifies (a disk that lost the signature's bytes) and there is no internet.
		Files.writeString(data.resolve("manifest.json.sig"), "not a signature");
		served.clear();
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version(), "the downloaded version still runs");
		assertEquals(data.resolve("versions").resolve("0.2.0").resolve("manifest.json"), run.manifestFile());
		assertTrue(logText().contains("the manifest kept with version 0.2.0 stands in for the accepted one"),
			logText());
		// Online again, the accepted pair is written anew even though the release is the same.
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run = run(updater("0.1.0").update());
		assertEquals(data.resolve("manifest.json"), run.manifestFile());
		assertTrue(logText().contains("accepted the release manifest for version 0.2.0"), logText());
	}

	@Test
	public void aFirstStartWithNoInternetAndNothingKeptNeedsTheInternetOnce() throws Exception
	{
		Updater.Outcome outcome = updater("0.1.0").update();
		assertInstanceOf(Updater.NoManifest.class, outcome);
		assertTrue(((Updater.NoManifest) outcome).why().contains("needs the internet once"));
	}

	@Test
	public void aBundledManifestServesAFirstStartWithNoInternet() throws Exception
	{
		Path app = dir.resolve("app");
		Files.createDirectories(app);
		byte[] bytes = manifest("0.1.0", "0.1.0", Map.of("odinsgate-launcher-0.1.0.jar", JAR_A), null)
			.getBytes(StandardCharsets.UTF_8);
		Files.write(app.resolve("manifest.json"), bytes);
		Files.writeString(app.resolve("manifest.json.sig"), sign(PRIMARY, bytes));
		Updater.Run run = run(updater("0.1.0", app, List.of(PRIMARY.getPublic(), BACKUP.getPublic())).update());
		assertTrue(run.installed());
		assertEquals(app.resolve("manifest.json"), run.manifestFile());
		// A bundled manifest signed by nobody we trust is no use.
		Files.writeString(app.resolve("manifest.json.sig"), sign(STRANGER, bytes));
		assertInstanceOf(Updater.NoManifest.class,
			updater("0.1.0", app, List.of(PRIMARY.getPublic(), BACKUP.getPublic())).update());
	}

	@Test
	public void noKeysBuiltInMeansUpdatesAreOff() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		Updater.Outcome outcome = updater("0.1.0", null, List.of()).update();
		assertInstanceOf(Updater.NoManifest.class, outcome);
		assertTrue(((Updater.NoManifest) outcome).why().contains("no release keys are built into this launcher"));
		assertEquals(0, fetched.size(), "nothing is even fetched");
	}

	@Test
	public void aMissingJarLeavesNothingBehind() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A, "gone.jar", JAR_B));
		served.remove(RELEASES + "/download/v0.2.0/gone.jar");
		Updater.Run run = run(updater("0.1.0").update());
		assertTrue(run.installed());
		assertTrue(logText().contains("version 0.2.0 could not be downloaded: " + RELEASES
			+ "/download/v0.2.0/gone.jar answered 404"), logText());
		assertFalse(Files.exists(data.resolve("versions").resolve("0.2.0")));
		assertFalse(Files.exists(data.resolve("versions").resolve("0.2.0.downloading")));
		assertFalse(Files.exists(data.resolve("current")));
	}

	@Test
	public void aJarThatIsntTheFileTheManifestNamesLeavesNothingBehind() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		served.put(RELEASES + "/download/v0.2.0/a.jar", JAR_B);
		Updater.Run run = run(updater("0.1.0").update());
		assertTrue(run.installed());
		assertTrue(logText().contains("a.jar from " + RELEASES + "/download/v0.2.0/a.jar isn't the file the manifest "
			+ "names"), logText());
		assertFalse(Files.exists(data.resolve("versions").resolve("0.2.0")));
	}

	@Test
	public void aLockedFileIsTriedAgainAMomentLater() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		int[] failures = {2};
		mover = (from, to) ->
		{
			if (failures[0]-- > 0)
			{
				throw new IOException("the file is being used by another process");
			}
			Files.move(from, to);
		};
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertEquals(List.of(Updater.MOVE_RETRY, Updater.MOVE_RETRY), slept);
		assertTrue(logText().contains("move 1/5 of 0.2.0.downloading failed: the file is being used"), logText());
		assertTrue(logText().contains("move 2/5"), logText());
		assertFalse(logText().contains("move 3/5"), logText());
	}

	@Test
	public void aFileLockedForGoodLeavesNothingBehind() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		mover = (from, to) ->
		{
			throw new IOException("held");
		};
		Updater.Run run = run(updater("0.1.0").update());
		assertTrue(run.installed());
		assertEquals(Updater.MOVE_TRIES - 1, slept.size());
		assertTrue(logText().contains("move 5/5 of 0.2.0.downloading failed: held"), logText());
		assertTrue(logText().contains("version 0.2.0 could not be downloaded: held"), logText());
		assertFalse(Files.exists(data.resolve("versions").resolve("0.2.0.downloading")));
		assertFalse(Files.exists(data.resolve("versions").resolve("0.2.0")));
	}

	@Test
	public void theBackupKeyCanNameANewPrimaryAndTheOldOneThenSignsNothing() throws Exception
	{
		release(BACKUP, "0.2.0", "0.1.0", Map.of("a.jar", JAR_A), spki(NEW_PRIMARY));
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertTrue(logText().contains("the backup key named a new primary key: rotated"), logText());
		assertTrue(Files.readString(data.resolve("keys.json")).contains(spki(NEW_PRIMARY)));
		// The new primary signs the next release.
		release(NEW_PRIMARY, "0.3.0", Map.of("b.jar", JAR_B));
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		// The old primary, lost or leaked, signs nothing a friend takes.
		release(PRIMARY, "0.4.0", Map.of("c.jar", JAR_A));
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		assertTrue(logText().contains("signature doesn't verify against any trusted key: ignored"), logText());
		assertEquals(0, fetchesOf("c.jar"));
		// The backup still signs.
		release(BACKUP, "0.5.0", Map.of("e.jar", JAR_B));
		assertEquals("0.5.0", run(updater("0.1.0").update()).version());
	}

	@Test
	public void anOlderBackupSignedManifestReplayedRotatesNothing() throws Exception
	{
		release(PRIMARY, "0.3.0", Map.of("b.jar", JAR_B));
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		// The backup once signed 0.2.0 naming another primary; served again (a cache, a mirror), it is older than
		// the accepted manifest, so it names nothing, not even a key.
		release(BACKUP, "0.2.0", "0.1.0", Map.of("a.jar", JAR_A), spki(NEW_PRIMARY));
		assertEquals("0.3.0", run(updater("0.1.0").update()).version());
		assertTrue(logText().contains("older than the accepted 0.3.0: ignored"), logText());
		assertFalse(logText().contains("rotated"), logText());
		assertFalse(Files.exists(data.resolve("keys.json")));
	}

	@Test
	public void onlyTheBackupKeyRotatesThePrimary() throws Exception
	{
		release(PRIMARY, "0.2.0", "0.1.0", Map.of("a.jar", JAR_A), spki(NEW_PRIMARY));
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
		assertTrue(logText().contains("isn't signed by the backup key: not rotated"), logText());
		assertFalse(Files.exists(data.resolve("keys.json")));
		release(PRIMARY, "0.3.0", Map.of("b.jar", JAR_B));
		assertEquals("0.3.0", run(updater("0.1.0").update()).version(), "the primary still stands");
	}

	@Test
	public void aKeysFileThatDoesntReadLeavesTheBuiltInKeysStanding() throws Exception
	{
		Files.createDirectories(data);
		Files.writeString(data.resolve("keys.json"), "{ not json");
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
		assertTrue(logText().contains("keys.json doesn't read, so the built-in keys stand"), logText());
	}

	@Test
	public void anUnsignedPleaseReinstallIsIgnored() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		release(STRANGER, "0.3.0", "9.0.0", Map.of("b.jar", JAR_B), null);
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertFalse(logText().contains("needs"), logText());
	}

	@Test
	public void aSignedManifestThatNeedsANewerShellSaysSo() throws Exception
	{
		release(PRIMARY, "0.3.0", "0.2.0", Map.of("b.jar", JAR_B), null);
		Updater.Outcome outcome = updater("0.1.0").update();
		assertInstanceOf(Updater.NeedsInstaller.class, outcome, logText());
		assertEquals("this launcher is version 0.1.0 and the release needs 0.2.0 or newer",
			((Updater.NeedsInstaller) outcome).why());
		assertEquals(0, fetchesOf("b.jar"), "nothing is downloaded for a shell that can't run it");
		// A shell that is new enough runs it.
		assertEquals("0.3.0", run(updater("0.2.0").update()).version());
		// And the kept manifest keeps saying so to an old shell with no internet.
		served.clear();
		assertInstanceOf(Updater.NeedsInstaller.class, updater("0.1.0").update());
	}

	@Test
	public void aManifestOfANewerFormatSaysSoRatherThanFailingToRead() throws Exception
	{
		byte[] bytes = manifest("0.3.0", "0.1.0", Map.of("b.jar", JAR_B), null)
			.replace("\"format\": 1", "\"format\": 2").getBytes(StandardCharsets.UTF_8);
		publish(bytes, sign(PRIMARY, bytes), "0.3.0", Map.of("b.jar", JAR_B));
		Updater.Outcome outcome = updater("0.1.0").update();
		assertInstanceOf(Updater.NeedsInstaller.class, outcome, logText());
		assertEquals("the release manifest is format 2 and this launcher reads format 1",
			((Updater.NeedsInstaller) outcome).why());
		// Unsigned, the same manifest says nothing.
		publish(bytes, sign(STRANGER, bytes), "0.3.0", Map.of("b.jar", JAR_B));
		assertInstanceOf(Updater.NoManifest.class, updater("0.1.0").update());
	}

	@Test
	public void aManifestThatDoesntReadIsIgnored() throws Exception
	{
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		run(updater("0.1.0").update());
		byte[] bytes = "{ \"format\": 1, \"version\": \"0.3.0\" }".getBytes(StandardCharsets.UTF_8);
		publish(bytes, sign(PRIMARY, bytes), "0.3.0", Map.of());
		assertEquals("0.2.0", run(updater("0.1.0").update()).version());
		assertTrue(logText().contains("the release manifest doesn't read:"), logText());
	}

	@Test
	public void aNewerInstallerOverAnOldDataFolderRunsTheInstalledCode() throws Exception
	{
		installTwo();
		Updater.Run run = run(updater("0.4.0").update());
		assertTrue(run.installed());
		assertEquals("0.4.0", run.version());
		assertTrue(logText().contains("running the installed launcher 0.4.0 (newer than 0.3.0)"), logText());
	}

	@Test
	public void aDevRunNeverHandsOffAndNeverNeedsAnInstaller() throws Exception
	{
		release(PRIMARY, "0.3.0", "0.2.0", Map.of("b.jar", JAR_B), null);
		Updater.Run run = run(updater("dev").update());
		assertTrue(run.installed());
		assertEquals("dev", run.version());
	}

	@Test
	public void theDownloadFolderOfAnEarlierTryIsClearedFirst() throws Exception
	{
		Path stale = data.resolve("versions").resolve("0.2.0.downloading").resolve("lib");
		Files.createDirectories(stale);
		Files.writeString(stale.resolve("stale.jar"), "left over");
		release(PRIMARY, "0.2.0", Map.of("a.jar", JAR_A));
		Updater.Run run = run(updater("0.1.0").update());
		assertEquals("0.2.0", run.version());
		assertFalse(Files.exists(run.versionDir().resolve("lib").resolve("stale.jar")));
	}

	@Test
	public void theReleaseUrlsAreGitHubsLatestAndVersionedDownloads()
	{
		assertEquals("https://github.com/SamSjosten/OSRSPS/releases", Updater.RELEASES);
		assertEquals(URI.create("https://github.com/SamSjosten/OSRSPS/releases/latest"), Updater.DOWNLOAD_PAGE);
	}
}
