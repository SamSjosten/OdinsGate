package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line per system and mode, from the flags the VM and CI pass, and the runtime config that always exists
 * before the game starts.
 */
public class LaunchTest
{
	private static final Manifest.Server SIGNED =
		new Manifest.Server("odinsgate.net", 43594, 43595, FakeClient.OUR_MODULUS);
	private static final Launch.Installed INSTALLED = new Launch.Installed(Path.of("runtime"),
		List.of(Path.of("lib", "odinsgate-launcher-0.1.0.jar"), Path.of("lib", "asm-9.11.jar")));
	private static final Launch.Pin PIN = new Launch.Pin(
		List.of(Path.of("cache", "injected-client-1.13.4.jar"), Path.of("cache", "client-1.13.4.jar")),
		List.of("-Xmx512m", "-XX:+UseSerialGC"));

	@TempDir
	Path userHome;

	private Launch plan(LaunchOptions options, Platform.Os os) throws Exception
	{
		return Launch.plan(options, new Platform(os, Platform.Arch.X64), Map.of(), userHome, SIGNED, INSTALLED, PIN);
	}

	@Test
	public void friendModeOnEverySystem() throws Exception
	{
		for (Platform.Os os : Platform.Os.values())
		{
			Launch launch = plan(LaunchOptions.NONE, os);
			Platform platform = new Platform(os, Platform.Arch.X64);
			assertEquals(platform.dataDir(Map.of(), userHome), launch.dataDir(), os.toString());
			assertEquals(launch.dataDir().resolve("home"), launch.gameHome());
			assertEquals(launch.dataDir().resolve("rtconf.json"), launch.rtconf());
			assertEquals(RuntimeConfig.CONTENT, Files.readString(launch.rtconf()), "the runtime config exists");
			assertEquals(SIGNED, launch.server());

			List<String> command = launch.command();
			assertEquals(platform.javaExecutable(Path.of("runtime")).toString(), command.get(0));
			assertTrue(command.containsAll(PIN.jvmArguments()), command.toString());
			assertTrue(command.contains("-Duser.home=" + launch.gameHome()), command.toString());
			assertTrue(command.contains("-Drunelite.rtconf=" + launch.rtconf()), command.toString());
			assertTrue(command.contains("-Drunelite.http-service.url=http://odinsgate.net:43595/rl/"),
				command.toString());
			assertTrue(command.contains("--disable-telemetry"), command.toString());
			assertFalse(command.contains("--developer-mode"), command.toString());
			assertFalse(command.contains("-ea:net.runelite.client.RuneLite"), command.toString());

			int main = command.indexOf(Loader.class.getName());
			assertTrue(main > 0, "the loader is the child JVM's main class: " + command);
			List<String> loaderArgs = command.subList(main + 1, command.indexOf("--"));
			assertEquals(Loader.arguments(PIN.gameJars(), SIGNED, Loader.RUNELITE_MAIN), loaderArgs);
			assertTrue(loaderArgs.containsAll(List.of("--allow", "odinsgate.net:43594", "odinsgate.net:43595")),
				"the game may reach the signed server's two ports: " + loaderArgs);
			assertEquals("http://odinsgate.net:43595/launcher/jav_config.ws",
				command.get(command.indexOf("--jav_config") + 1));
		}
	}

	@Test
	public void developerModeIsTheVmsFlagAlone() throws Exception
	{
		List<String> command = plan(LaunchOptions.parse(List.of("--dev")), Platform.Os.WINDOWS).command();
		assertTrue(command.contains("--developer-mode"), command.toString());
		assertTrue(command.contains("-ea:net.runelite.client.RuneLite"), command.toString());
		// --auto is the window's business, not the game's: the command is the friend-mode one.
		List<String> auto = plan(LaunchOptions.parse(List.of("--auto")), Platform.Os.WINDOWS).command();
		assertEquals(plan(LaunchOptions.NONE, Platform.Os.WINDOWS).command(), auto);
	}

	@Test
	public void homeMovesEverythingTheLaunchWrites() throws Exception
	{
		Path home = userHome.resolve("vm-launcher");
		Launch launch = plan(LaunchOptions.parse(List.of("--home", home.toString())), Platform.Os.WINDOWS);
		assertEquals(home, launch.dataDir());
		assertEquals(home.resolve("home"), launch.gameHome());
		assertEquals(home.resolve("rtconf.json"), launch.rtconf());
		assertTrue(Files.isRegularFile(launch.rtconf()));
		assertTrue(launch.command().contains("-Duser.home=" + home.resolve("home")), launch.command().toString());
	}

	@Test
	public void aStagingServerChangesTheHostThePortsAndTheLoadersPort() throws Exception
	{
		Launch launch = plan(LaunchOptions.parse(List.of("--server", "staging.odinsgate.net:44594")),
			Platform.Os.LINUX);
		assertEquals(new Manifest.Server("staging.odinsgate.net", 44594, 44595, SIGNED.modulus()), launch.server());
		List<String> command = launch.command();
		assertTrue(command.contains("-Drunelite.http-service.url=http://staging.odinsgate.net:44595/rl/"),
			command.toString());
		assertEquals("http://staging.odinsgate.net:44595/launcher/jav_config.ws",
			command.get(command.indexOf("--jav_config") + 1));
		int port = command.indexOf("--port");
		assertEquals("44594", command.get(port + 1), "the loader patches the game port into the client");
		assertEquals(SIGNED.modulus(), command.get(command.indexOf("--modulus") + 1), "the key stays the signed one");
	}

	@Test
	public void theRuntimeConfigIsRewrittenBeforeEveryLaunch() throws Exception
	{
		Launch first = plan(LaunchOptions.NONE, Platform.Os.MAC);
		Files.writeString(first.rtconf(), "{ \"outdatedClientVersions\": [\"1.13.4\"] }");
		Launch second = plan(LaunchOptions.NONE, Platform.Os.MAC);
		assertEquals(first.rtconf(), second.rtconf());
		assertEquals(RuntimeConfig.CONTENT, Files.readString(second.rtconf()));
	}
}
