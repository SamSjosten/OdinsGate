package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The headless run, start to the game's exit, with the outside world (the server check, RuneLite's download, the
 * child process) stood in by fakes: the states in order, each stop's exit code and reason, and what reaches the
 * game's command line.
 */
public class LauncherRunTest
{
	private static final Instant AT = Instant.parse("2026-10-29T17:00:00Z");
	private static final Platform LINUX = new Platform(Platform.Os.LINUX, Platform.Arch.X64);
	private static final Launch.Installed INSTALLED = new Launch.Installed(Path.of("runtime"),
		List.of(Path.of("lib", "odinsgate-launcher-0.1.0.jar")));

	/** Records what the run asked of the outside world and answers as told. */
	static final class FakeSteps implements LauncherRun.Steps
	{
		ServerCheck.Result result = new ServerCheck.Result.Ready(241, 3);
		Launch.Pin pin = new Launch.Pin(
			List.of(Path.of("cache", "injected-client-1.13.4.jar"), Path.of("cache", "client-1.13.4.jar")),
			List.of("-Xmx512m"));
		Exception fetchFailure;
		IOException startFailure;
		int gameExit;
		/** What the status page says to the watch while the game runs; null is a server that doesn't answer. */
		ServerCheck.Status status;
		int watched;

		Manifest.Server checked;
		Manifest checkedManifest;
		Manifest.Pin fetched;
		List<String> command;
		Path gameLog;
		FakeProcess game;

		@Override
		public ServerCheck.Result check(Manifest.Server server, Manifest manifest)
		{
			checked = server;
			checkedManifest = manifest;
			return result;
		}

		@Override
		public Launch.Pin fetch(Manifest.Pin pin, Platform platform) throws IOException, BootstrapException
		{
			fetched = pin;
			if (fetchFailure instanceof BootstrapException e)
			{
				throw e;
			}
			if (fetchFailure instanceof IOException e)
			{
				throw e;
			}
			return this.pin;
		}

		@Override
		public Optional<ServerCheck.Status> status(Manifest.Server server)
		{
			watched++;
			return Optional.ofNullable(status);
		}

		@Override
		public Process start(List<String> command, Path gameLog) throws IOException
		{
			this.command = command;
			this.gameLog = gameLog;
			if (startFailure != null)
			{
				throw startFailure;
			}
			game = new FakeProcess(gameExit);
			return game;
		}
	}

	/** A child process that has already exited with the given code. */
	static final class FakeProcess extends Process
	{
		private final int exitCode;
		boolean destroyed;

		FakeProcess(int exitCode)
		{
			this.exitCode = exitCode;
		}

		@Override
		public OutputStream getOutputStream()
		{
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor()
		{
			return exitCode;
		}

		@Override
		public int exitValue()
		{
			return exitCode;
		}

		@Override
		public void destroy()
		{
			destroyed = true;
		}

		@Override
		public long pid()
		{
			return 4321L;
		}
	}

	@TempDir
	Path dir;

	private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
	private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
	private Manifest manifest;
	private Path home;
	private Path statusFile;

	@BeforeEach
	public void aManifestAndAHome() throws Exception
	{
		manifest = Manifest.parse(ManifestTest.good());
		home = dir.resolve("data");
		statusFile = dir.resolve("status.json");
	}

	private int run(LaunchOptions options, FakeSteps steps)
	{
		LauncherRun run = new LauncherRun("0.1.0", steps, Clock.fixed(AT, ZoneOffset.UTC));
		return run.run(options, manifest, LINUX, Map.of(), dir.resolve("user-home"), INSTALLED, log);
	}

	private LaunchOptions options(String... more)
	{
		List<String> args = new java.util.ArrayList<>(List.of("--home", home.toString(), "--status-file",
			statusFile.toString()));
		args.addAll(List.of(more));
		return LaunchOptions.parse(args);
	}

	private List<String> states()
	{
		return logBytes.toString(StandardCharsets.UTF_8).lines()
			.filter(line -> line.startsWith("[launcher] "))
			.map(line -> line.substring("[launcher] ".length(), line.indexOf(':')))
			.toList();
	}

	@Test
	public void aReadyServerRunsTheGameToItsExit() throws Exception
	{
		FakeSteps steps = new FakeSteps();
		assertEquals(0, run(options(), steps));
		assertEquals(List.of("STARTING", "CHECKING", "DOWNLOADING", "LAUNCHING", "RUNNING", "EXITED"), states());

		assertEquals(manifest.server, steps.checked, "the signed server is the one checked");
		assertEquals(manifest.pin(241), steps.fetched, "the pin for the revision the server reported");
		assertEquals(home.resolve("logs").resolve("game.log"), steps.gameLog);
		assertEquals(RuntimeConfig.CONTENT, Files.readString(home.resolve("rtconf.json")),
			"the runtime config was written before the game started");

		List<String> command = steps.command;
		assertEquals(LINUX.javaExecutable(Path.of("runtime")).toString(), command.get(0));
		assertTrue(command.contains(Loader.class.getName()), command.toString());
		assertEquals("43594", command.get(command.indexOf("--port") + 1));
		assertEquals(ManifestTest.MODULUS, command.get(command.indexOf("--modulus") + 1));
		assertTrue(command.contains("-Duser.home=" + home.resolve("home")), command.toString());

		StatusFile.Status status = StatusFile.read(statusFile);
		assertEquals(StatusFile.State.EXITED, status.state());
		assertEquals("0.1.0", status.version());
		assertEquals(241, status.revision());
		assertEquals(4321L, status.gamePid(), "the PID of the game that ran stays in the status after its exit");
		assertEquals(AT, status.at());
		assertTrue(logBytes.toString(StandardCharsets.UTF_8).contains("RUNNING: game process 4321"),
			logBytes.toString(StandardCharsets.UTF_8));
	}

	@Test
	public void theGamesExitCodeBecomesTheLaunchers() throws Exception
	{
		FakeSteps steps = new FakeSteps();
		steps.gameExit = 7;
		assertEquals(7, run(options(), steps));
		StatusFile.Status status = StatusFile.read(statusFile);
		assertEquals(StatusFile.State.EXITED, status.state());
		assertTrue(status.detail().contains("7"), status.detail());
	}

	@Test
	public void eachCheckOutcomeStopsWithItsOwnExitCode() throws Exception
	{
		Map<ServerCheck.Result, Integer> codes = Map.of(
			new ServerCheck.Result.Offline("the game port doesn't answer"), LauncherRun.EXIT_OFFLINE,
			new ServerCheck.Result.Full(241, 50), LauncherRun.EXIT_FULL,
			new ServerCheck.Result.NoInternet(), LauncherRun.EXIT_NO_INTERNET,
			new ServerCheck.Result.UnknownRevision(242), LauncherRun.EXIT_UNKNOWN_REVISION,
			new ServerCheck.Result.KeyChanged(), LauncherRun.EXIT_KEY_CHANGED);
		for (Map.Entry<ServerCheck.Result, Integer> entry : codes.entrySet())
		{
			FakeSteps steps = new FakeSteps();
			steps.result = entry.getKey();
			assertEquals(entry.getValue(), run(options(), steps), entry.getKey().toString());
			assertNull(steps.fetched, "nothing is fetched after " + entry.getKey());
			assertNull(steps.command, "nothing is started after " + entry.getKey());
			StatusFile.Status status = StatusFile.read(statusFile);
			assertEquals(StatusFile.State.FAILED, status.state(), entry.getKey().toString());
			assertEquals(LauncherRun.reason(entry.getKey()), status.detail());
			// The outcomes that know the revision record it, so a script can tell which pin is missing.
			Integer revision = switch (entry.getKey())
			{
				case ServerCheck.Result.UnknownRevision u -> u.revision();
				case ServerCheck.Result.Full f -> f.revision();
				default -> null;
			};
			assertEquals(revision, status.revision(), entry.getKey().toString());
			assertNull(status.gamePid(), entry.getKey().toString());
		}
		assertTrue(LauncherRun.reason(new ServerCheck.Result.Offline("x")).contains("offline"));
		assertTrue(LauncherRun.reason(new ServerCheck.Result.UnknownRevision(242)).contains("242"));
	}

	@Test
	public void aRevisionTheManifestDoesNotPinIsStoppedEvenWhenTheCheckSaidReady() throws Exception
	{
		FakeSteps steps = new FakeSteps();
		steps.result = new ServerCheck.Result.Ready(242, 1);
		assertEquals(LauncherRun.EXIT_UNKNOWN_REVISION, run(options(), steps));
		assertNull(steps.fetched);
		StatusFile.Status status = StatusFile.read(statusFile);
		assertTrue(status.detail().contains("242"));
		assertEquals(242, status.revision());
	}

	@Test
	public void aDownloadFailureStopsBeforeTheLaunch() throws Exception
	{
		FakeSteps steps = new FakeSteps();
		steps.fetchFailure = new BootstrapException("the bootstrap for abc doesn't carry the pinned SHA-256");
		assertEquals(LauncherRun.EXIT_DOWNLOAD, run(options(), steps));
		assertNull(steps.command, "the game was not started");
		StatusFile.Status status = StatusFile.read(statusFile);
		assertEquals(StatusFile.State.FAILED, status.state());
		assertTrue(status.detail().contains("pinned SHA-256"), status.detail());
		assertEquals(241, status.revision(), "the revision is known by then");

		steps = new FakeSteps();
		steps.fetchFailure = new IOException("raw.githubusercontent.com answered 503");
		assertEquals(LauncherRun.EXIT_DOWNLOAD, run(options(), steps));
		assertTrue(StatusFile.read(statusFile).detail().contains("503"));
	}

	@Test
	public void aGameThatCannotStartIsReported() throws Exception
	{
		FakeSteps steps = new FakeSteps();
		steps.startFailure = new IOException("no java at runtime/bin/java");
		assertEquals(LauncherRun.EXIT_START, run(options(), steps));
		StatusFile.Status status = StatusFile.read(statusFile);
		assertEquals(StatusFile.State.FAILED, status.state());
		assertTrue(status.detail().contains("runtime/bin/java"), status.detail());
		assertEquals(List.of("STARTING", "CHECKING", "DOWNLOADING", "LAUNCHING", "FAILED"), states());
	}

	@Test
	public void theStagingServerIsCheckedAndDialedWithTheSignedKey()
	{
		FakeSteps steps = new FakeSteps();
		assertEquals(0, run(options("--server", "staging.odinsgate.net:44594", "--dev"), steps));
		assertEquals(new Manifest.Server("staging.odinsgate.net", 44594, 44595, ManifestTest.MODULUS), steps.checked);
		assertEquals("44594", steps.command.get(steps.command.indexOf("--port") + 1));
		assertTrue(steps.command.containsAll(List.of("staging.odinsgate.net:44594", "staging.odinsgate.net:44595")),
			"the game may reach the staging server, not the signed one: " + steps.command);
		assertFalse(steps.command.contains("odinsgate.net:43594"), steps.command.toString());
		assertTrue(steps.command.contains("--developer-mode"), steps.command.toString());
		assertTrue(logBytes.toString(StandardCharsets.UTF_8).contains("developer mode"));
	}

	@Test
	public void aLargeGameLogIsMovedAsideBeforeTheGameStarts() throws Exception
	{
		Path gameLog = home.resolve("logs").resolve("game.log");
		Files.createDirectories(gameLog.getParent());
		Files.writeString(gameLog, "old run\n".repeat((int) (RotatingLog.MAX_BYTES / 8) + 1));
		FakeSteps steps = new FakeSteps();
		assertEquals(0, run(options(), steps));
		assertEquals(gameLog, steps.gameLog, "the game still logs to the same file");
		assertFalse(Files.exists(gameLog), "the old log was moved aside, the game starts a fresh one");
		assertTrue(Files.readString(RotatingLog.aside(gameLog, 1)).startsWith("old run"));

		Files.writeString(gameLog, "small\n");
		assertEquals(0, run(options(), new FakeSteps()));
		assertEquals("small\n", Files.readString(gameLog), "a small log stays");
	}

	@Test
	public void withoutAStatusFileOnlyTheLogSeesTheStates() throws Exception
	{
		FakeSteps steps = new FakeSteps();
		assertEquals(0, run(LaunchOptions.parse(List.of("--home", home.toString())), steps));
		assertEquals(List.of("STARTING", "CHECKING", "DOWNLOADING", "LAUNCHING", "RUNNING", "EXITED"), states());
		assertFalse(Files.exists(statusFile));
		try (var files = Files.walk(dir))
		{
			assertTrue(files.noneMatch(f -> f.getFileName().toString().equals("status.json")));
		}
	}
}
