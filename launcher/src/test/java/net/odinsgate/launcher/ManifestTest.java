package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ManifestTest
{
	static final String MODULUS = "ab".repeat(128);
	static final String SHA256 = "cd".repeat(32);
	static final String COMMIT = "1e634e64717418713898b06061a189ec1cdf948d";

	/** A manifest every rule passes; each test breaks one thing in it. */
	static String good()
	{
		return """
			{ "format": 1,
			  "version": "0.1.0",
			  "minBootstrapVersion": "0.1.0",
			  "server": { "host": "odinsgate.net", "gamePort": 43594, "httpPort": 43595, "modulus": "%s" },
			  "pins": [ { "revision": 241, "runelite": "1.13.4", "commit": "%s", "bootstrapSha256": "%s" } ],
			  "files": [ { "path": "lib/odinsgate-launcher-0.1.0.jar", "sha256": "%s", "size": 123456 } ] }
			""".formatted(MODULUS, COMMIT, SHA256, SHA256);
	}

	private static ManifestException rejected(String json)
	{
		return assertThrows(ManifestException.class, () -> Manifest.parse(json));
	}

	@Test
	public void theGoodManifestParsesWhole() throws Exception
	{
		Manifest manifest = Manifest.parse(good());
		assertEquals("0.1.0", manifest.version);
		assertEquals("0.1.0", manifest.minBootstrapVersion);
		assertEquals(new Manifest.Server("odinsgate.net", 43594, 43595, MODULUS), manifest.server);
		assertEquals(new Manifest.Pin(241, "1.13.4", COMMIT, SHA256), manifest.pin(241));
		assertNull(manifest.pin(242));
		assertEquals(1, manifest.pins().size());
		assertEquals(new Manifest.FileEntry("lib/odinsgate-launcher-0.1.0.jar", SHA256, 123456), manifest.files.get(0));
	}

	@Test
	public void unknownMembersAreIgnoredSoOldLaunchersSurviveAdditions() throws Exception
	{
		Manifest manifest = Manifest.parse(good().replace("\"format\": 1,", "\"format\": 1, \"notice\": \"hello\","));
		assertEquals("0.1.0", manifest.version);
	}

	@Test
	public void anotherFormatIsRefusedByName()
	{
		assertTrue(rejected(good().replace("\"format\": 1", "\"format\": 2")).getMessage().contains("format 2"));
	}

	@Test
	public void whatIsntJsonIsRefused()
	{
		rejected("{ half a manifest");
		rejected("[]");
		rejected("\"a string\"");
	}

	@Test
	public void aMissingMemberIsNamed()
	{
		String missing = good().replace("\"version\": \"0.1.0\",", "");
		assertTrue(rejected(missing).getMessage().contains("\"version\""));
	}

	@Test
	public void versionsAreDottedDigits()
	{
		rejected(good().replace("\"version\": \"0.1.0\"", "\"version\": \"v1\""));
		rejected(good().replace("\"minBootstrapVersion\": \"0.1.0\"", "\"minBootstrapVersion\": \"\""));
	}

	@Test
	public void aHostMustFormARequestUri() throws Exception
	{
		rejected(good().replace("odinsgate.net", "odinsgate..net"));
		rejected(good().replace("odinsgate.net", "odinsgate-"));
		rejected(good().replace("odinsgate.net", "-odinsgate.net"));
		rejected(good().replace("odinsgate.net", "odinsgate.net."));
		rejected(good().replace("odinsgate.net", "odinsgate.net/path"));
		// The LAN address friends' yaml uses today stays valid.
		assertEquals("192.168.0.210", Manifest.parse(good().replace("odinsgate.net", "192.168.0.210")).server.host());
	}

	@Test
	public void theModulusIsExactly1024LowercaseBits()
	{
		rejected(good().replace(MODULUS, MODULUS.toUpperCase()));
		rejected(good().replace(MODULUS, MODULUS.substring(2)));
		rejected(good().replace(MODULUS, MODULUS + "ab"));
	}

	@Test
	public void portsStayPorts()
	{
		rejected(good().replace("43594", "0"));
		rejected(good().replace("43595", "70000"));
		rejected(good().replace("43594", "43594.5"));
	}

	@Test
	public void aPinsCommitIsFortyLowercaseHexCharacters()
	{
		rejected(good().replace(COMMIT, COMMIT.substring(1)));
		rejected(good().replace(COMMIT, COMMIT.toUpperCase()));
		rejected(good().replace(COMMIT, COMMIT.substring(1) + "g"));
	}

	@Test
	public void twoPinsForOneRevisionAreRefused()
	{
		String pin = "{ \"revision\": 241, \"runelite\": \"1.13.4\", \"commit\": \"%s\", \"bootstrapSha256\": \"%s\" }"
			.formatted(COMMIT, SHA256);
		String doubled = good().replace(pin, pin + ", " + pin);
		assertNotEquals(good(), doubled, "the test's pin string no longer matches good()");
		assertTrue(rejected(doubled).getMessage().contains("241"));
	}

	@Test
	public void noPinsAtAllIsRefused()
	{
		String noPins = good().replaceFirst("\"pins\": \\[[^]]*]", "\"pins\": []");
		assertTrue(rejected(noPins).getMessage().contains("pins"));
	}

	@Test
	public void aFilePathCantLeaveTheFolder()
	{
		String path = "lib/odinsgate-launcher-0.1.0.jar";
		rejected(good().replace(path, "../evil.jar"));
		rejected(good().replace(path, "/etc/passwd"));
		rejected(good().replace(path, "lib\\\\evil.jar"));
		rejected(good().replace(path, "lib//evil.jar"));
		rejected(good().replace(path, ".hidden"));
		rejected(good().replace(path, ""));
	}

	@Test
	public void twoEntriesForOneFileAreRefused()
	{
		String file = "{ \"path\": \"lib/odinsgate-launcher-0.1.0.jar\", \"sha256\": \"%s\", \"size\": 123456 }"
			.formatted(SHA256);
		rejected(good().replaceFirst("\"files\": \\[[^]]*]", "\"files\": [ " + file + ", " + file + " ]"));
	}

	@Test
	public void aFilesSizeIsNeverNegative()
	{
		rejected(good().replace("123456", "-1"));
	}

	@Test
	public void anEmptyFilesListIsFineUntilReleasesFillIt() throws Exception
	{
		Manifest manifest = Manifest.parse(good().replaceFirst("\"files\": \\[[^]]*]", "\"files\": []"));
		assertTrue(manifest.files.isEmpty());
	}
}
