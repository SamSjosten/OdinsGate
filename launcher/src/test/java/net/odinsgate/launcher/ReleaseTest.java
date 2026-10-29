package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release tool on a made-up lib folder, pin table and modulus, with throwaway keys: what the manifest names, what
 * it refuses, the signature round trip, and the plan's check that the release's file names are the ones the updater
 * asks for (an updater run against a fake GitHub serving the tool's output under the asset names).
 */
public class ReleaseTest
{
	private static final KeyPair KEY = generate();
	private static final KeyPair OTHER = generate();
	private static final String RELEASES = "http://releases.test";

	@TempDir
	Path dir;

	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

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

	private static String spki(KeyPair key)
	{
		return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
	}

	/** The key as {@code openssl genpkey -algorithm ed25519} writes it: PKCS#8 PEM. */
	private static String pem(KeyPair key)
	{
		Base64.Encoder lines = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8));
		return "-----BEGIN PRIVATE KEY-----\n" + lines.encodeToString(key.getPrivate().getEncoded())
			+ "\n-----END PRIVATE KEY-----\n";
	}

	private String outText()
	{
		return outBytes.toString(StandardCharsets.UTF_8);
	}

	private int run(String... args)
	{
		return Release.run(args, List.of(), out);
	}

	/** A lib folder with the launcher's jar for {@code version}, the other jars named, and a file that isn't a jar. */
	private Path lib(String version, String... others) throws IOException
	{
		Path lib = Files.createDirectories(dir.resolve("lib"));
		Files.writeString(lib.resolve(Release.LAUNCHER_JAR_PREFIX + version + ".jar"), "launcher " + version);
		for (String other : others)
		{
			Files.writeString(lib.resolve(other), "the jar " + other);
		}
		Files.writeString(lib.resolve("README.txt"), "not a jar");
		return lib;
	}

	private Path pins(String json) throws IOException
	{
		return Files.writeString(dir.resolve("runelite-pins.json"), json);
	}

	private Path pins() throws IOException
	{
		return pins("{ \"pins\": [ { \"revision\": 241, \"runelite\": \"1.13.4\", \"commit\": \"" + ManifestTest.COMMIT
			+ "\",\n \"bootstrapSha256\": \"" + ManifestTest.SHA256 + "\",\n \"source\": \"static.runelite.net\" } ]"
			+ " }\n");
	}

	/** The server's committed modulus, upper case here to see the tool write it the way the manifest wants it. */
	private Path modulus() throws IOException
	{
		return Files.writeString(dir.resolve("rsa-modulus.txt"), ManifestTest.MODULUS.toUpperCase() + "\n");
	}

	private String[] manifestArgs(Path lib, String version, String minBootstrap, String... more) throws IOException
	{
		List<String> args = new java.util.ArrayList<>(List.of("manifest", "--version", version, "--min-bootstrap",
			minBootstrap, "--lib", lib.toString(), "--pins", pins().toString(), "--modulus", modulus().toString(),
			"--out", dir.resolve("manifest.json").toString()));
		args.addAll(List.of(more));
		return args.toArray(String[]::new);
	}

	private Manifest written() throws IOException, ManifestException
	{
		return Manifest.parse(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
	}

	@Test
	public void theManifestNamesEveryJarUnderLibWithItsHashAndSize() throws Exception
	{
		Path lib = lib("0.2.0", "gson-2.13.1.jar", "asm-9.11.jar");
		assertEquals(0, run(manifestArgs(lib, "0.2.0", "0.1.0")), outText());
		Manifest manifest = written();
		assertEquals("0.2.0", manifest.version);
		assertEquals("0.1.0", manifest.minBootstrapVersion);
		assertEquals(new Manifest.Server("odinsgate.net", 43594, 43595, ManifestTest.MODULUS), manifest.server);
		assertEquals(new Manifest.Pin(241, "1.13.4", ManifestTest.COMMIT, ManifestTest.SHA256), manifest.pin(241));
		assertEquals(List.of("lib/asm-9.11.jar", "lib/gson-2.13.1.jar", "lib/odinsgate-launcher-0.2.0.jar"),
			manifest.files.stream().map(Manifest.FileEntry::path).toList(), "every jar, sorted, nothing else");
		for (Manifest.FileEntry entry : manifest.files)
		{
			byte[] bytes = Files.readAllBytes(lib.resolve(entry.assetName()));
			assertEquals(RuneLiteCache.sha256(bytes), entry.sha256(), entry.path());
			assertEquals(bytes.length, entry.size(), entry.path());
		}
		assertTrue(outText().contains("3 files, 1 pin(s), server odinsgate.net:43594"), outText());
		String line = "odinsgate-launcher-0.2.0.jar " + "launcher 0.2.0".length() + " bytes";
		assertTrue(outText().contains(line), outText());
	}

	@Test
	public void anotherHostAndGamePortGoInWithTheHttpPortBesideIt() throws Exception
	{
		Path lib = lib("0.2.0");
		assertEquals(0, run(manifestArgs(lib, "0.2.0", "0.2.0", "--host", "staging.odinsgate.net", "--game-port",
			"44594")), outText());
		assertEquals(new Manifest.Server("staging.odinsgate.net", 44594, 44595, ManifestTest.MODULUS),
			written().server);
	}

	@Test
	public void aLibFolderBuiltFromAnotherVersionIsRefused() throws Exception
	{
		Path lib = lib("0.1.0", "gson-2.13.1.jar");
		assertEquals(1, run(manifestArgs(lib, "0.2.0", "0.1.0")));
		assertTrue(outText().contains("has no odinsgate-launcher-0.2.0.jar"), outText());
		assertFalse(Files.exists(dir.resolve("manifest.json")), "nothing is written");
	}

	@Test
	public void aMinBootstrapAboveTheVersionIsRefused() throws Exception
	{
		Path lib = lib("0.2.0");
		assertEquals(1, run(manifestArgs(lib, "0.2.0", "0.3.0")));
		assertTrue(outText().contains("minBootstrapVersion 0.3.0 is above the version 0.2.0"), outText());
	}

	@Test
	public void aPinTableWithNoPinsIsRefusedAsALauncherWould() throws Exception
	{
		Path lib = lib("0.2.0");
		pins("{ \"pins\": [] }");
		assertEquals(1, Release.run(new String[]{"manifest", "--version", "0.2.0", "--min-bootstrap", "0.2.0", "--lib",
			lib.toString(), "--pins", dir.resolve("runelite-pins.json").toString(), "--modulus", modulus().toString(),
			"--out", dir.resolve("manifest.json").toString()}, List.of(), out));
		assertTrue(outText().contains("\"pins\" is empty"), outText());
	}

	@Test
	public void aMissingOptionOrCommandIsAUsageError()
	{
		assertEquals(2, run());
		assertEquals(2, run("publish"));
		assertEquals(2, run("manifest", "--version", "0.2.0"));
		assertTrue(outText().contains("--min-bootstrap <value> is needed"), outText());
		assertEquals(2, run("sign", "--key"));
	}

	@Test
	public void signingAndVerifyingRoundTrip() throws Exception
	{
		Path lib = lib("0.2.0");
		assertEquals(0, run(manifestArgs(lib, "0.2.0", "0.2.0")), outText());
		Path key = Files.writeString(dir.resolve("release.pem"), pem(KEY));
		Path manifest = dir.resolve("manifest.json");
		Path signature = dir.resolve("manifest.json.sig");
		assertEquals(0, run("sign", "--key", key.toString(), "--manifest", manifest.toString(), "--out",
			signature.toString()), outText());
		String line = Files.readString(signature, StandardCharsets.UTF_8);
		assertTrue(line.endsWith("\n") && !line.trim().contains("\n"), "one base64 line: " + line);
		assertEquals(64, Base64.getDecoder().decode(line.trim()).length, "a 64-byte Ed25519 signature");

		assertEquals(0, run("verify", "--manifest", manifest.toString(), "--signature", signature.toString(), "--key",
			spki(KEY)), outText());
		assertTrue(outText().contains("verifies against the 1 key(s) given: version 0.2.0"), outText());
		assertEquals(1, run("verify", "--manifest", manifest.toString(), "--signature", signature.toString(), "--key",
			spki(OTHER)));
		assertTrue(outText().contains("does NOT verify"), outText());
		assertEquals(0, run("verify", "--manifest", manifest.toString(), "--signature", signature.toString(), "--key",
			spki(OTHER), "--key", spki(KEY)), "any of the keys given will do");

		Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8) + " ");
		assertEquals(1, run("verify", "--manifest", manifest.toString(), "--signature", signature.toString(), "--key",
			spki(KEY)), "a byte more and the signature is for other bytes");
	}

	@Test
	public void verifyWithoutKeysUsesTheBuiltInOnesAndStopsWhenThereAreNone() throws Exception
	{
		Path lib = lib("0.2.0");
		assertEquals(0, run(manifestArgs(lib, "0.2.0", "0.2.0")), outText());
		Path key = Files.writeString(dir.resolve("release.pem"), pem(KEY));
		String manifest = dir.resolve("manifest.json").toString();
		String signature = dir.resolve("manifest.json.sig").toString();
		assertEquals(0, run("sign", "--key", key.toString(), "--manifest", manifest, "--out", signature), outText());
		assertEquals(1, Release.run(new String[]{"verify", "--manifest", manifest, "--signature", signature}, List.of(),
			out));
		assertTrue(outText().contains("no release keys are built into this launcher yet"), outText());
		assertEquals(0, Release.run(new String[]{"verify", "--manifest", manifest, "--signature", signature},
			List.of(OTHER.getPublic(), KEY.getPublic()), out), outText());
		assertTrue(outText().contains("verifies against the 2 built-in key(s)"), outText());
		assertEquals(1, Release.run(new String[]{"verify", "--manifest", manifest, "--signature", signature},
			List.of(OTHER.getPublic()), out));
	}

	@Test
	public void aKeyFileThatIsntAPkcs8PemIsRefused() throws Exception
	{
		Path lib = lib("0.2.0");
		assertEquals(0, run(manifestArgs(lib, "0.2.0", "0.2.0")), outText());
		Path key = Files.writeString(dir.resolve("release.pem"),
			"-----BEGIN OPENSSH PRIVATE KEY-----\nabcd\n-----END OPENSSH PRIVATE KEY-----\n");
		assertEquals(1, run("sign", "--key", key.toString(), "--manifest", dir.resolve("manifest.json").toString(),
			"--out", dir.resolve("manifest.json.sig").toString()));
		assertTrue(outText().contains("isn't a PKCS#8 PEM"), outText());
		Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})
			+ "\n-----END PRIVATE KEY-----\n");
		assertEquals(1, run("sign", "--key", key.toString(), "--manifest", dir.resolve("manifest.json").toString(),
			"--out", dir.resolve("manifest.json.sig").toString()));
		assertTrue(outText().contains("doesn't hold an Ed25519 key"), outText());
	}

	/**
	 * The plan's check: the names the tool publishes under are the ones the updater fetches. A fake GitHub serves the
	 * tool's manifest and signature at {@code latest/download/} and each jar under {@code download/v<version>/} by
	 * its asset name, and an installed 0.1.0 shell updates to the release from it.
	 */
	@Test
	public void theFilesAreNamedTheWayTheUpdaterFetchesThem() throws Exception
	{
		Path lib = lib("0.2.0", "gson-2.13.1.jar", "asm-9.11.jar");
		assertEquals(0, run(manifestArgs(lib, "0.2.0", "0.1.0")), outText());
		Path key = Files.writeString(dir.resolve("release.pem"), pem(KEY));
		Path manifest = dir.resolve("manifest.json");
		Path signature = dir.resolve("manifest.json.sig");
		assertEquals(0, run("sign", "--key", key.toString(), "--manifest", manifest.toString(), "--out",
			signature.toString()), outText());

		Map<String, byte[]> served = new LinkedHashMap<>();
		served.put(RELEASES + "/latest/download/manifest.json", Files.readAllBytes(manifest));
		served.put(RELEASES + "/latest/download/manifest.json.sig", Files.readAllBytes(signature));
		for (Manifest.FileEntry entry : written().files)
		{
			served.put(RELEASES + "/download/v0.2.0/" + entry.assetName(),
				Files.readAllBytes(lib.resolve(entry.assetName())));
		}
		Path data = dir.resolve("data");
		Updater updater = new Updater(data, "0.1.0", null, List.<PublicKey>of(KEY.getPublic()), RELEASES, uri ->
		{
			byte[] bytes = served.get(uri.toString());
			if (bytes == null)
			{
				throw new IOException("404 " + uri);
			}
			return bytes;
		}, (from, to) -> Files.move(from, to), duration -> { },
			Clock.fixed(Instant.parse("2026-10-29T23:00:00Z"), ZoneOffset.UTC), out);
		Updater.Outcome outcome = updater.update();
		Updater.Run run = assertInstanceOf(Updater.Run.class, outcome, outText());
		assertEquals("0.2.0", run.version());
		assertFalse(run.installed(), "the release was downloaded, not the shell itself");
		assertEquals(List.of("asm-9.11.jar", "gson-2.13.1.jar", "odinsgate-launcher-0.2.0.jar"),
			run.jars().stream().map(jar -> jar.getFileName().toString()).sorted().toList());
		for (Path jar : run.jars())
		{
			assertTrue(Files.exists(jar), jar.toString());
		}
	}
}
