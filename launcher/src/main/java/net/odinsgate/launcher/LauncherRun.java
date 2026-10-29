package net.odinsgate.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * One run of the launcher with nobody at the screen, from the start to the game's exit: what {@code --auto} and the
 * test VM get now, and the sequence the window (its own PR) will drive step by step. The order is the plan's: check
 * the server (plain data, hints only), fetch the pinned RuneLite (verified on the way in and again before launch),
 * plan the launch (the runtime config written), start the game in its child JVM through {@link Loader}, and wait
 * for it. Every step is logged and, when {@code --status-file} was given, written to the status file; every stop
 * names its reason in both and ends with an exit code a script can read. The parts that touch the outside world sit
 * behind {@link Steps}, so the tests run the whole sequence with stand-ins.
 */
public final class LauncherRun
{
	/** The outside world: our server, RuneLite's servers and the child process. */
	public interface Steps
	{
		ServerCheck.Result check(Manifest.Server server, Manifest manifest);

		/** The pin's jars for this system, hashed, and its bootstrap's JVM arguments. */
		Launch.Pin fetch(Manifest.Pin pin, Platform platform) throws IOException, BootstrapException;

		/** Starts the game's command line with its output going to {@code gameLog}. */
		Process start(List<String> command, Path gameLog) throws IOException;
	}

	/** The launcher's exit codes when the game never ran; when it did, the launcher exits with the game's own. */
	public static final int EXIT_OFFLINE = 10;
	public static final int EXIT_FULL = 11;
	public static final int EXIT_UNKNOWN_REVISION = 12;
	public static final int EXIT_KEY_CHANGED = 13;
	public static final int EXIT_NO_INTERNET = 14;
	public static final int EXIT_DOWNLOAD = 20;
	public static final int EXIT_START = 21;

	/** Under the data folder: RuneLite's cache (one folder per pin) and the logs. */
	static final String RUNELITE_DIR = "runelite";
	static final String LOGS_DIR = "logs";
	static final String GAME_LOG = "game.log";

	private final String version;
	private final Steps steps;
	private final Clock clock;

	public LauncherRun(String version, Steps steps, Clock clock)
	{
		this.version = version;
		this.steps = steps;
		this.clock = clock;
	}

	/** The real outside world, with RuneLite's cache under {@code dataDir}. */
	public static Steps real(Path dataDir)
	{
		return new Steps()
		{
			@Override
			public ServerCheck.Result check(Manifest.Server server, Manifest manifest)
			{
				return new ServerCheck(server).check(manifest);
			}

			@Override
			public Launch.Pin fetch(Manifest.Pin pin, Platform platform) throws IOException, BootstrapException
			{
				RuneLiteCache cache = new RuneLiteCache(dataDir.resolve(RUNELITE_DIR));
				Bootstrap bootstrap = cache.bootstrap(pin);
				return new Launch.Pin(cache.jars(pin, bootstrap, platform), bootstrap.clientJvmArguments(platform));
			}

			@Override
			public Process start(List<String> command, Path gameLog) throws IOException
			{
				Files.createDirectories(gameLog.getParent());
				return new ProcessBuilder(command)
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.appendTo(gameLog.toFile()))
					.start();
			}
		};
	}

	/**
	 * Runs the whole sequence and returns the exit code: the game's own once it ran, else one of the
	 * {@code EXIT_} codes above, with the reason logged and in the status file.
	 */
	public int run(LaunchOptions options, Manifest manifest, Platform platform, Map<String, String> env, Path userHome,
		Launch.Installed installed, PrintStream log)
	{
		Path dataDir = options.dataDir(platform, env, userHome);
		Reporter report = new Reporter(options.statusFile(), log);
		report.state(StatusFile.State.STARTING, "launcher " + version + " on " + platform.os() + "/" + platform.arch()
			+ ", data in " + dataDir);

		Manifest.Server server = options.launchServer(manifest.server);
		report.state(StatusFile.State.CHECKING, server.host() + ", game port " + server.gamePort() + ", HTTP port "
			+ server.httpPort());
		ServerCheck.Result result = steps.check(server, manifest);
		if (!(result instanceof ServerCheck.Result.Ready ready))
		{
			return report.fail(exitCode(result), reason(result));
		}
		Manifest.Pin pin = manifest.pin(ready.revision());
		if (pin == null)
		{
			// ServerCheck reports an unpinned revision itself; this only guards a Steps that doesn't.
			return report.fail(EXIT_UNKNOWN_REVISION, reason(new ServerCheck.Result.UnknownRevision(ready.revision())));
		}
		report.revision = ready.revision();

		report.state(StatusFile.State.DOWNLOADING, "RuneLite " + pin.runelite() + " for revision " + ready.revision()
			+ " (" + pin.commit().substring(0, 12) + ")");
		Launch.Pin fetched;
		try
		{
			fetched = steps.fetch(pin, platform);
		}
		catch (IOException | BootstrapException e)
		{
			return report.fail(EXIT_DOWNLOAD,
				"RuneLite " + pin.runelite() + " could not be fetched: " + e.getMessage());
		}

		report.state(StatusFile.State.LAUNCHING, fetched.gameJars().size() + " jars, "
			+ (options.developerMode() ? "developer mode" : "normal mode"));
		Path gameLog = dataDir.resolve(LOGS_DIR).resolve(GAME_LOG);
		Process game;
		try
		{
			Launch launch = Launch.plan(options, platform, env, userHome, server, installed, fetched);
			game = steps.start(launch.command(), gameLog);
		}
		catch (IOException e)
		{
			return report.fail(EXIT_START, "the game could not be started: " + e.getMessage());
		}
		report.state(StatusFile.State.RUNNING, "game process " + game.pid() + ", its log in " + gameLog, game.pid());

		int exit;
		try
		{
			exit = game.waitFor();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			game.destroy();
			return report.fail(EXIT_START, "interrupted while the game ran");
		}
		report.state(StatusFile.State.EXITED, "the game exited with " + exit);
		return exit;
	}

	/** The launcher's exit code for a check that didn't say Ready. */
	static int exitCode(ServerCheck.Result result)
	{
		return switch (result)
		{
			case ServerCheck.Result.Ready ready -> 0;
			case ServerCheck.Result.Offline offline -> EXIT_OFFLINE;
			case ServerCheck.Result.Full full -> EXIT_FULL;
			case ServerCheck.Result.NoInternet noInternet -> EXIT_NO_INTERNET;
			case ServerCheck.Result.UnknownRevision unknown -> EXIT_UNKNOWN_REVISION;
			case ServerCheck.Result.KeyChanged changed -> EXIT_KEY_CHANGED;
		};
	}

	/** The check's outcome in a line for the log and the status file. */
	static String reason(ServerCheck.Result result)
	{
		return switch (result)
		{
			case ServerCheck.Result.Ready ready -> "the server is up at revision " + ready.revision() + " with "
				+ ready.players() + " player(s)";
			case ServerCheck.Result.Offline offline -> "the server is offline: " + offline.detail();
			case ServerCheck.Result.Full full -> "the server is full (" + full.players() + " players)";
			case ServerCheck.Result.NoInternet noInternet -> "no internet: neither the server nor the probe answers";
			case ServerCheck.Result.UnknownRevision unknown -> "the server runs revision " + unknown.revision()
				+ ", which this launcher has no pin for yet";
			case ServerCheck.Result.KeyChanged changed -> "the served key isn't the signed one";
		};
	}

	/** Each state to the log and, when asked for, the status file; a status file that can't be written never stops
	 * a launch, it is logged. */
	private final class Reporter
	{
		private final Path statusFile;
		private final PrintStream log;
		Integer revision;

		Reporter(Path statusFile, PrintStream log)
		{
			this.statusFile = statusFile;
			this.log = log;
		}

		void state(StatusFile.State state, String detail)
		{
			state(state, detail, null);
		}

		void state(StatusFile.State state, String detail, Long gamePid)
		{
			log.println("[launcher] " + state + ": " + detail);
			if (statusFile == null)
			{
				return;
			}
			try
			{
				StatusFile.write(statusFile,
					new StatusFile.Status(version, state, detail, revision, gamePid, clock.instant()));
			}
			catch (IOException e)
			{
				log.println("[launcher] the status file " + statusFile + " could not be written: " + e.getMessage());
			}
		}

		int fail(int exitCode, String why)
		{
			state(StatusFile.State.FAILED, why);
			return exitCode;
		}
	}
}
