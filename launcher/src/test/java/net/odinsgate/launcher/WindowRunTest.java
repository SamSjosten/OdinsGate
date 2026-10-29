package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window's driver over a real {@link LauncherRun.Session} with the outside world faked: the worker is cranked
 * by hand, the window's thread is this one, and every view drawn is kept. What the screen asks for reaches the
 * session's steps, what they answer reaches the screen, and the timers of a failed check come and go.
 */
public class WindowRunTest
{
	private static final Instant AT = Instant.parse("2026-10-29T19:00:00Z");
	private static final Platform LINUX = new Platform(Platform.Os.LINUX, Platform.Arch.X64);
	private static final Launch.Installed INSTALLED = new Launch.Installed(Path.of("runtime"),
		List.of(Path.of("lib", "odinsgate-launcher-0.1.0.jar")));

	/** Keeps the tasks and timers until the test runs or fires them. */
	static final class FakeWorker implements WindowRun.Worker
	{
		static final class Timer implements WindowRun.Cancellable
		{
			final Duration wait;
			final Runnable task;
			final boolean repeating;
			boolean cancelled;

			Timer(Duration wait, Runnable task, boolean repeating)
			{
				this.wait = wait;
				this.task = task;
				this.repeating = repeating;
			}

			@Override
			public void cancel()
			{
				cancelled = true;
			}
		}

		final Deque<Runnable> tasks = new ArrayDeque<>();
		final List<Timer> timers = new ArrayList<>();

		@Override
		public void run(Runnable task)
		{
			tasks.add(task);
		}

		@Override
		public WindowRun.Cancellable after(Duration wait, Runnable task)
		{
			Timer timer = new Timer(wait, task, false);
			timers.add(timer);
			return timer;
		}

		@Override
		public WindowRun.Cancellable every(Duration period, Runnable task)
		{
			Timer timer = new Timer(period, task, true);
			timers.add(timer);
			return timer;
		}

		/** Runs every queued task, in order, including the ones they queue. */
		void drain()
		{
			while (!tasks.isEmpty())
			{
				tasks.poll().run();
			}
		}

		/** Runs the next queued task only. */
		void runOne()
		{
			assertFalse(tasks.isEmpty(), "a task is queued");
			tasks.poll().run();
		}

		/** The one live timer of this kind. */
		Timer live(boolean repeating)
		{
			List<Timer> live = timers.stream().filter(t -> t.repeating == repeating && !t.cancelled).toList();
			assertEquals(1, live.size(), (repeating ? "repeating" : "one-shot") + " timers: " + live.size());
			return live.get(0);
		}

		boolean noneLive()
		{
			return timers.stream().allMatch(t -> t.cancelled);
		}
	}

	/** A clock the test moves. */
	static final class TestClock extends Clock
	{
		Instant now = AT;

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return now;
		}
	}

	@TempDir
	Path dir;

	private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
	private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
	private final FakeWorker worker = new FakeWorker();
	private final TestClock clock = new TestClock();
	private final List<Screen.View> views = new ArrayList<>();
	private final List<String> clipboard = new ArrayList<>();
	/** How many times the clipboard refuses before it takes the report. */
	private int clipboardBusy;
	private static final Map<String, String> SYSTEM = Map.of("os.name", "TestOS", "os.version", "1.0",
		"java.version", "21.0.1", "java.vendor", "Testers");
	private Manifest manifest;
	private Path home;

	@BeforeEach
	public void aManifestAndAHome() throws Exception
	{
		manifest = Manifest.parse(ManifestTest.good());
		home = dir.resolve("data");
	}

	private WindowRun windowRun(LauncherRun.Steps steps, boolean auto, Supplier<Optional<String>> notice)
	{
		LauncherRun run = new LauncherRun("0.1.0", steps, clock);
		LaunchOptions options = LaunchOptions.parse(List.of("--home", home.toString()));
		LauncherRun.Session session =
			run.session(options, manifest, LINUX, Map.of(), dir.resolve("user-home"), INSTALLED, log);
		return new WindowRun(new Screen(auto, session.firstRun()), session, notice, worker, Runnable::run, views::add,
			clock, this::toClipboard, SYSTEM);
	}

	private void toClipboard(String text)
	{
		if (clipboardBusy > 0)
		{
			clipboardBusy--;
			throw new IllegalStateException("cannot open system clipboard");
		}
		clipboard.add(text);
	}

	/** The good manifest with a second pin, for a server that moves to a revision the launcher already knows. */
	private static Manifest twoPins() throws Exception
	{
		String second = "{ \"revision\": 242, \"runelite\": \"1.13.5\", \"commit\": \"" + ManifestTest.COMMIT
			+ "\", \"bootstrapSha256\": \"" + ManifestTest.SHA256 + "\" }, {";
		return Manifest.parse(ManifestTest.good().replace("\"pins\": [ {", "\"pins\": [ " + second));
	}

	/** Up to the game running, with the wait on it still queued on the worker. */
	private WindowRun runningGame(LauncherRunTest.FakeSteps steps)
	{
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		assertEquals(Screen.Phase.READY, last().phase());
		run.play();
		worker.runOne();
		assertEquals(Screen.Phase.RUNNING, last().phase());
		assertEquals(1, worker.tasks.size(), "the wait on the game is queued, nothing else");
		return run;
	}

	private WindowRun windowRun(LauncherRun.Steps steps)
	{
		return windowRun(steps, false, Optional::empty);
	}

	private Screen.View last()
	{
		return views.get(views.size() - 1);
	}

	private List<Screen.Phase> phases()
	{
		return views.stream().map(Screen.View::phase).distinct().toList();
	}

	private List<String> states()
	{
		return logBytes.toString(StandardCharsets.UTF_8).lines()
			.filter(line -> line.startsWith("[launcher] "))
			.map(line -> line.substring("[launcher] ".length(), line.indexOf(':')))
			.toList();
	}

	@Test
	public void theStepsRunOffTheWindowsThreadAndTheirAnswersComeBackToIt()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		WindowRun run = windowRun(steps, false, () -> Optional.of("Back up at nine."));
		run.start();
		assertEquals(Screen.Phase.CHECKING, last().phase());
		assertTrue(last().firstRun(), "no game home yet");
		assertNull(steps.checked, "the check waits for the worker");
		assertEquals(2, worker.tasks.size(), "the notice and the check");

		worker.drain();
		assertEquals(manifest.server, steps.checked);
		assertEquals("Back up at nine.", last().notice());
		assertEquals(Screen.Phase.READY, last().phase(), "checked, fetched, and waiting for Play");
		assertEquals(manifest.pin(241), steps.fetched);
		assertNull(steps.command, "nothing starts before Play");
		assertEquals(List.of("STARTING", "CHECKING", "DOWNLOADING"), states());

		run.play();
		assertEquals(Screen.Phase.LAUNCHING, last().phase());
		assertNull(steps.command, "the launch waits for the worker");
		worker.drain();
		assertEquals(List.of(Screen.Phase.CHECKING, Screen.Phase.DOWNLOADING, Screen.Phase.READY,
			Screen.Phase.LAUNCHING, Screen.Phase.RUNNING, Screen.Phase.EXITED), phases());
		assertEquals(List.of("STARTING", "CHECKING", "DOWNLOADING", "LAUNCHING", "RUNNING", "EXITED"), states());
		assertTrue(steps.command.contains(Loader.class.getName()), steps.command.toString());
		assertEquals("The game closed.", last().status());
		assertTrue(worker.noneLive());
	}

	@Test
	public void playAfterTheGameClosedChecksAgainAndStartsTheGameAgain()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		run.play();
		worker.drain();
		assertEquals(Screen.Phase.EXITED, last().phase());
		LauncherRunTest.FakeProcess first = steps.game;

		views.clear();
		run.play();
		assertEquals(Screen.Phase.CHECKING, last().phase());
		worker.drain();
		assertEquals(List.of(Screen.Phase.CHECKING, Screen.Phase.DOWNLOADING, Screen.Phase.LAUNCHING,
			Screen.Phase.RUNNING, Screen.Phase.EXITED), phases(), "no second click on Play");
		assertTrue(steps.game != first, "a new game process");
	}

	@Test
	public void aDeadPortSchedulesTheRetryAndTheCountdown()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		steps.result = new ServerCheck.Result.Offline("the game port doesn't answer");
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		assertEquals(Screen.Phase.WAITING, last().phase());
		assertEquals("Trying again in 30 seconds.", last().detail());
		FakeWorker.Timer retry = worker.live(false);
		FakeWorker.Timer ticks = worker.live(true);
		assertEquals(Screen.RETRY_WAIT, retry.wait);
		assertEquals(WindowRun.TICK, ticks.wait);

		clock.now = AT.plusSeconds(12);
		ticks.task.run();
		assertEquals("Trying again in 18 seconds.", last().detail());
		assertEquals(Screen.Phase.WAITING, last().phase());

		clock.now = AT.plusSeconds(30);
		steps.result = new ServerCheck.Result.Ready(241, 3);
		retry.task.run();
		assertEquals(Screen.Phase.CHECKING, last().phase());
		assertTrue(retry.cancelled && ticks.cancelled, "both timers are called off when the check starts");
		worker.drain();
		assertEquals(Screen.Phase.READY, last().phase());
		assertTrue(worker.noneLive());
	}

	@Test
	public void theRetryButtonCallsOffTheScheduledRetry()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		steps.result = new ServerCheck.Result.Full(241, 50);
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		assertEquals("The server is full (50 players).", last().status());
		FakeWorker.Timer retry = worker.live(false);
		FakeWorker.Timer ticks = worker.live(true);

		run.retry();
		assertEquals(Screen.Phase.CHECKING, last().phase());
		assertTrue(retry.cancelled && ticks.cancelled);
		assertEquals(1, worker.tasks.size(), "one check queued");
		retry.task.run();
		assertEquals(1, worker.tasks.size(), "a cancelled timer that fires anyway starts no second check");

		worker.drain();
		assertEquals(Screen.Phase.WAITING, last().phase(), "still full");
		assertEquals(2, worker.timers.stream().filter(t -> !t.cancelled).count(), "a fresh pair of timers");
	}

	@Test
	public void aFailedFetchShowsItsReasonAndRetryStartsOver()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		steps.fetchFailure = new IOException("raw.githubusercontent.com answered 503");
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		assertEquals(Screen.Phase.STOPPED, last().phase());
		assertEquals("The game client could not be downloaded.", last().status());
		assertEquals("raw.githubusercontent.com answered 503", last().detail());
		assertTrue(last().canRetry());
		assertTrue(logBytes.toString(StandardCharsets.UTF_8).contains("FAILED"));

		steps.fetchFailure = null;
		run.retry();
		worker.drain();
		assertEquals(Screen.Phase.READY, last().phase());
	}

	@Test
	public void aGameThatCannotStartShowsItsReason()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		steps.startFailure = new IOException("no java at runtime/bin/java");
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		run.play();
		worker.drain();
		assertEquals(Screen.Phase.STOPPED, last().phase());
		assertEquals("The game could not be started.", last().status());
		assertEquals("no java at runtime/bin/java", last().detail());
		assertEquals(List.of("STARTING", "CHECKING", "DOWNLOADING", "LAUNCHING", "FAILED"), states());
	}

	@Test
	public void aStepThatThrowsStopsTheScreenInsteadOfLeavingItBusy()
	{
		LauncherRun.Steps steps = new LauncherRun.Steps()
		{
			@Override
			public ServerCheck.Result check(Manifest.Server server, Manifest manifest)
			{
				throw new IllegalStateException("the probe list is empty");
			}

			@Override
			public Launch.Pin fetch(Manifest.Pin pin, Platform platform)
			{
				return fail("never fetched");
			}

			@Override
			public Process start(List<String> command, Path gameLog)
			{
				return fail("never started");
			}
		};
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		assertEquals(Screen.Phase.STOPPED, last().phase());
		assertEquals("The launcher ran into a problem.", last().status());
		assertEquals("the probe list is empty", last().detail());
		assertFalse(last().busy());
		assertTrue(last().canRetry());
		assertEquals(List.of("STARTING", "CHECKING", "FAILED"), states(), "the log and status file stop too");
		assertTrue(logBytes.toString(StandardCharsets.UTF_8).contains("FAILED: unexpected: the probe list is empty"),
			logBytes.toString(StandardCharsets.UTF_8));
	}

	@Test
	public void theServerIsWatchedWhileTheGameRunsAndAMovedRevisionRestartsIt() throws Exception
	{
		manifest = twoPins();
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		steps.status = new ServerCheck.Status(true, true, 241, 3);
		WindowRun run = runningGame(steps);
		FakeWorker.Timer watch = worker.live(true);
		assertEquals(Screen.WATCH_PERIOD, watch.wait);
		LauncherRunTest.FakeProcess first = steps.game;

		watch.task.run();
		assertEquals(1, steps.watched);
		assertEquals("The game is running.", last().status(), "the same revision changes nothing");
		steps.status = null;
		watch.task.run();
		assertEquals("The game is running.", last().status(), "a server that didn't answer changes nothing");
		steps.status = new ServerCheck.Status(true, true, 242, 2);
		watch.task.run();
		assertEquals(Screen.UPDATED_LINE, last().status());
		assertEquals(Screen.RESTART_LINE, last().detail());
		assertTrue(last().canPlay());
		String log = logBytes.toString(StandardCharsets.UTF_8);
		assertTrue(log.contains("watch: the server is up at revision 242 with 2 player(s), not the 241 the game runs"),
			log);
		assertFalse(log.contains("revision 241 with 3 player(s)"), "a look that found nothing new isn't logged");

		views.clear();
		run.play();
		assertTrue(first.destroyed, "the running game is closed");
		assertEquals("Closing the game…", last().status());
		assertFalse(watch.cancelled, "the watch runs until the game is gone");

		steps.result = new ServerCheck.Result.Ready(242, 2);
		worker.drain();
		assertEquals(List.of(Screen.Phase.RUNNING, Screen.Phase.CHECKING, Screen.Phase.DOWNLOADING,
			Screen.Phase.LAUNCHING, Screen.Phase.EXITED), phases(), "closing, then the launch over, without a click");
		assertEquals(manifest.pin(242), steps.fetched, "the new revision's pin");
		assertTrue(steps.game != first, "a new game process");
		assertTrue(worker.noneLive(), "the watch is called off once the game is gone");
	}

	@Test
	public void aRestartOntoAFullServerReportsTheServersRevision() throws Exception
	{
		manifest = twoPins();
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		steps.status = new ServerCheck.Status(true, true, 242, 2);
		WindowRun run = runningGame(steps);
		worker.live(true).task.run();
		assertEquals(Screen.UPDATED_LINE, last().status());
		run.play();
		steps.result = new ServerCheck.Result.Full(242, 50);
		worker.drain();
		assertEquals(Screen.Phase.WAITING, last().phase());
		assertEquals("The server is full (50 players).", last().status());

		run.copyReport(copied -> {});
		worker.drain();
		String report = clipboard.get(0);
		assertTrue(report.lines().anyMatch(line -> line.startsWith("revision 242, pin RuneLite 1.13.5 at ")), report);
		assertTrue(report.lines().noneMatch(line -> line.startsWith("revision 241")),
			"the header names the server's revision, not the last game's (which the steps below still mention)");
	}

	@Test
	public void aMovedRevisionWithoutAPinStopsAfterTheRestart()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		steps.status = new ServerCheck.Status(true, true, 242, 2);
		WindowRun run = runningGame(steps);
		worker.live(true).task.run();
		assertEquals(Screen.UPDATED_LINE, last().status());
		run.play();
		steps.result = new ServerCheck.Result.Ready(242, 2);
		worker.drain();
		assertEquals(Screen.Phase.STOPPED, last().phase());
		assertEquals("The server was updated.", last().status());
		assertTrue(last().detail().contains("242"), last().detail());
		assertTrue(worker.noneLive());

		run.copyReport(copied -> {});
		worker.drain();
		String report = clipboard.get(0);
		assertTrue(report.contains("revision 242\n"), report);
		assertFalse(report.contains("pin RuneLite"), "the old revision's pin isn't shown next to the new revision");
	}

	@Test
	public void theProblemReportGoesToTheClipboardWithoutAPassword() throws Exception
	{
		Path gameLog = home.resolve("logs").resolve("game.log");
		Files.createDirectories(gameLog.getParent());
		Files.writeString(gameLog, "[loader] 31 jars\n-Dpassword=hunter2 was on the line\nthe game drew a frame\n");
		Path clientLog = home.resolve("home").resolve(".runelite").resolve("logs").resolve("client.log");
		Files.createDirectories(clientLog.getParent());
		Files.writeString(clientLog, "logged in as test\nAuthorization: Bearer abc123\nworld 301\n");
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();
		run.play();
		worker.runOne();
		worker.live(true).task.run();

		Boolean[] done = new Boolean[1];
		run.copyReport(copied -> done[0] = copied);
		assertTrue(clipboard.isEmpty(), "the report is built on the worker");
		worker.drain();
		assertEquals(Boolean.TRUE, done[0]);
		assertEquals(1, clipboard.size());
		String report = clipboard.get(0);
		assertTrue(report.startsWith(Names.APP_NAME + " problem report, " + AT), report);
		assertTrue(report.contains("launcher " + Main.VERSION), report);
		assertTrue(report.contains("system linux/x64, TestOS 1.0, java 21.0.1 (Testers)"), report);
		assertTrue(report.contains("revision 241, pin RuneLite 1.13.4 at " + ManifestTest.COMMIT), report);
		assertTrue(report.contains(" CHECKING: "), report);
		assertTrue(report.contains(" DOWNLOADING: "), report);
		assertTrue(report.contains(" watch: the server didn't answer\n"), "the look at the server is in it too");
		assertTrue(report.indexOf(" DOWNLOADING: ") < report.indexOf(" watch: "), "steps first, then the looks");
		assertTrue(report.contains("the game drew a frame"), report);
		assertTrue(report.contains("logged in as test"), report);
		assertTrue(report.contains("world 301"), report);
		assertFalse(report.contains("hunter2"), report);
		assertFalse(report.contains("abc123"), report);
		assertFalse(report.contains("Bearer"), report);
		assertTrue(report.contains(ProblemReport.LEFT_OUT), report);
		assertFalse(report.contains(home.toString()), "no folder of the friend's in the report");
		assertTrue(report.contains("data in <data>"), report);
	}

	/** Fires every live one-shot timer, oldest first, and runs what it queues. */
	private void fireTimers()
	{
		for (FakeWorker.Timer timer : new ArrayList<>(worker.timers))
		{
			if (!timer.repeating && !timer.cancelled)
			{
				timer.cancelled = true;
				timer.task.run();
				worker.drain();
			}
		}
	}

	@Test
	public void aClipboardAnotherProgramHoldsIsTriedAgainAMomentLater()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		WindowRun run = windowRun(steps);
		run.start();
		worker.drain();

		clipboardBusy = 2;
		Boolean[] done = new Boolean[1];
		run.copyReport(copied -> done[0] = copied);
		worker.drain();
		assertNull(done[0], "not given up on, not done either");
		assertTrue(clipboard.isEmpty());
		assertEquals(WindowRun.CLIPBOARD_RETRY, worker.timers.get(worker.timers.size() - 1).wait);
		fireTimers();
		fireTimers();
		assertEquals(Boolean.TRUE, done[0]);
		assertEquals(1, clipboard.size(), "the report got there on the third try");

		clipboardBusy = Integer.MAX_VALUE;
		done[0] = null;
		run.copyReport(copied -> done[0] = copied);
		worker.drain();
		for (int i = 0; i < WindowRun.CLIPBOARD_TRIES; i++)
		{
			fireTimers();
		}
		assertEquals(Boolean.FALSE, done[0], "after the last try the button hears it couldn't");
		assertEquals(1, clipboard.size());
		assertEquals(Screen.Phase.READY, last().phase(), "the screen is untouched by any of it");
	}

	@Test
	public void aNoticeThatNeverComesLeavesTheScreenAlone()
	{
		LauncherRunTest.FakeSteps steps = new LauncherRunTest.FakeSteps();
		WindowRun run = windowRun(steps, true, () ->
		{
			throw new IllegalStateException("GitHub answered 500");
		});
		run.start();
		worker.drain();
		assertEquals("", last().notice());
		assertEquals(Screen.Phase.EXITED, last().phase(), "auto mode ran the game through without a click");
		assertEquals("", LauncherRun.fetchNotice(java.net.URI.create("http://127.0.0.1:9/notice.txt"))
			.orElse(""), "a notice that can't be fetched is no notice");
	}
}
