package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The launcher's entry point up to the run itself: the command line, the manifest file, this JVM's pieces. */
public class MainTest
{
	@TempDir
	Path dir;

	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

	private String output()
	{
		return outBytes.toString(StandardCharsets.UTF_8);
	}

	private static java.security.KeyPair key()
	{
		try
		{
			return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (java.security.NoSuchAlgorithmException e)
		{
			throw new AssertionError(e);
		}
	}

	private static String sign(java.security.KeyPair key, byte[] data)
	{
		try
		{
			java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
			signer.initSign(key.getPrivate());
			signer.update(data);
			return java.util.Base64.getEncoder().encodeToString(signer.sign());
		}
		catch (java.security.GeneralSecurityException e)
		{
			throw new AssertionError(e);
		}
	}

	/** A shell whose GitHub is a map: the manifest for {@code version} naming {@code jar}, signed by {@code key}. */
	private Main.Updaters fakeGitHub(java.security.KeyPair key, String version, String minBootstrap, byte[] jar)
	{
		String json = "{ \"format\": 1, \"version\": \"" + version + "\", \"minBootstrapVersion\": \"" + minBootstrap
			+ "\", \"server\": { \"host\": \"odinsgate.net\", \"gamePort\": 43594, \"httpPort\": 43595, \"modulus\": \""
			+ ManifestTest.MODULUS + "\" }, \"pins\": [ { \"revision\": 241, \"runelite\": \"1.13.4\", \"commit\": \""
			+ ManifestTest.COMMIT + "\", \"bootstrapSha256\": \"" + ManifestTest.SHA256 + "\" } ], "
			+ "\"files\": [ { \"path\": \"lib/odinsgate-launcher-" + version + ".jar\", \"sha256\": \""
			+ RuneLiteCache.sha256(jar) + "\", \"size\": " + jar.length + " } ] }";
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		java.util.Map<String, byte[]> served = java.util.Map.of(
			"http://gh/latest/download/manifest.json", bytes,
			"http://gh/latest/download/manifest.json.sig", sign(key, bytes).getBytes(StandardCharsets.UTF_8),
			"http://gh/download/v" + version + "/odinsgate-launcher-" + version + ".jar", jar);
		return (dataDir, log) -> new Updater(dataDir, "0.1.0", null, List.of(key.getPublic()), "http://gh", uri ->
		{
			byte[] body = served.get(uri.toString());
			if (body == null)
			{
				throw new java.io.IOException(uri + " answered 404");
			}
			return body;
		}, (from, to) -> Files.move(from, to), Thread::sleep, java.time.Clock.systemUTC(), log);
	}

	@Test
	public void theShellHandsOffToADownloadedVersionWithTheManifestAndHealthyMarker() throws Exception
	{
		java.security.KeyPair key = key();
		Path home = dir.resolve("home");
		int code = Main.run(new String[]{"--auto", "--home", home.toString()}, out,
			fakeGitHub(key, "0.2.0", "0.1.0", FakeVersion.jar("fake 0.2.0", 42)));
		assertEquals(42, code, output());
		Path versionDir = home.resolve("versions").resolve("0.2.0");
		assertTrue(output().contains("fake 0.2.0: --auto --home " + home + " --manifest "
			+ home.resolve("manifest.json") + " --healthy " + versionDir.resolve(".healthy")), output());
		assertTrue(output().contains("[launcher] " + Main.VERSION + " handing off to version 0.2.0"), output());
		assertTrue(Files.exists(versionDir.resolve(".started")), "the started marker is written before the hand-off");
		assertFalse(Files.exists(versionDir.resolve(".healthy")), "the fake never says it is healthy");
		assertTrue(Files.readString(home.resolve("logs").resolve("launcher.log")).contains("handing off"),
			"the shell's lines are in the launcher log");
	}

	@Test
	public void aVersionThatCannotRunIsBadAndTheInstalledLauncherLaunchesInstead() throws Exception
	{
		java.security.KeyPair key = key();
		Path home = dir.resolve("home");
		List<String> launched = new ArrayList<>();
		// The fake version has no Main: the hand-off fails, the version is listed as bad, and the installed
		// launcher's own launch runs with the accepted manifest (a recording one here, in place of a server check).
		int code = Main.run(new String[]{"--auto", "--home", home.toString()}, out,
			fakeGitHub(key, "0.2.0", "0.1.0", FakeVersion.emptyJar()), (options, manifest, dataDir, log) ->
			{
				launched.add(manifest.version + " in " + dataDir);
				return 77;
			});
		assertEquals(77, code, output());
		assertEquals(List.of("0.2.0 in " + home), launched);
		assertTrue(output().contains("[launcher] version 0.2.0 could not run: the version has no "
			+ VersionLoader.MAIN_CLASS), output());
		assertTrue(output().contains("[updater] version 0.2.0 is bad (it could not run:"), output());
		assertTrue(output().contains("running the installed launcher " + Main.VERSION + " instead"), output());
		assertEquals("0.2.0", Files.readString(home.resolve("bad-versions.txt")).trim());
	}

	@Test
	public void aReleaseThatNeedsANewerInstallerStopsWithTheLink() throws Exception
	{
		Path home = dir.resolve("home");
		int code = Main.run(new String[]{"--auto", "--home", home.toString()}, out,
			fakeGitHub(key(), "0.2.0", "0.5.0", FakeVersion.jar("never", 1)));
		assertEquals(Main.EXIT_INSTALLER, code, output());
		assertTrue(output().contains("this launcher is version 0.1.0 and the release needs 0.5.0 or newer: "
			+ Main.INSTALLER_LINE + " " + Updater.DOWNLOAD_PAGE), output());
	}

	@Test
	public void nothingToRunIsSaidAndIsAManifestError() throws Exception
	{
		Path home = dir.resolve("home");
		Main.Updaters nothing = (dataDir, log) -> new Updater(dataDir, "0.1.0", null, List.of(key().getPublic()),
			"http://gh", uri ->
		{
			throw new java.io.IOException("no internet");
		}, (from, to) -> Files.move(from, to), Thread::sleep, java.time.Clock.systemUTC(), log);
		assertEquals(Main.EXIT_MANIFEST, Main.run(new String[]{"--auto", "--home", home.toString()}, out, nothing));
		assertTrue(output().contains("needs the internet once"), output());
	}

	@Test
	public void theHealthyMarkerIsWrittenWhereAsked() throws Exception
	{
		Path marker = dir.resolve("marks").resolve(".healthy");
		Main.healthyMarker(marker, out).run();
		assertTrue(Files.exists(marker));
		assertTrue(Files.readString(marker).startsWith("20"), "a timestamp");
		Main.healthyMarker(null, out).run();
		Files.writeString(dir.resolve("file"), "x");
		Main.healthyMarker(dir.resolve("file").resolve("under-a-file"), out).run();
		assertTrue(output().contains("the healthy marker") && output().contains("could not be written"), output());
	}

	@Test
	public void theSelfTestRunsWithoutAManifestOrKeys()
	{
		int code = Main.run(new String[] {"--self-test", "--home", dir.resolve("data").toString()}, out, null);
		assertEquals(0, code, output());
		assertTrue(output().contains("[self-test] passed"), output());
		assertTrue(Files.isRegularFile(dir.resolve("data").resolve("logs").resolve("launcher.log")), "logged too");
	}

	@Test
	public void aBuildWithoutKeysNeedsAManifest()
	{
		// The null updaters is what Main.run(args, out) passes for a build whose TrustedKeys is empty, as every build
		// before 1.0.0 was; a real build now goes to GitHub, which no test does.
		assertEquals(Main.EXIT_USAGE, Main.run(new String[]{"--auto"}, out, null));
		assertTrue(output().contains("--manifest"), output());
		assertTrue(output().contains(LaunchOptions.USAGE), output());
	}

	@Test
	public void aBadArgumentIsAUsageError()
	{
		assertEquals(Main.EXIT_USAGE, Main.run(new String[]{"--what"}, out));
		assertTrue(output().contains("--what"), output());
	}

	@Test
	public void aManifestThatIsNotThereIsNamed()
	{
		Path missing = dir.resolve("none.json");
		assertEquals(Main.EXIT_MANIFEST, Main.run(new String[]{"--manifest", missing.toString()}, out));
		assertTrue(output().contains(missing.toString()), output());
	}

	@Test
	public void aManifestThatDoesNotParseIsNamedWithItsFault() throws Exception
	{
		Path bad = dir.resolve("bad.json");
		Files.writeString(bad, "{ \"version\": \"0.1.0\" }");
		assertEquals(Main.EXIT_MANIFEST, Main.run(new String[]{"--manifest", bad.toString()}, out));
		assertTrue(output().contains("format"), output());
	}

	@Test
	public void thisJvmIsTheRuntimeAndTheClassPath()
	{
		Launch.Installed installed = Main.thisJvm();
		assertEquals(Path.of(System.getProperty("java.home")), installed.runtimeDir());
		assertFalse(installed.loaderClasspath().isEmpty());
		assertTrue(installed.loaderClasspath().stream().allMatch(p -> !p.toString().isBlank()));
	}

	@Test
	public void theLauncherLogsUnderItsDataFolderAndStillToTheConsole() throws Exception
	{
		Path data = dir.resolve("data");
		try (PrintStream log = Main.openLog(data, out))
		{
			log.println("[launcher] STARTING: here");
		}
		Path file = data.resolve("logs").resolve("launcher.log");
		assertEquals("[launcher] STARTING: here" + System.lineSeparator(), Files.readString(file));
		assertTrue(output().contains("STARTING: here"), output());
		out.println("still open");
		assertTrue(output().contains("still open"), "closing the log leaves the console alone");

		try (PrintStream log = Main.openLog(data, out))
		{
			log.println("second run");
		}
		assertTrue(Files.readString(file).contains("here"), "the log is appended to across runs");
		assertTrue(Files.readString(file).contains("second run"));
	}

	@Test
	public void aDataFolderThatCannotHoldALogLeavesTheConsoleAsTheLog() throws Exception
	{
		Path blocked = dir.resolve("blocked");
		Files.writeString(blocked, "a file where the folder should be");
		try (PrintStream log = Main.openLog(blocked, out))
		{
			log.println("[launcher] CHECKING: anyway");
		}
		assertTrue(output().contains("could not be opened"), output());
		assertTrue(output().contains("CHECKING: anyway"), output());
	}

	@Test
	public void theVersionIsNeverEmpty()
	{
		assertFalse(Main.VERSION.isBlank());
	}
}
