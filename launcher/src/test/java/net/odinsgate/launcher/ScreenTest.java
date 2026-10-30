package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The window's state machine on its own: each thing the server can answer, each button, the timers, the notice
 * and the first-run line, as the plan's messages table has them. No Swing, no threads, no clock but the one the
 * events carry.
 */
public class ScreenTest
{
	private static final Instant T0 = Instant.parse("2026-10-29T19:00:00Z");
	private static final ServerCheck.Result READY = new ServerCheck.Result.Ready(241, 3);
	private static final ServerCheck.Result OFFLINE = new ServerCheck.Result.Offline("the game port doesn't answer");

	private static Screen.Event checked(ServerCheck.Result result, Instant now)
	{
		return new Screen.Event.Checked(result, now);
	}

	private static Instant after(Duration d)
	{
		return T0.plus(d);
	}

	@Test
	public void theWindowOpensOnTheServerCheck()
	{
		Screen screen = new Screen(false, false);
		assertEquals(List.of(new Screen.Command.Check()), screen.start());
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.CHECKING, view.phase());
		assertEquals("Checking the server…", view.status());
		assertTrue(view.busy());
		assertFalse(view.canPlay());
		assertFalse(view.canRetry());
	}

	@Test
	public void aDeadPortWaitsAndTriesAgainEveryThirtySeconds()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		assertEquals(List.of(new Screen.Command.RetryAfter(Duration.ofSeconds(30))), screen.on(checked(OFFLINE, T0)));
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.WAITING, view.phase());
		assertEquals("The server is offline or restarting.", view.status());
		assertEquals("Trying again in 30 seconds.", view.detail());
		assertFalse(view.busy());
		assertTrue(view.canRetry());
		assertFalse(view.canPlay());

		assertEquals(List.of(), screen.on(new Screen.Event.Tick(after(Duration.ofSeconds(10)))));
		assertEquals("Trying again in 20 seconds.", screen.view().detail());
		screen.on(new Screen.Event.Tick(after(Duration.ofSeconds(29))));
		assertEquals("Trying again in 1 second.", screen.view().detail());
		screen.on(new Screen.Event.Tick(after(Duration.ofSeconds(31))));
		assertEquals("Trying again in 0 seconds.", screen.view().detail(), "a late tick never counts below zero");

		// The timer fires: the check runs again, and the outage is still a short one.
		assertEquals(List.of(new Screen.Command.Check()), screen.on(new Screen.Event.Retry()));
		assertEquals(Screen.Phase.CHECKING, screen.view().phase());
		screen.on(checked(OFFLINE, after(Duration.ofSeconds(30))));
		assertEquals("The server is offline or restarting.", screen.view().status());
	}

	@Test
	public void afterFiveMinutesTheMessageSaysForHowLong()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		screen.on(checked(OFFLINE, T0));
		for (int i = 1; i < 10; i++)
		{
			screen.on(new Screen.Event.Retry());
			screen.on(checked(OFFLINE, after(Duration.ofSeconds(30L * i))));
		}
		assertEquals("The server is offline or restarting.", screen.view().status(), "4.5 minutes in");
		screen.on(new Screen.Event.Retry());
		screen.on(checked(OFFLINE, after(Duration.ofMinutes(5))));
		assertEquals("The server has been offline for 5 minutes.", screen.view().status());
		screen.on(new Screen.Event.Tick(after(Duration.ofMinutes(7).plusSeconds(10))));
		assertEquals("The server has been offline for 7 minutes.", screen.view().status(), "a tick keeps it current");
		assertTrue(screen.view().canRetry(), "Retry stays, the plan's button for a long outage");
	}

	@Test
	public void anOutageThatEndedStartsFromZeroNextTime()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		screen.on(checked(OFFLINE, T0));
		screen.on(new Screen.Event.Retry());
		screen.on(checked(READY, after(Duration.ofMinutes(1))));
		assertEquals(Screen.Phase.DOWNLOADING, screen.view().phase());
		screen.on(new Screen.Event.FetchFailed("503"));
		screen.on(new Screen.Event.Retry());
		screen.on(checked(OFFLINE, after(Duration.ofMinutes(20))));
		assertEquals("The server is offline or restarting.", screen.view().status(),
			"the twenty minutes include a time the server was up");
	}

	@Test
	public void aFullServerAndNoInternetWaitAndRetryToo()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		assertEquals(List.of(new Screen.Command.RetryAfter(Screen.RETRY_WAIT)),
			screen.on(checked(new ServerCheck.Result.Full(241, 50), T0)));
		assertEquals("The server is full (50 players).", screen.view().status());
		assertEquals("Trying again in 30 seconds.", screen.view().detail());
		assertTrue(screen.view().canRetry());

		screen.on(new Screen.Event.Retry());
		assertEquals(List.of(new Screen.Command.RetryAfter(Screen.RETRY_WAIT)),
			screen.on(checked(new ServerCheck.Result.NoInternet(), T0)));
		assertEquals("Can't reach the internet. Check your connection.", screen.view().status());
	}

	@Test
	public void losingTheInternetDuringAnOutageStopsTheOutagesClock()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		screen.on(checked(OFFLINE, T0));
		screen.on(new Screen.Event.Retry());
		screen.on(checked(new ServerCheck.Result.NoInternet(), after(Duration.ofMinutes(1))));
		assertEquals("Can't reach the internet. Check your connection.", screen.view().status());
		screen.on(new Screen.Event.Tick(after(Duration.ofMinutes(1).plusSeconds(5))));
		assertEquals("Can't reach the internet. Check your connection.", screen.view().status(),
			"a tick keeps the internet line, not the outage's");
		assertEquals("Trying again in 25 seconds.", screen.view().detail());

		screen.on(new Screen.Event.Retry());
		screen.on(checked(OFFLINE, after(Duration.ofMinutes(10))));
		assertEquals("The server is offline or restarting.", screen.view().status(),
			"the outage starts over once the server can be asked again");
	}

	@Test
	public void aRevisionTheLauncherDoesNotKnowStopsUntilItsUpdate()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		assertEquals(List.of(), screen.on(checked(new ServerCheck.Result.UnknownRevision(242), T0)));
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.STOPPED, view.phase());
		assertEquals("The server was updated.", view.status());
		assertTrue(view.detail().contains("242"), view.detail());
		assertTrue(view.detail().contains("update is on its way"), view.detail());
		assertFalse(view.busy());
		assertTrue(view.canRetry(), "Retry asks the server again, for when the update has landed");
		assertFalse(view.canPlay());
		assertEquals(List.of(new Screen.Command.Check()), screen.on(new Screen.Event.Retry()));
	}

	@Test
	public void aChangedKeyStopsTheLaunch()
	{
		Screen screen = new Screen(true, false);
		screen.start();
		assertEquals(List.of(), screen.on(checked(new ServerCheck.Result.KeyChanged(), T0)),
			"nothing is fetched and nothing starts, even in auto mode");
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.STOPPED, view.phase());
		assertEquals("The server's key changed. Please tell Sam.", view.status());
		assertFalse(view.canPlay());
		assertEquals(List.of(), screen.on(new Screen.Event.Play()), "Play does nothing here");
	}

	@Test
	public void aReadyServerFetchesAndThenWaitsForPlay()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		assertEquals(List.of(new Screen.Command.Fetch()), screen.on(checked(READY, T0)));
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.DOWNLOADING, view.phase());
		assertEquals("Updating the game client for the server…", view.status());
		assertEquals("Revision 241", view.detail());
		assertTrue(view.busy());

		assertEquals(List.of(), screen.on(new Screen.Event.Fetched()));
		view = screen.view();
		assertEquals(Screen.Phase.READY, view.phase());
		assertEquals("Ready to play.", view.status());
		assertFalse(view.busy());
		assertTrue(view.canPlay());
		assertFalse(view.canRetry());

		assertEquals(List.of(new Screen.Command.Launch()), screen.on(new Screen.Event.Play()));
		view = screen.view();
		assertEquals(Screen.Phase.LAUNCHING, view.phase());
		assertEquals("Starting the game…", view.status());
		assertTrue(view.busy());
		assertFalse(view.canPlay());

		assertEquals(List.of(new Screen.Command.WatchEvery(Screen.WATCH_PERIOD)),
			screen.on(new Screen.Event.Launched(4321)));
		view = screen.view();
		assertEquals(Screen.Phase.RUNNING, view.phase());
		assertEquals("The game is running.", view.status());
		assertFalse(view.busy());
		assertFalse(view.canPlay());

		assertEquals(List.of(), screen.on(new Screen.Event.Exited(0)));
		view = screen.view();
		assertEquals(Screen.Phase.EXITED, view.phase());
		assertEquals("The game closed.", view.status());
		assertEquals("Play starts it again.", view.detail());
		assertTrue(view.canPlay());
	}

	@Test
	public void playAfterTheGameClosedChecksAgainAndStartsWithoutASecondClick()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		screen.on(checked(READY, T0));
		screen.on(new Screen.Event.Fetched());
		screen.on(new Screen.Event.Play());
		screen.on(new Screen.Event.Launched(4321));
		screen.on(new Screen.Event.Exited(7));
		assertEquals("The game closed with an error (code 7).", screen.view().status());

		assertEquals(List.of(new Screen.Command.Check()), screen.on(new Screen.Event.Play()));
		assertEquals(Screen.Phase.CHECKING, screen.view().phase());
		assertEquals(List.of(new Screen.Command.Fetch()), screen.on(checked(READY, after(Duration.ofMinutes(1)))));
		assertEquals(List.of(new Screen.Command.Launch()), screen.on(new Screen.Event.Fetched()),
			"the click on Play is remembered through the check and the fetch");
		screen.on(new Screen.Event.Launched(4322));
		assertEquals(Screen.Phase.RUNNING, screen.view().phase());
	}

	private static Screen running(int revision)
	{
		Screen screen = new Screen(false, false);
		screen.start();
		screen.on(checked(new ServerCheck.Result.Ready(revision, 3), T0));
		screen.on(new Screen.Event.Fetched());
		screen.on(new Screen.Event.Play());
		assertEquals(List.of(new Screen.Command.WatchEvery(Screen.WATCH_PERIOD)),
			screen.on(new Screen.Event.Launched(4321)), "the server is watched while the game runs");
		return screen;
	}

	@Test
	public void aRevisionThatMovedWhileTheGameRunsAsksForARestart()
	{
		Screen screen = running(241);
		assertEquals(List.of(), screen.on(new Screen.Event.Watched(241)));
		assertEquals("The game is running.", screen.view().status());
		assertEquals(List.of(), screen.on(new Screen.Event.Watched(null)), "a server that didn't answer says nothing");
		assertEquals("The game is running.", screen.view().status());
		assertEquals(List.of(), screen.on(new Screen.Event.Play()), "no restart before the server moved");

		assertEquals(List.of(), screen.on(new Screen.Event.Watched(242)));
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.RUNNING, view.phase());
		assertEquals(Screen.UPDATED_LINE, view.status());
		assertEquals(Screen.RESTART_LINE, view.detail());
		assertTrue(view.canPlay());
		assertFalse(view.busy());
		screen.on(new Screen.Event.Watched(243));
		assertEquals(view, screen.view(), "one restart is asked for, whatever the server says next");

		assertEquals(List.of(new Screen.Command.Restart()), screen.on(new Screen.Event.Play()));
		view = screen.view();
		assertEquals(Screen.Phase.RUNNING, view.phase());
		assertEquals("Closing the game…", view.status());
		assertTrue(view.busy());
		assertFalse(view.canPlay());
		assertEquals(List.of(), screen.on(new Screen.Event.Play()), "one click closes it");

		assertEquals(List.of(new Screen.Command.Check()), screen.on(new Screen.Event.Exited(1)),
			"the closed game's exit starts the launch over instead of ending it");
		assertEquals(Screen.Phase.CHECKING, screen.view().phase());
		assertEquals(List.of(new Screen.Command.Fetch()), screen.on(checked(new ServerCheck.Result.Ready(242, 3), T0)));
		assertEquals(List.of(new Screen.Command.Launch()), screen.on(new Screen.Event.Fetched()),
			"no second click on Play");
		assertEquals(List.of(new Screen.Command.WatchEvery(Screen.WATCH_PERIOD)),
			screen.on(new Screen.Event.Launched(4322)));
		assertEquals("The game is running.", screen.view().status());
		assertEquals(List.of(), screen.on(new Screen.Event.Watched(242)));
		assertEquals("The game is running.", screen.view().status(), "the new revision is the one the game runs");
		screen.on(new Screen.Event.Exited(0));
		assertEquals(Screen.Phase.EXITED, screen.view().phase(), "an exit that wasn't a restart ends as before");
	}

	@Test
	public void aMovedRevisionTheLauncherDoesNotPinStopsAfterTheRestart()
	{
		Screen screen = running(241);
		screen.on(new Screen.Event.Watched(242));
		screen.on(new Screen.Event.Play());
		screen.on(new Screen.Event.Exited(1));
		assertEquals(List.of(), screen.on(checked(new ServerCheck.Result.UnknownRevision(242), T0)));
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.STOPPED, view.phase());
		assertEquals("The server was updated.", view.status());
		assertTrue(view.detail().contains("242"), view.detail());
		assertTrue(view.canRetry());
	}

	@Test
	public void autoStartsTheGameWithoutAClickOnce()
	{
		Screen screen = new Screen(true, false);
		screen.start();
		screen.on(checked(READY, T0));
		assertEquals(List.of(new Screen.Command.Launch()), screen.on(new Screen.Event.Fetched()));
		screen.on(new Screen.Event.Launched(4321));
		screen.on(new Screen.Event.Exited(0));
		assertTrue(screen.view().canPlay(), "the game is not started again by itself");
	}

	@Test
	public void aFailedDownloadOrStartStopsWithTheReasonAndRetryStartsOver()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		screen.on(checked(READY, T0));
		assertEquals(List.of(), screen.on(new Screen.Event.FetchFailed("raw.githubusercontent.com answered 503")));
		Screen.View view = screen.view();
		assertEquals(Screen.Phase.STOPPED, view.phase());
		assertEquals("The game client could not be downloaded.", view.status());
		assertEquals("raw.githubusercontent.com answered 503", view.detail());
		assertTrue(view.canRetry());
		assertFalse(view.canPlay());
		assertFalse(view.busy());
		assertEquals(List.of(new Screen.Command.Check()), screen.on(new Screen.Event.Retry()));

		screen.on(checked(READY, T0));
		screen.on(new Screen.Event.Fetched());
		screen.on(new Screen.Event.Play());
		screen.on(new Screen.Event.LaunchFailed("no java at runtime/bin/java"));
		view = screen.view();
		assertEquals(Screen.Phase.STOPPED, view.phase());
		assertEquals("The game could not be started.", view.status());
		assertEquals("no java at runtime/bin/java", view.detail());
		assertTrue(view.canRetry());

		screen.on(new Screen.Event.Retry());
		screen.on(new Screen.Event.Failed("NullPointerException"));
		view = screen.view();
		assertEquals(Screen.Phase.STOPPED, view.phase());
		assertEquals("The launcher ran into a problem.", view.status());
		assertEquals("NullPointerException", view.detail());
	}

	@Test
	public void theNoticeIsShownAsItIsAndCutWhenLong()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		assertEquals("", screen.view().notice());
		assertEquals(List.of(), screen.on(new Screen.Event.Notice("<html><b>Down</b> tonight</html>")));
		assertEquals("<html><b>Down</b> tonight</html>", screen.view().notice(), "markup is text, never rendered");

		screen.on(new Screen.Event.Notice("  \r\nTwo lines\r\nof notice\r\n  "));
		assertEquals("Two lines\nof notice", screen.view().notice());

		screen.on(new Screen.Event.Notice("x".repeat(Screen.NOTICE_LIMIT + 100)));
		assertEquals(Screen.NOTICE_LIMIT + 1, screen.view().notice().length());
		assertTrue(screen.view().notice().endsWith("…"));

		screen.on(new Screen.Event.Notice("   "));
		assertEquals("", screen.view().notice());
	}

	@Test
	public void theFirstRunLineIsThereOnlyOnTheFirstRun()
	{
		assertTrue(new Screen(false, true).view().firstRun());
		assertFalse(new Screen(false, false).view().firstRun());
		assertTrue(Screen.FIRST_RUN_LINE.contains("Never type the login of your real game account"));
	}

	@Test
	public void anEventOutOfTurnChangesNothing()
	{
		Screen screen = new Screen(false, false);
		screen.start();
		assertEquals(List.of(), screen.on(new Screen.Event.Play()), "no Play while checking");
		assertEquals(List.of(), screen.on(new Screen.Event.Retry()), "no Retry while checking");
		assertEquals(List.of(), screen.on(new Screen.Event.Fetched()), "nothing was being fetched");
		assertEquals(List.of(), screen.on(new Screen.Event.Exited(0)), "nothing was running");
		assertEquals(Screen.Phase.CHECKING, screen.view().phase());

		screen.on(checked(READY, T0));
		screen.on(new Screen.Event.Fetched());
		Screen.View ready = screen.view();
		assertEquals(List.of(), screen.on(checked(OFFLINE, T0)), "a check that arrives late is ignored");
		assertEquals(List.of(), screen.on(new Screen.Event.Tick(T0)));
		assertEquals(List.of(), screen.on(new Screen.Event.Retry()));
		assertEquals(ready, screen.view());
	}
}
