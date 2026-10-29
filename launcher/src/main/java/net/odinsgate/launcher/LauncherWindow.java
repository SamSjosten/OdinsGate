package net.odinsgate.launcher;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.PrintStream;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The launcher window itself: a frame titled with the app's name around a {@link LauncherPanel}, driven by a
 * {@link WindowRun} on Swing's event thread with a small daemon thread pool for the steps that block.
 * {@link #open} returns when the friend closes the window; the game, once started, is its own process and keeps
 * running (the launcher staying open through the game's run is the next PR).
 */
public final class LauncherWindow
{
	private LauncherWindow()
	{
	}

	/**
	 * Opens the window and returns its exit code once it is closed: 0, or {@link LauncherRun#EXIT_START} when the
	 * window could not be shown at all.
	 *
	 * @param session the run's steps, STARTING reported
	 * @param steps where the notice comes from
	 * @param firstRun whether the first-run line is shown
	 * @param log where a window that can't be shown is explained
	 */
	public static int open(LauncherRun.Session session, LauncherRun.Steps steps, boolean firstRun, Clock clock,
		PrintStream log)
	{
		ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, task ->
		{
			Thread thread = new Thread(task, "launcher-worker");
			thread.setDaemon(true);
			return thread;
		});
		CountDownLatch closed = new CountDownLatch(1);
		Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				show(session, steps, firstRun, clock, pool, closed);
			}
			catch (RuntimeException | Error e)
			{
				failure[0] = e;
				closed.countDown();
			}
		});
		try
		{
			closed.await();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		pool.shutdownNow();
		if (failure[0] != null)
		{
			log.println("[launcher] the window could not be shown: " + WindowRun.message(failure[0]));
			return LauncherRun.EXIT_START;
		}
		return 0;
	}

	private static void show(LauncherRun.Session session, LauncherRun.Steps steps, boolean firstRun, Clock clock,
		ScheduledExecutorService pool, CountDownLatch closed)
	{
		JFrame frame = new JFrame(Names.APP_NAME);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent event)
			{
				closed.countDown();
			}
		});
		WindowRun[] run = new WindowRun[1];
		LauncherPanel panel = new LauncherPanel(() -> run[0].play(), () -> run[0].retry());
		run[0] = new WindowRun(new Screen(false, firstRun), session, steps::notice, WindowRun.Worker.threads(pool),
			SwingUtilities::invokeLater, panel::render, clock);
		frame.setContentPane(panel);
		frame.pack();
		frame.setMinimumSize(frame.getSize());
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
		run[0].start();
	}
}
