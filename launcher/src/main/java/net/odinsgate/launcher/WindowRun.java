package net.odinsgate.launcher;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The launcher window's driver: it runs the {@link Screen}'s commands against a {@link LauncherRun.Session} and
 * feeds the answers back as events, each change drawn through {@code render}. The screen changes on one thread
 * only, the window's ({@link UiThread}); every step that blocks (the server check, the download, the game's
 * start and its whole run) goes to the {@link Worker}, whose answer is handed back to the window's thread. The
 * timers of a failed check (the retry in thirty seconds, the once-a-second redraw of the countdown) run on the
 * worker too, and are cancelled the moment a check starts, by the timer or by the Retry button; so does the
 * once-a-minute look at the server while the game runs, cancelled when the game is gone. A restart closes the
 * game and lets its exit start the launch over. The window ({@link LauncherWindow}) gives this Swing's event
 * thread and a small thread pool; the tests give a thread of their own and a hand-cranked worker.
 */
public final class WindowRun
{
	/** Something scheduled that can be called off. */
	public interface Cancellable
	{
		void cancel();
	}

	/** Where the blocking steps run and the timers tick: a thread pool in the window, a hand-cranked fake in tests. */
	public interface Worker
	{
		/** Runs {@code task} off the window's thread, as soon as it can. */
		void run(Runnable task);

		/** Runs {@code task} once, after {@code wait}, unless cancelled first. */
		Cancellable after(Duration wait, Runnable task);

		/** Runs {@code task} every {@code period} until cancelled. */
		Cancellable every(Duration period, Runnable task);

		/**
		 * The worker over {@code pool}: the game's run holds one thread; the checks, the watch and the report share
		 * the rest.
		 */
		static Worker threads(ScheduledExecutorService pool)
		{
			return new Worker()
			{
				@Override
				public void run(Runnable task)
				{
					pool.execute(task);
				}

				@Override
				public Cancellable after(Duration wait, Runnable task)
				{
					ScheduledFuture<?> future = pool.schedule(task, wait.toMillis(), TimeUnit.MILLISECONDS);
					return () -> future.cancel(false);
				}

				@Override
				public Cancellable every(Duration period, Runnable task)
				{
					ScheduledFuture<?> future =
						pool.scheduleAtFixedRate(task, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
					return () -> future.cancel(false);
				}
			};
		}
	}

	/** The window's one thread, where the screen changes and the view is drawn: Swing's, or the test's own. */
	public interface UiThread
	{
		void later(Runnable task);
	}

	/** How often the countdown of a failed check is redrawn. */
	static final Duration TICK = Duration.ofSeconds(1);
	/** How many times, how far apart, a clipboard that another program holds is tried. */
	static final int CLIPBOARD_TRIES = 4;
	static final Duration CLIPBOARD_RETRY = Duration.ofMillis(250);

	private final Screen screen;
	private final LauncherRun.Session session;
	private final Supplier<Optional<String>> notice;
	private final Worker worker;
	private final UiThread ui;
	private final Consumer<Screen.View> render;
	private final Clock clock;
	private final Consumer<String> clipboard;
	private final Map<String, String> system;
	/** What the last fetch gave, for the launch; written by the worker, read by it after the window's thread. */
	private volatile Launch.Pin fetched;
	/** The running game, for a restart; written by the worker, read on the window's thread. */
	private volatile Process game;
	private Cancellable retry;
	private Cancellable ticks;
	private Cancellable watch;

	/**
	 * @param screen the state machine, fresh
	 * @param session the run's steps, STARTING reported and nothing dialed yet
	 * @param notice where the notice comes from, asked once on the worker
	 * @param worker where the blocking steps and the timers run
	 * @param ui the window's thread
	 * @param render draws a view; called on the window's thread after every change
	 * @param clock the time the countdown and the outage's length are measured by
	 * @param clipboard where a problem report goes; called on the window's thread
	 * @param system {@code os.name}, {@code os.version}, {@code java.version}, {@code java.vendor} for the report
	 */
	public WindowRun(Screen screen, LauncherRun.Session session, Supplier<Optional<String>> notice, Worker worker,
		UiThread ui, Consumer<Screen.View> render, Clock clock, Consumer<String> clipboard,
		Map<String, String> system)
	{
		this.screen = screen;
		this.session = session;
		this.notice = notice;
		this.worker = worker;
		this.ui = ui;
		this.render = render;
		this.clock = clock;
		this.clipboard = clipboard;
		this.system = system;
	}

	/** On the window's thread: draws the first view, asks for the notice, and starts the server check. */
	public void start()
	{
		worker.run(this::fetchNotice);
		List<Screen.Command> commands = screen.start();
		render.accept(screen.view());
		execute(commands);
	}

	/** The Play button, on the window's thread. */
	public void play()
	{
		on(new Screen.Event.Play());
	}

	/** The Retry button, on the window's thread. */
	public void retry()
	{
		on(new Screen.Event.Retry());
	}

	/**
	 * The "Copy problem report" button, on the window's thread: the report is put together on the worker (it reads
	 * the logs) and handed to the clipboard back here, then {@code done} hears whether it got there. A clipboard
	 * another program holds at that moment (Windows refuses with an {@link IllegalStateException}) is tried again
	 * a few times, a moment apart, before the button says it couldn't.
	 */
	public void copyReport(Consumer<Boolean> done)
	{
		worker.run(() ->
		{
			String report = ProblemReport.build(session.platform(), system, version(), session.revision(),
				session.pin(), session.recent(), session.dataDir(), session.gameLog(), session.clientLog(),
				clock.instant());
			ui.later(() -> paste(report, CLIPBOARD_TRIES, done));
		});
	}

	private void paste(String report, int triesLeft, Consumer<Boolean> done)
	{
		try
		{
			clipboard.accept(report);
		}
		catch (RuntimeException e)
		{
			if (triesLeft > 1)
			{
				worker.after(CLIPBOARD_RETRY, () -> ui.later(() -> paste(report, triesLeft - 1, done)));
				return;
			}
			done.accept(false);
			return;
		}
		done.accept(true);
	}

	private String version()
	{
		return Main.VERSION;
	}

	/** Hands {@code event} to the screen, draws what it made of it and runs what it asked for. */
	void on(Screen.Event event)
	{
		List<Screen.Command> commands = screen.on(event);
		render.accept(screen.view());
		execute(commands);
		Screen.Phase phase = screen.view().phase();
		if (phase != Screen.Phase.WAITING)
		{
			cancelTimers();
		}
		if (phase != Screen.Phase.RUNNING)
		{
			cancelWatch();
		}
	}

	private void execute(List<Screen.Command> commands)
	{
		for (Screen.Command command : commands)
		{
			switch (command)
			{
				case Screen.Command.Check check ->
				{
					cancelTimers();
					worker.run(guarded(this::check));
				}
				case Screen.Command.Fetch fetch -> worker.run(guarded(this::fetch));
				case Screen.Command.Launch launch -> worker.run(guarded(this::launch));
				case Screen.Command.RetryAfter retryAfter ->
				{
					cancelTimers();
					retry = worker.after(retryAfter.delay(), () -> ui.later(this::retry));
					ticks = worker.every(TICK, () -> ui.later(() -> on(new Screen.Event.Tick(clock.instant()))));
				}
				case Screen.Command.WatchEvery watchEvery ->
				{
					cancelWatch();
					watch = worker.every(watchEvery.period(), guarded(this::watch));
				}
				case Screen.Command.Restart restart ->
				{
					Process running = game;
					if (running != null)
					{
						// Only a signal; the thread waiting on the game answers with Exited once it is gone.
						running.destroy();
					}
				}
			}
		}
	}

	private void watch()
	{
		Integer revision = session.watch().map(ServerCheck.Status::revision).orElse(null);
		ui.later(() -> on(new Screen.Event.Watched(revision)));
	}

	/** The notice is never a reason to stop: whatever goes wrong fetching it, the screen hears nothing. */
	private void fetchNotice()
	{
		Optional<String> text;
		try
		{
			text = notice.get();
		}
		catch (RuntimeException e)
		{
			return;
		}
		text.ifPresent(found -> ui.later(() -> on(new Screen.Event.Notice(found))));
	}

	private void check()
	{
		ServerCheck.Result result = session.check();
		ui.later(() -> on(new Screen.Event.Checked(result, clock.instant())));
	}

	private void fetch()
	{
		try
		{
			fetched = session.fetch();
		}
		catch (IOException | BootstrapException e)
		{
			ui.later(() -> on(new Screen.Event.FetchFailed(message(e))));
			return;
		}
		ui.later(() -> on(new Screen.Event.Fetched()));
	}

	private void launch()
	{
		Process started;
		try
		{
			started = session.start(fetched);
		}
		catch (IOException e)
		{
			ui.later(() -> on(new Screen.Event.LaunchFailed(message(e))));
			return;
		}
		game = started;
		ui.later(() -> on(new Screen.Event.Launched(started.pid())));
		// The wait is its own task, so the game's whole run holds one worker thread and nothing else.
		worker.run(guarded(() ->
		{
			int code = session.waitFor(started);
			ui.later(() -> on(new Screen.Event.Exited(code)));
		}));
	}

	/**
	 * A worker task whose unexpected failure stops the screen with the message instead of leaving it busy, and
	 * reaches the log and the status file as the run's failure, like a stop the steps report themselves.
	 */
	private Runnable guarded(Runnable task)
	{
		return () ->
		{
			try
			{
				task.run();
			}
			catch (RuntimeException e)
			{
				String why = message(e);
				session.fail("unexpected: " + why);
				ui.later(() -> on(new Screen.Event.Failed(why)));
			}
		};
	}

	private void cancelWatch()
	{
		if (watch != null)
		{
			watch.cancel();
			watch = null;
		}
	}

	private void cancelTimers()
	{
		if (retry != null)
		{
			retry.cancel();
			retry = null;
		}
		if (ticks != null)
		{
			ticks.cancel();
			ticks = null;
		}
	}

	static String message(Throwable e)
	{
		return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
	}
}
