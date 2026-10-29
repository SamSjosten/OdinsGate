package net.odinsgate.launcher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The launcher window as a state machine with no Swing in it: what the friend sees ({@link View}) and what the
 * launcher should do next ({@link Command}), driven by what happens ({@link Event}). {@link WindowRun} feeds it
 * the outside world's answers and runs its commands; {@link LauncherPanel} draws the view. The words are the
 * plan's: before Play the launcher checks the server and fetches the game client; an offline server is tried again
 * every {@link #RETRY_WAIT} and, after {@link #LONG_OUTAGE}, the message says for how long; a changed key stops
 * the launch; the notice from GitHub is shown as it is; the first run carries the line about a new name and
 * password. Everything here runs on one thread, the window's.
 */
public final class Screen
{
	/** Where the window is. */
	public enum Phase
	{
		/** The server check runs. */
		CHECKING,
		/** The check failed and will run again, by itself or by Retry. */
		WAITING,
		/** The game client is fetched or verified. */
		DOWNLOADING,
		/** Everything is in place; Play starts the game. */
		READY,
		/** The game's child JVM is being started. */
		LAUNCHING,
		/** The game runs. */
		RUNNING,
		/** The game ran and closed; Play starts it again. */
		EXITED,
		/** Something stopped the launch; Retry starts over from the check. */
		STOPPED
	}

	/**
	 * What to draw.
	 *
	 * @param status the headline: what is happening, or what stopped it
	 * @param detail the line under it, often empty
	 * @param busy whether work is in progress (the progress bar moves)
	 * @param canPlay whether the Play button does anything
	 * @param canRetry whether the Retry button does anything
	 * @param notice the notice from GitHub, as plain text, or empty
	 * @param firstRun whether the first-run line is shown
	 */
	public record View(Phase phase, String status, String detail, boolean busy, boolean canPlay, boolean canRetry,
		String notice, boolean firstRun)
	{
	}

	/** What the launcher should do next, in order. */
	public sealed interface Command
	{
		/** Run the server check and answer with {@link Event.Checked}. */
		record Check() implements Command
		{
		}

		/** Fetch the game client and answer with {@link Event.Fetched} or {@link Event.FetchFailed}. */
		record Fetch() implements Command
		{
		}

		/**
		 * Start the game and answer with {@link Event.Launched} or {@link Event.LaunchFailed}, then
		 * {@link Event.Exited}.
		 */
		record Launch() implements Command
		{
		}

		/** Send {@link Event.Retry} after this long unless the friend clicks Retry first, with ticks meanwhile. */
		record RetryAfter(Duration delay) implements Command
		{
		}
	}

	/** What happened. */
	public sealed interface Event
	{
		record Checked(ServerCheck.Result result, Instant now) implements Event
		{
		}

		record Fetched() implements Event
		{
		}

		record FetchFailed(String why) implements Event
		{
		}

		record Launched(long pid) implements Event
		{
		}

		record LaunchFailed(String why) implements Event
		{
		}

		record Exited(int code) implements Event
		{
		}

		/** The Play button. */
		record Play() implements Event
		{
		}

		/** The Retry button, or the wait of a {@link Command.RetryAfter} over. */
		record Retry() implements Event
		{
		}

		/** Time passes while waiting: the countdown and the outage's length are redrawn. */
		record Tick(Instant now) implements Event
		{
		}

		/** The notice arrived. */
		record Notice(String text) implements Event
		{
		}

		/** A step threw what it shouldn't have; the message is all there is to show. */
		record Failed(String why) implements Event
		{
		}
	}

	/** How long a failed check waits before it runs again by itself. */
	public static final Duration RETRY_WAIT = Duration.ofSeconds(30);
	/** From here on an outage's message says how long the server has been offline. */
	public static final Duration LONG_OUTAGE = Duration.ofMinutes(5);
	/** More of a notice than this is cut, with an ellipsis. */
	public static final int NOTICE_LIMIT = 500;
	public static final String FIRST_RUN_LINE =
		"Make up a new name and password here. Never type the login of your real game account.";

	private final boolean firstRun;
	private Phase phase = Phase.CHECKING;
	private String status = "";
	private String detail = "";
	private boolean canPlay;
	private boolean canRetry;
	private String notice = "";
	/** Play is wanted as soon as the client is ready: {@code --auto}, or the button was clicked. */
	private boolean playWanted;
	/** When the current outage began, or null while the server answers. */
	private Instant offlineSince;
	/** When the next check runs by itself, or null. */
	private Instant retryAt;

	/**
	 * @param auto start the game without a click on Play, once
	 * @param firstRun show the first-run line
	 */
	public Screen(boolean auto, boolean firstRun)
	{
		this.playWanted = auto;
		this.firstRun = firstRun;
	}

	public View view()
	{
		boolean busy = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.LAUNCHING;
		return new View(phase, status, detail, busy, canPlay, canRetry, notice, firstRun);
	}

	/** The first thing the window does: check the server. */
	public List<Command> start()
	{
		return check();
	}

	public List<Command> on(Event event)
	{
		Objects.requireNonNull(event);
		return switch (event)
		{
			case Event.Checked checked -> checked(checked.result(), checked.now());
			case Event.Fetched fetched -> fetched();
			case Event.FetchFailed failed -> stopped("The game client could not be downloaded.", failed.why());
			case Event.Launched launched -> launched();
			case Event.LaunchFailed failed -> stopped("The game could not be started.", failed.why());
			case Event.Exited exited -> exited(exited.code());
			case Event.Play play -> play();
			case Event.Retry retry -> retry();
			case Event.Tick tick -> tick(tick.now());
			case Event.Notice notice -> notice(notice.text());
			case Event.Failed failed -> stopped("The launcher ran into a problem.", failed.why());
		};
	}

	private List<Command> check()
	{
		phase = Phase.CHECKING;
		status = "Checking the server…";
		detail = "";
		canPlay = false;
		canRetry = false;
		retryAt = null;
		return List.of(new Command.Check());
	}

	private List<Command> checked(ServerCheck.Result result, Instant now)
	{
		if (phase != Phase.CHECKING)
		{
			return List.of();
		}
		switch (result)
		{
			case ServerCheck.Result.Ready ready ->
			{
				offlineSince = null;
				phase = Phase.DOWNLOADING;
				status = "Updating the game client for the server…";
				detail = "Revision " + ready.revision();
				return List.of(new Command.Fetch());
			}
			case ServerCheck.Result.Offline offline ->
			{
				if (offlineSince == null)
				{
					offlineSince = now;
				}
				return waiting(outage(now), now);
			}
			case ServerCheck.Result.Full full ->
			{
				offlineSince = null;
				return waiting("The server is full (" + full.players() + " players).", now);
			}
			case ServerCheck.Result.NoInternet noInternet ->
			{
				// Whether the server is up can't be told from here, so the outage's clock stops with its message.
				offlineSince = null;
				return waiting("Can't reach the internet. Check your connection.", now);
			}
			case ServerCheck.Result.UnknownRevision unknown ->
			{
				offlineSince = null;
				return stopped("The server was updated.", "This launcher doesn't know revision " + unknown.revision()
					+ " yet; its update is on its way. Try again in a few minutes.");
			}
			case ServerCheck.Result.KeyChanged changed ->
			{
				offlineSince = null;
				return stopped("The server's key changed. Please tell Sam.", "");
			}
		}
	}

	private List<Command> waiting(String why, Instant now)
	{
		phase = Phase.WAITING;
		status = why;
		retryAt = now.plus(RETRY_WAIT);
		detail = countdown(now);
		canPlay = false;
		canRetry = true;
		return List.of(new Command.RetryAfter(RETRY_WAIT));
	}

	private List<Command> stopped(String what, String why)
	{
		phase = Phase.STOPPED;
		status = what;
		detail = why == null ? "" : why;
		retryAt = null;
		canPlay = false;
		canRetry = true;
		return List.of();
	}

	private List<Command> fetched()
	{
		if (phase != Phase.DOWNLOADING)
		{
			return List.of();
		}
		if (playWanted)
		{
			return launch();
		}
		phase = Phase.READY;
		status = "Ready to play.";
		detail = "";
		canPlay = true;
		canRetry = false;
		return List.of();
	}

	private List<Command> launch()
	{
		phase = Phase.LAUNCHING;
		status = "Starting the game…";
		detail = "";
		canPlay = false;
		canRetry = false;
		return List.of(new Command.Launch());
	}

	private List<Command> launched()
	{
		playWanted = false;
		phase = Phase.RUNNING;
		status = "The game is running.";
		detail = "";
		canPlay = false;
		canRetry = false;
		return List.of();
	}

	private List<Command> exited(int code)
	{
		if (phase != Phase.RUNNING)
		{
			return List.of();
		}
		phase = Phase.EXITED;
		status = code == 0 ? "The game closed." : "The game closed with an error (code " + code + ").";
		detail = "Play starts it again.";
		canPlay = true;
		canRetry = false;
		return List.of();
	}

	private List<Command> play()
	{
		return switch (phase)
		{
			case READY -> launch();
			case EXITED ->
			{
				playWanted = true;
				yield check();
			}
			default -> List.of();
		};
	}

	private List<Command> retry()
	{
		return switch (phase)
		{
			case WAITING, STOPPED -> check();
			default -> List.of();
		};
	}

	private List<Command> tick(Instant now)
	{
		if (phase != Phase.WAITING)
		{
			return List.of();
		}
		if (offlineSince != null)
		{
			status = outage(now);
		}
		detail = countdown(now);
		return List.of();
	}

	private List<Command> notice(String text)
	{
		String clean = text == null ? "" : text.replace("\r\n", "\n").strip();
		if (clean.length() > NOTICE_LIMIT)
		{
			clean = clean.substring(0, NOTICE_LIMIT).stripTrailing() + "…";
		}
		notice = clean;
		return List.of();
	}

	private String outage(Instant now)
	{
		Duration length = Duration.between(offlineSince, now);
		if (length.compareTo(LONG_OUTAGE) < 0)
		{
			return "The server is offline or restarting.";
		}
		return "The server has been offline for " + length.toMinutes() + " minutes.";
	}

	private String countdown(Instant now)
	{
		if (retryAt == null)
		{
			return "";
		}
		long seconds = Math.max(0, Duration.between(now, retryAt).toSeconds());
		return "Trying again in " + seconds + (seconds == 1 ? " second." : " seconds.");
	}
}
