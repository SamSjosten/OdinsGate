package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The launcher's own command line: friends pass nothing, the VM and CI pass the mode flags. */
public class LaunchOptionsTest
{
	private static final Manifest.Server SIGNED =
		new Manifest.Server("odinsgate.net", 43594, 43595, FakeClient.OUR_MODULUS);
	private static final Platform LINUX = new Platform(Platform.Os.LINUX, Platform.Arch.X64);
	private static final Path HOME = Path.of("/home/friend");

	@Test
	public void theHealthyMarkerIsAnAbsolutePath()
	{
		LaunchOptions options = LaunchOptions.parse(List.of("--healthy", "marks/.healthy"));
		assertEquals(Path.of("marks/.healthy").toAbsolutePath().normalize(), options.healthy());
		assertEquals(null, LaunchOptions.NONE.healthy());
	}

	@Test
	public void noArgumentsIsFriendMode()
	{
		LaunchOptions options = LaunchOptions.parse(List.of());
		assertEquals(LaunchOptions.NONE, options);
		assertFalse(options.auto());
		assertFalse(options.developerMode());
		assertNull(options.statusFile());
		assertNull(options.manifest());
		assertSame(SIGNED, options.launchServer(SIGNED), "the signed server, untouched");
		assertEquals(LINUX.dataDir(Map.of(), HOME), options.dataDir(LINUX, Map.of(), HOME));
	}

	@Test
	public void everyFlagIsRead()
	{
		LaunchOptions options = LaunchOptions.parse(List.of("--auto", "--dev", "--home", "/vm/launcher",
			"--status-file", "/vm/launcher/status.json", "--server", "staging.odinsgate.net:44594",
			"--manifest", "/vm/launcher/manifest.json"));
		assertTrue(options.auto());
		assertTrue(options.developerMode());
		assertEquals(Path.of("/vm/launcher"), options.home());
		assertEquals(Path.of("/vm/launcher/status.json"), options.statusFile());
		assertEquals(Path.of("/vm/launcher/manifest.json"), options.manifest());
		assertEquals(new LaunchOptions.ServerOverride("staging.odinsgate.net", 44594, 44595), options.server());
		assertEquals(Path.of("/vm/launcher"), options.dataDir(LINUX, Map.of(), HOME), "--home wins over the system");

		Manifest.Server server = options.launchServer(SIGNED);
		assertEquals("staging.odinsgate.net", server.host());
		assertEquals(44594, server.gamePort());
		assertEquals(44595, server.httpPort(), "the HTTP port is the game port plus one, as our server pairs them");
		assertEquals(SIGNED.modulus(), server.modulus(), "the key is always the manifest's");
	}

	@Test
	public void theHttpPortCanBeGivenToo()
	{
		LaunchOptions options = LaunchOptions.parse(List.of("--server", "192.168.0.210:44594:48595"));
		assertEquals(new LaunchOptions.ServerOverride("192.168.0.210", 44594, 48595), options.server());
	}

	@Test
	public void aRelativeHomeIsMadeAbsolute()
	{
		LaunchOptions options = LaunchOptions.parse(List.of("--home", "data/launcher"));
		assertTrue(options.home().isAbsolute(), options.home().toString());
		assertTrue(options.home().endsWith(Path.of("data", "launcher")), options.home().toString());
	}

	@Test
	public void aBadServerIsRefused()
	{
		for (String bad : List.of("nohost", "odinsgate.net:abc", "odinsgate.net:0", "odinsgate.net:65536",
			"odinsgate.net:65535", "bad..host:43594", "odinsgate.net:1:2:3", ":43594", "odinsgate.net:"))
		{
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> LaunchOptions.parse(List.of("--server", bad)), bad);
			assertTrue(refused.getMessage().startsWith("--server"), refused.getMessage());
		}
	}

	@Test
	public void aFlagWithoutItsValueIsRefused()
	{
		for (List<String> bad : List.of(List.of("--home"), List.of("--home", "--auto"), List.of("--status-file", ""),
			List.of("--auto", "--server")))
		{
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> LaunchOptions.parse(bad), bad.toString());
			assertTrue(refused.getMessage().contains(LaunchOptions.USAGE), refused.getMessage());
		}
	}

	@Test
	public void anUnknownFlagIsRefusedWithTheUsage()
	{
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> LaunchOptions.parse(List.of("--auto", "--what")));
		assertTrue(refused.getMessage().contains("--what"), refused.getMessage());
		assertTrue(refused.getMessage().contains(LaunchOptions.USAGE), refused.getMessage());
	}
}
