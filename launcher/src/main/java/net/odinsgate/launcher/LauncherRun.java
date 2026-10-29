package net.odinsgate.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One run of the launcher, from the start to the game's exit, in the plan's order: check the server (plain data,
 * hints only), fetch the pinned RuneLite (verified on the way in and again before launch), plan the launch (the
 * runtime config written), start the game in its child JVM through {@link Loader}, and wait for it. A
 * {@link Session} holds one run's steps, each logged and, when {@code --status-file} was given, written to the
 * status file, with every stop's reason in both. {@link #run} takes the steps straight through with nobody at the
 * screen, which is what {@code --auto} and the test VM get; the window ({@link WindowRun}) takes them one at a
 * time, with Retry between a failed check and the next and Play before the launch. The parts that touch the
 * outside world sit behind {@link Steps}, so the tests run the whole sequence with stand-ins.
 */
public final class LauncherRun
{
	/** The outside world: our server, RuneLite's servers, GitHub's notice and the child process. */
	public interface Steps
	{
		ServerCheck.Result check(Manifest.Server server, Manifest manifest);

		/** The pin's jars for this system, hashed, and its bootstrap's JVM arguments. */
		Launch.Pin fetch(Manifest.Pin pin, Platform platform) throws IOException, BootstrapException;

		/** Starts the game's command line with its output going to {@code gameLog}. */
		Process start(List<String> command, Path gameLog) throws IOException;

		/** The notice for the window, plain text from GitHub, or nothing: never a reason to stop. */
		default Optional<String> notice()
		{
			return Optional.empty();
		}
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

	/**
	 * The notice file: a short text on main, read over HTTPS from GitHub, so a line reaches friends without a server
	 * deploy and while the server is down. The window shows it as plain text, never as HTML, capped in length.
	 */
	static final URI NOTICE =
		URI.create("https://raw.githubusercontent.com/SamSjosten/OSRSPS/main/launcher/notice.txt");
	static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(5);
	/** More than this is not a notice; the window cuts the text down further for the screen. */
	static final int NOTICE_MAX_BYTES = 4096;

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

			@Override
			public Optional<String> notice()
			{
				return fetchNotice(NOTICE);
			}
		};
	}

	/** The notice at {@code uri} when it answers 200 with a body within the limit; anything else is no notice. */
	static Optional<String> fetchNotice(URI uri)
	{
		try
		{
			HttpClient http = HttpClient.newBuilder().connectTimeout(NOTICE_TIMEOUT).build();
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(NOTICE_TIMEOUT).GET().build();
			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200 || response.body().length > NOTICE_MAX_BYTES)
			{
				return Optional.empty();
			}
			String text = new String(response.body(), StandardCharsets.UTF_8).strip();
			return text.isEmpty() ? Optional.empty() : Optional.of(text);
		}
		catch (IOException | RuntimeException e)
		{
			return Optional.empty();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	/** Begins a run: the data folder known, STARTING reported, nothing dialed yet. */
	public Session session(LaunchOptions options, Manifest manifest, Platform platform, Map<String, String> env,
		Path userHome, Launch.Installed installed, PrintStream log)
	{
		return new Session(options, manifest, platform, env, userHome, installed, log);
	}

	/**
	 * Runs the whole sequence with nobody at the screen and returns the exit code: the game's own once it ran, else
	 * one of the {@code EXIT_} codes above, with the reason logged and in the status file.
	 */
	public int run(LaunchOptions options, Manifest manifest, Platform platform, Map<String, String> env, Path userHome,
		Launch.Installed installed, PrintStream log)
	{
		return run(session(options, manifest, platform, env, userHome, installed, log));
	}

	/** The steps of {@code session} straight through, as {@link #run} above. */
	public int run(Session session)
	{
		ServerCheck.Result result = session.check();
		if (!(result instanceof ServerCheck.Result.Ready))
		{
			return exitCode(result);
		}
		Launch.Pin fetched;
		try
		{
			fetched = session.fetch();
		}
		catch (IOException | BootstrapException e)
		{
			return EXIT_DOWNLOAD;
		}
		Process game;
		try
		{
			game = session.start(fetched);
		}
		catch (IOException e)
		{
			return EXIT_START;
		}
		return session.waitFor(game);
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

	/**
	 * One run's steps, in order: {@link #check}, then {@link #fetch} once the check said Ready, {@link #start} with
	 * what was fetched, and {@link #waitFor} the game. A step that stops the run reports FAILED with its reason; a
	 * check may be repeated after a failure (the window's Retry), and the game started again after its exit (Play).
	 * Each step blocks for as long as the outside world takes, so the window calls them off its event thread.
	 */
	public final class Session
	{
		private final LaunchOptions options;
		private final Manifest manifest;
		private final Platform platform;
		private final Map<String, String> env;
		private final Path userHome;
		private final Launch.Installed installed;
		private final Path dataDir;
		private final Manifest.Server server;
		private final Reporter report;
		private volatile Manifest.Pin pin;

		private Session(LaunchOptions options, Manifest manifest, Platform platform, Map<String, String> env,
			Path userHome, Launch.Installed installed, PrintStream log)
		{
			this.options = options;
			this.manifest = manifest;
			this.platform = platform;
			this.env = env;
			this.userHome = userHome;
			this.installed = installed;
			this.dataDir = options.dataDir(platform, env, userHome);
			this.server = options.launchServer(manifest.server);
			this.report = new Reporter(options.statusFile(), log);
			report.state(StatusFile.State.STARTING, "launcher " + version + " on " + platform.os() + "/"
				+ platform.arch() + ", data in " + dataDir);
		}

		/** The launcher's data folder for this run. */
		public Path dataDir()
		{
			return dataDir;
		}

		/** The server this run dials. */
		public Manifest.Server server()
		{
			return server;
		}

		/** True until the game has run once from this data folder: no game home exists yet. */
		public boolean firstRun()
		{
			return !Files.isDirectory(dataDir.resolve(Launch.GAME_HOME));
		}

		/** Checks the server; anything but Ready is reported as the run's failure, and may be retried. */
		public ServerCheck.Result check()
		{
			report.state(StatusFile.State.CHECKING, server.host() + ", game port " + server.gamePort()
				+ ", HTTP port " + server.httpPort());
			ServerCheck.Result result = steps.check(server, manifest);
			if (!(result instanceof ServerCheck.Result.Ready ready))
			{
				if (result instanceof ServerCheck.Result.UnknownRevision unknown)
				{
					// The revision is known even though it stops the launch: the status file says which one.
					report.revision = unknown.revision();
				}
				report.fail(reason(result));
				return result;
			}
			report.revision = ready.revision();
			pin = manifest.pin(ready.revision());
			if (pin == null)
			{
				// ServerCheck reports an unpinned revision itself; this only guards a Steps that doesn't.
				ServerCheck.Result unknown = new ServerCheck.Result.UnknownRevision(ready.revision());
				report.fail(reason(unknown));
				return unknown;
			}
			return result;
		}

		/** Fetches the pin the check found; a failure is reported and rethrown. */
		public Launch.Pin fetch() throws IOException, BootstrapException
		{
			Manifest.Pin pin = this.pin;
			if (pin == null)
			{
				throw new IllegalStateException("fetch before a check that said Ready");
			}
			report.state(StatusFile.State.DOWNLOADING, "RuneLite " + pin.runelite() + " for revision "
				+ report.revision + " (" + pin.commit().substring(0, 12) + ")");
			try
			{
				return steps.fetch(pin, platform);
			}
			catch (IOException | BootstrapException e)
			{
				report.fail("RuneLite " + pin.runelite() + " could not be fetched: " + e.getMessage());
				throw e;
			}
		}

		/** Plans the launch and starts the game; a failure is reported and rethrown. */
		public Process start(Launch.Pin fetched) throws IOException
		{
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
				report.fail("the game could not be started: " + e.getMessage());
				throw e;
			}
			report.gamePid = game.pid();
			report.state(StatusFile.State.RUNNING, "game process " + game.pid() + ", its log in " + gameLog);
			return game;
		}

		/** Waits for the game and returns its exit code ({@link #EXIT_START} if interrupted). */
		public int waitFor(Process game)
		{
			int exit;
			try
			{
				exit = game.waitFor();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				game.destroy();
				report.fail("interrupted while the game ran");
				return EXIT_START;
			}
			report.state(StatusFile.State.EXITED, "the game exited with " + exit);
			return exit;
		}
	}

	/**
	 * Each state to the log and, when asked for, the status file. The revision and the game's PID stay in every
	 * state after the one that learned them, so a reader that missed a short-lived state still sees them; a status
	 * file that can't be written never stops a launch, it is logged.
	 */
	private final class Reporter
	{
		private final Path statusFile;
		private final PrintStream log;
		volatile Integer revision;
		volatile Long gamePid;

		Reporter(Path statusFile, PrintStream log)
		{
			this.statusFile = statusFile;
			this.log = log;
		}

		void state(StatusFile.State state, String detail)
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

		void fail(String why)
		{
			state(StatusFile.State.FAILED, why);
		}
	}
}
