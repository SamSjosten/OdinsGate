package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class PlatformTest
{
	private static final Path HOME = Path.of("/home/friend");

	@Test
	public void theSystemsAndChipsAreToldApart()
	{
		assertEquals(new Platform(Platform.Os.WINDOWS, Platform.Arch.X64), Platform.detect("Windows 11", "amd64"));
		assertEquals(new Platform(Platform.Os.MAC, Platform.Arch.AARCH64), Platform.detect("Mac OS X", "aarch64"));
		assertEquals(new Platform(Platform.Os.MAC, Platform.Arch.X64), Platform.detect("Mac OS X", "x86_64"));
		assertEquals(new Platform(Platform.Os.LINUX, Platform.Arch.X64), Platform.detect("Linux", "amd64"));
		assertEquals(new Platform(Platform.Os.LINUX, Platform.Arch.AARCH64), Platform.detect("Linux", "arm64"));
	}

	@Test
	public void windowsDataLivesUnderLocalAppData()
	{
		Platform windows = new Platform(Platform.Os.WINDOWS, Platform.Arch.X64);
		assertEquals(Path.of("/winhome/AppData/Local/OdinsGate"),
			windows.dataDir(Map.of("LOCALAPPDATA", "/winhome/AppData/Local"), HOME));
		// Without the variable, the same folder is built from the home folder.
		assertEquals(HOME.resolve("AppData").resolve("Local").resolve("OdinsGate"),
			windows.dataDir(Map.of(), HOME));
	}

	@Test
	public void macDataLivesUnderApplicationSupport()
	{
		Platform mac = new Platform(Platform.Os.MAC, Platform.Arch.AARCH64);
		assertEquals(HOME.resolve("Library").resolve("Application Support").resolve("OdinsGate"),
			mac.dataDir(Map.of(), HOME));
	}

	@Test
	public void linuxDataFollowsXdg()
	{
		Platform linux = new Platform(Platform.Os.LINUX, Platform.Arch.X64);
		assertEquals(Path.of("/xdg/data/odinsgate"), linux.dataDir(Map.of("XDG_DATA_HOME", "/xdg/data"), HOME));
		assertEquals(HOME.resolve(".local").resolve("share").resolve("odinsgate"), linux.dataDir(Map.of(), HOME));
		// The XDG spec calls a relative XDG_DATA_HOME invalid: it is ignored, not resolved against the working dir.
		assertEquals(HOME.resolve(".local").resolve("share").resolve("odinsgate"),
			linux.dataDir(Map.of("XDG_DATA_HOME", "relative/data"), HOME));
	}

	@Test
	public void theJavaAndTheClasspathSeparatorMatchTheSystem()
	{
		Path runtime = Path.of("runtime");
		assertEquals("java.exe",
			new Platform(Platform.Os.WINDOWS, Platform.Arch.X64).javaExecutable(runtime).getFileName().toString());
		assertEquals("java",
			new Platform(Platform.Os.MAC, Platform.Arch.AARCH64).javaExecutable(runtime).getFileName().toString());
		assertEquals(';', new Platform(Platform.Os.WINDOWS, Platform.Arch.X64).pathSeparator());
		assertEquals(':', new Platform(Platform.Os.LINUX, Platform.Arch.X64).pathSeparator());
	}
}
