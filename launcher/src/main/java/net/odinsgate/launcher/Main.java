package net.odinsgate.launcher;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The launcher's own entry point, what the installer's executable and the test VM's scripts start. A friend gets
 * the window ({@link LauncherWindow}); {@code --auto} (the test VM, CI) and a machine without a display get the
 * same steps straight through with nobody at the screen ({@link LauncherRun#run}). The signed manifest from GitHub
 * (the release PR) will replace {@code --manifest}. The runtime this JVM runs on is the one the game gets (the
 * bundled runtime keeps {@code bin/java}), and this JVM's class path is the loader's.
 */
public final class Main
{
	/** The jar's Implementation-Version, or "dev" when run from classes. */
	public static final String VERSION = version();
	static final int EXIT_USAGE = 64;
	static final int EXIT_MANIFEST = 65;

	private Main()
	{
	}

	public static void main(String[] args)
	{
		System.exit(run(args, System.out));
	}

	/** The whole run, returning the exit code, so a test can call it without a JVM exit. */
	static int run(String[] args, PrintStream out)
	{
		LaunchOptions options;
		try
		{
			options = LaunchOptions.parse(List.of(args));
		}
		catch (IllegalArgumentException e)
		{
			out.println("[launcher] " + e.getMessage());
			return EXIT_USAGE;
		}
		if (options.manifest() == null)
		{
			out.println("[launcher] --manifest <file> is needed until releases ship the signed manifest; "
				+ LaunchOptions.USAGE);
			return EXIT_USAGE;
		}
		Manifest manifest;
		try
		{
			manifest = Manifest.parse(Files.readString(options.manifest(), StandardCharsets.UTF_8));
		}
		catch (IOException | ManifestException e)
		{
			out.println("[launcher] " + options.manifest() + ": " + e.getMessage());
			return EXIT_MANIFEST;
		}
		Platform platform = Platform.detect();
		Map<String, String> env = System.getenv();
		Path userHome = Path.of(System.getProperty("user.home"));
		Path dataDir = options.dataDir(platform, env, userHome);
		Clock clock = Clock.systemUTC();
		LauncherRun.Steps steps = LauncherRun.real(dataDir);
		LauncherRun run = new LauncherRun(VERSION, steps, clock);
		LauncherRun.Session session = run.session(options, manifest, platform, env, userHome, thisJvm(), out);
		if (options.auto())
		{
			return run.run(session);
		}
		if (GraphicsEnvironment.isHeadless())
		{
			out.println("[launcher] no display here: running without the window");
			return run.run(session);
		}
		return LauncherWindow.open(session, steps, session.firstRun(), clock, out);
	}

	/** This JVM's runtime and class path: what the game's child JVM is started from. */
	static Launch.Installed thisJvm()
	{
		List<Path> classpath = Arrays.stream(System.getProperty("java.class.path", "")
				.split(Pattern.quote(File.pathSeparator)))
			.filter(entry -> !entry.isBlank())
			.map(Path::of)
			.toList();
		return new Launch.Installed(Path.of(System.getProperty("java.home")), classpath);
	}

	private static String version()
	{
		String version = Main.class.getPackage() == null ? null : Main.class.getPackage().getImplementationVersion();
		return version == null ? "dev" : version;
	}
}
