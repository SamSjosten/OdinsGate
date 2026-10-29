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
			clock);
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
		steps.result = new ServerCheck.Result.Full(50);
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
