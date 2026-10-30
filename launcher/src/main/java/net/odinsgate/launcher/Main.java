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
 * The launcher's own entry point, what the installer's executable and the test VM's scripts start. Without
 * {@code --manifest} it is the self-update bootstrap first ({@link Updater}): the signed manifest from GitHub decides
 * which launcher version runs, a downloaded one is run in this JVM through {@link VersionLoader}, and the installed
 * code runs itself when it is the newest or the only one that verifies. With {@code --manifest <file>} (the test VM,
 * and a version the shell handed off to) the file is trusted as it is and no update is looked for. A friend gets the
 * window ({@link LauncherWindow}); {@code --auto} (the test VM, CI) and a machine without a display get the same
 * steps straight through with nobody at the screen ({@link LauncherRun#run}). The runtime this JVM runs on is the
 * one the game gets (the bundled runtime keeps {@code bin/java}), and this code's jars are the loader's class path.
 * {@code --self-test} ({@link SelfTest}) checks exactly those two things against a fake server and stops.
 *
 * <p>{@link #run(String[], PrintStream)} is the one contract between an installed shell and every later version
 * it may download: public, static, these arguments, an exit code back. It stays.
 */
public final class Main
{
	/** The jar's Implementation-Version, or "dev" when run from classes. */
	public static final String VERSION = version();
	static final int EXIT_USAGE = 64;
	static final int EXIT_MANIFEST = 65;
	/** The release needs a newer installer than this one. */
	static final int EXIT_INSTALLER = 67;
	static final String INSTALLER_LINE = "Please download the new installer.";

	/** How the shell gets its updater; a test's updater talks to a fake GitHub. */
	interface Updaters
	{
		Updater updater(Path dataDir, PrintStream log);
	}

	/** The launch once the manifest is settled; a test's records the call instead of checking a server. */
	interface Launches
	{
		int launch(LaunchOptions options, Manifest manifest, Path dataDir, PrintStream log);
	}

	private Main()
	{
	}

	public static void main(String[] args)
	{
		System.exit(run(args, System.out));
	}

	/** The whole run, returning the exit code, so a test can call it without a JVM exit. */
	public static int run(String[] args, PrintStream out)
	{
		Updaters real = (dataDir, log) -> Updater.real(dataDir, VERSION, appDir(), log);
		return run(args, out, TrustedKeys.trusted().isEmpty() ? null : real);
	}

	/**
	 * The run with the shell's updater made by {@code updaters}: a test's talks to a fake GitHub. A null means no
	 * release keys are built in yet, so nothing from GitHub could be trusted and a run needs {@code --manifest}.
	 */
	static int run(String[] args, PrintStream out, Updaters updaters)
	{
		return run(args, out, updaters, null);
	}

	/** As above, with the launch itself made by {@code launches}; null for the real one. */
	static int run(String[] args, PrintStream out, Updaters updaters, Launches launches)
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
		if (options.selfTest())
		{
			// Before anything that needs a manifest or the keys: the self-test dials nothing real.
			Path dataDir = options.dataDir(Platform.detect(), System.getenv(),
				Path.of(System.getProperty("user.home")));
			try (PrintStream log = openLog(dataDir, out))
			{
				return SelfTest.run(Platform.detect(), thisJvm(), dataDir, log);
			}
		}
		if (options.manifest() == null && updaters == null)
		{
			out.println("[launcher] no release keys are built into this launcher yet, so --manifest <file> is needed; "
				+ LaunchOptions.USAGE);
			return EXIT_USAGE;
		}
		Platform platform = Platform.detect();
		Map<String, String> env = System.getenv();
		Path userHome = Path.of(System.getProperty("user.home"));
		Path dataDir = options.dataDir(platform, env, userHome);
		Clock clock = Clock.systemUTC();
		Launches launcher = launches != null ? launches
			: (o, manifest, d, l) -> launch(o, manifest, platform, env, userHome, d, clock, l);
		try (PrintStream log = openLog(dataDir, out))
		{
			if (options.manifest() != null)
			{
				Manifest manifest;
				try
				{
					manifest = Manifest.parse(Files.readString(options.manifest(), StandardCharsets.UTF_8));
				}
				catch (IOException | ManifestException e)
				{
					log.println("[launcher] " + options.manifest() + ": " + e.getMessage());
					return EXIT_MANIFEST;
				}
				return launcher.launch(options, manifest, dataDir, log);
			}
			Updater updater = updaters.updater(dataDir, log);
			Updater.Outcome outcome = updater.update();
			if (outcome instanceof Updater.NoManifest none)
			{
				log.println("[launcher] " + none.why());
				return EXIT_MANIFEST;
			}
			if (outcome instanceof Updater.NeedsInstaller needs)
			{
				log.println("[launcher] " + needs.why() + ": " + INSTALLER_LINE + " " + Updater.DOWNLOAD_PAGE);
				if (!options.auto() && !GraphicsEnvironment.isHeadless())
				{
					LauncherWindow.installerNeeded(needs.why(), log);
				}
				return EXIT_INSTALLER;
			}
			Updater.Run run = (Updater.Run) outcome;
			if (!run.installed())
			{
				try
				{
					updater.markStarted(run.version());
					updater.watchdog(run.version()).start();
					String[] handed = Arrays.copyOf(args, args.length + 4);
					handed[args.length] = "--manifest";
					handed[args.length + 1] = run.manifestFile().toString();
					handed[args.length + 2] = "--healthy";
					handed[args.length + 3] = updater.healthyMarker(run.version()).toString();
					log.println("[launcher] " + VERSION + " handing off to version " + run.version());
					return VersionLoader.run(run.jars(), handed, out);
				}
				catch (IOException e)
				{
					log.println("[launcher] version " + run.version() + " could not run: " + e.getMessage());
					updater.markBad(run.version(), "it could not run: " + e.getMessage());
					log.println("[launcher] running the installed launcher " + VERSION + " instead");
				}
			}
			return launcher.launch(options, run.manifest(), dataDir, log);
		}
	}

	/** This launcher's own run, the window or the steps straight through, with {@code manifest} as the truth. */
	private static int launch(LaunchOptions options, Manifest manifest, Platform platform, Map<String, String> env,
		Path userHome, Path dataDir, Clock clock, PrintStream log)
	{
		Runnable healthy = healthyMarker(options.healthy(), log);
		LauncherRun.Steps steps = LauncherRun.real(dataDir);
		LauncherRun run = new LauncherRun(VERSION, steps, clock);
		LauncherRun.Session session = run.session(options, manifest, platform, env, userHome, thisJvm(), log);
		if (options.auto())
		{
			healthy.run();
			return run.run(session);
		}
		if (GraphicsEnvironment.isHeadless())
		{
			log.println("[launcher] no display here: running without the window");
			healthy.run();
			return run.run(session);
		}
		return LauncherWindow.open(session, steps, session.firstRun(), clock, log, healthy);
	}

	/**
	 * What {@code --healthy <file>} asks for: the file is written once this launcher is up (its window on screen,
	 * or its steps about to run), which tells the shell that handed off to this version that it works. Nothing
	 * without the option; a file that can't be written is logged, never a stop.
	 */
	static Runnable healthyMarker(Path file, PrintStream log)
	{
		if (file == null)
		{
			return () -> { };
		}
		return () ->
		{
			try
			{
				Files.createDirectories(file.toAbsolutePath().getParent());
				Files.writeString(file, java.time.Instant.now() + "\n", StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				log.println("[launcher] the healthy marker " + file + " could not be written: " + e.getMessage());
			}
		};
	}

	/**
	 * The launcher's own log under the data folder, rotated when large, with every line still going to {@code out}
	 * (the console, or what a wrapper script collects). A log that can't be opened is said so on {@code out}, which
	 * then is the log: a launcher never stops for its log.
	 */
	static PrintStream openLog(Path dataDir, PrintStream out)
	{
		Path file = dataDir.resolve(LauncherRun.LOGS_DIR).resolve(LauncherRun.LAUNCHER_LOG);
		try
		{
			return RotatingLog.open(file, out);
		}
		catch (IOException e)
		{
			out.println("[launcher] the log " + file + " could not be opened: " + e.getMessage());
			return new PrintStream(out, true, StandardCharsets.UTF_8)
			{
				@Override
				public void close()
				{
					// The caller's stream stays open.
					flush();
				}
			};
		}
	}

	/**
	 * This JVM's runtime and this code's class path: what the game's child JVM is started from. A version the shell
	 * loaded from its jars ({@link VersionLoader}) answers with those jars, not the shell's, so the game gets the
	 * loader of the version that is running.
	 */
	static Launch.Installed thisJvm()
	{
		return new Launch.Installed(Path.of(System.getProperty("java.home")), ownClasspath());
	}

	static List<Path> ownClasspath()
	{
		if (Main.class.getClassLoader() instanceof java.net.URLClassLoader loader)
		{
			List<Path> jars = new java.util.ArrayList<>();
			for (java.net.URL url : loader.getURLs())
			{
				if ("file".equals(url.getProtocol()))
				{
					jars.add(Path.of(java.net.URI.create(url.toString())));
				}
			}
			if (!jars.isEmpty())
			{
				return List.copyOf(jars);
			}
		}
		return Arrays.stream(System.getProperty("java.class.path", "").split(Pattern.quote(File.pathSeparator)))
			.filter(entry -> !entry.isBlank())
			.map(Path::of)
			.toList();
	}

	/** The installed layout's folder, the parent of the {@code lib/} this jar sits in; null when run from classes. */
	static Path appDir()
	{
		for (Path entry : ownClasspath())
		{
			String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
			if (name.startsWith("odinsgate-launcher") && name.endsWith(".jar") && entry.getParent() != null
				&& entry.getParent().getParent() != null)
			{
				return entry.getParent().getParent();
			}
		}
		return null;
	}

	private static String version()
	{
		String version = Main.class.getPackage() == null ? null : Main.class.getPackage().getImplementationVersion();
		return version == null ? "dev" : version;
	}
}
