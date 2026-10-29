package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ClientCommandTest
{
	private static ClientCommand.Spec spec(Platform.Os os, boolean developerMode)
	{
		return new ClientCommand.Spec(
			new Platform(os, Platform.Arch.X64),
			Path.of("runtime"),
			List.of("-Xmx512m"),
			Path.of("data", "game-home"),
			Path.of("data", "rtconf.json"),
			"odinsgate.net", 43595,
			List.of(Path.of("lib", "loader.jar"), Path.of("lib", "asm.jar")),
			"net.odinsgate.loader.Loader",
			List.of("--main", "net.runelite.client.RuneLite"),
			developerMode);
	}

	@Test
	public void theCommandRunsOurLoaderFromTheBundledRuntime()
	{
		List<String> command = ClientCommand.build(spec(Platform.Os.LINUX, false));
		assertEquals(Path.of("runtime", "bin", "java").toString(), command.get(0));
		assertTrue(command.contains("-Xmx512m"), "the bootstrap's JVM arguments are kept: " + command);
		int cp = command.indexOf("-cp");
		assertEquals(Path.of("lib", "loader.jar") + ":" + Path.of("lib", "asm.jar"), command.get(cp + 1));
		assertEquals("net.odinsgate.loader.Loader", command.get(cp + 2));
		assertEquals(List.of("--main", "net.runelite.client.RuneLite"), command.subList(cp + 3, cp + 5));
	}

	@Test
	public void windowsGetsItsOwnJavaAndSeparator()
	{
		List<String> command = ClientCommand.build(spec(Platform.Os.WINDOWS, false));
		assertTrue(command.get(0).endsWith("java.exe"), command.get(0));
		assertEquals(Path.of("lib", "loader.jar") + ";" + Path.of("lib", "asm.jar"),
			command.get(command.indexOf("-cp") + 1));
	}

	@Test
	public void theGameNeverSeesTheFriendsRealHome()
	{
		List<String> command = ClientCommand.build(spec(Platform.Os.LINUX, false));
		assertTrue(command.contains("-Duser.home=" + Path.of("data", "game-home")), command.toString());
	}

	@Test
	public void theRuntimeConfigIsAlwaysPassedBecauseRuneLiteDiesWithoutOne()
	{
		for (boolean developerMode : new boolean[]{false, true})
		{
			List<String> command = ClientCommand.build(spec(Platform.Os.LINUX, developerMode));
			assertTrue(command.contains("-Drunelite.rtconf=" + Path.of("data", "rtconf.json")), command.toString());
		}
	}

	@Test
	public void theWorldListAndJavConfigComeFromOurServerAlone()
	{
		List<String> command = ClientCommand.build(spec(Platform.Os.LINUX, false));
		assertTrue(command.contains("-Drunelite.http-service.url=http://odinsgate.net:43595/rl/"), command.toString());
		int javConfig = command.indexOf("--jav_config");
		assertEquals("http://odinsgate.net:43595/launcher/jav_config.ws", command.get(javConfig + 1));
		assertTrue(command.indexOf("--") < javConfig, "RuneLite's own arguments follow the loader's --");
		assertTrue(command.contains("--disable-telemetry"), command.toString());
		assertTrue(command.contains("--noupdate"), command.toString());
	}

	@Test
	public void developerModeIsTheTestVmsAloneAndOffByDefault()
	{
		List<String> normal = ClientCommand.build(spec(Platform.Os.LINUX, false));
		assertFalse(normal.contains("--developer-mode"), normal.toString());
		assertFalse(normal.contains("-ea:net.runelite.client.RuneLite"), normal.toString());

		List<String> developer = ClientCommand.build(spec(Platform.Os.LINUX, true));
		assertTrue(developer.contains("--developer-mode"), developer.toString());
		assertTrue(developer.contains("-ea:net.runelite.client.RuneLite"), developer.toString());
		assertTrue(developer.indexOf("-ea:net.runelite.client.RuneLite") < developer.indexOf("-cp"),
			"assertions are a JVM argument, before the classpath");
	}
}
