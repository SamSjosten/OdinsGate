package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The frame needs a display and opens in the installer PRs' {@code --self-test}; what can be proven here is how
 * the window lets its worker go: without an interrupt, so a thread waiting on the game never destroys it.
 */
public class LauncherWindowTest
{
	@Test
	public void closingTheWindowDoesNotInterruptTheThreadWaitingOnTheGame() throws Exception
	{
		ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);
		CountDownLatch gameRuns = new CountDownLatch(1);
		CountDownLatch gameOver = new CountDownLatch(1);
		CountDownLatch waited = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		pool.execute(() ->
		{
			gameRuns.countDown();
			try
			{
				gameOver.await();
			}
			catch (InterruptedException e)
			{
				interrupted.set(true);
			}
			waited.countDown();
		});
		assertTrue(gameRuns.await(5, TimeUnit.SECONDS));

		LauncherWindow.stop(pool);
		assertTrue(pool.isShutdown());
		assertFalse(waited.await(200, TimeUnit.MILLISECONDS), "the wait goes on after the window closed");
		assertFalse(interrupted.get());

		gameOver.countDown();
		assertTrue(waited.await(5, TimeUnit.SECONDS));
		assertFalse(interrupted.get(), "the game's wait ended with the game, not with an interrupt");
		assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
	}
}
