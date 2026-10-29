package net.odinsgate.launcher;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Which system and chip this is, and the places and separators that differ by it. The real values come from
 * {@link #detect()}; tests pass their own, so every system's paths are tested from the one CI runner.
 */
public record Platform(Os os, Arch arch)
{
	public enum Os
	{
		WINDOWS, MAC, LINUX
	}

	/**
	 * X64 or AARCH64, the two chips RuneLite ships natives for. Apple chips must be AARCH64: RuneLite's arm64
	 * natives only load when Java reports aarch64, so an Intel build on an Apple chip would draw nothing.
	 */
	public enum Arch
	{
		X64, AARCH64
	}

	public static Platform detect()
	{
		return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
	}

	public static Platform detect(String osName, String osArch)
	{
		String name = osName.toLowerCase(Locale.ROOT);
		Os os = name.contains("win") ? Os.WINDOWS
			: name.contains("mac") || name.contains("darwin") ? Os.MAC
			: Os.LINUX;
		String arch = osArch.toLowerCase(Locale.ROOT);
		return new Platform(os, arch.equals("aarch64") || arch.equals("arm64") ? Arch.AARCH64 : Arch.X64);
	}

	/**
	 * The launcher's own data folder: downloaded RuneLite jars, launcher versions, the game's home and the log. Its
	 * own, and never the friend's real RuneLite folder, whose repository2 their normal RuneLite launcher prunes to
	 * its own bootstrap - it would delete our pinned jars.
	 */
	public Path dataDir(Map<String, String> env, Path userHome)
	{
		switch (os)
		{
			case WINDOWS:
			{
				String localAppData = env.get("LOCALAPPDATA");
				Path base = localAppData != null && !localAppData.isBlank()
					? Path.of(localAppData)
					: userHome.resolve("AppData").resolve("Local");
				return base.resolve(Names.DIR_CAPITALIZED);
			}
			case MAC:
				return userHome.resolve("Library").resolve("Application Support").resolve(Names.DIR_CAPITALIZED);
			default:
			{
				String xdgData = env.get("XDG_DATA_HOME");
				Path base = xdgData != null && !xdgData.isBlank()
					? Path.of(xdgData)
					: userHome.resolve(".local").resolve("share");
				return base.resolve(Names.DIR_LOWERCASE);
			}
		}
	}

	/** What joins a -cp list on this system. */
	public char pathSeparator()
	{
		return os == Os.WINDOWS ? ';' : ':';
	}

	/** The java to start the game with, inside our bundled runtime (built keeping bin/java; jpackage strips it). */
	public Path javaExecutable(Path runtimeDir)
	{
		return runtimeDir.resolve("bin").resolve(os == Os.WINDOWS ? "java.exe" : "java");
	}
}
