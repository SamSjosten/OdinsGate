package net.odinsgate.launcher;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.PrintStream;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The launcher window itself: a frame titled with the app's name around a {@link LauncherPanel}, driven by a
 * {@link WindowRun} on Swing's event thread with a small daemon thread pool for the steps that block.
 * {@link #open} returns when the friend closes the window; the game, once started, is its own process and keeps
 * running. While the window is open and the game runs, the server is looked at once a minute for a new revision.
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
		ScheduledExecutorService pool = Executors.newScheduledThreadPool(3, task ->
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
		stop(pool);
		if (failure[0] != null)
		{
			log.println("[launcher] the window could not be shown: " + WindowRun.message(failure[0]));
			return LauncherRun.EXIT_START;
		}
		return 0;
	}

	/**
	 * Lets the worker go without interrupting it: the thread waiting on the game must not be, since an interrupted
	 * {@link LauncherRun.Session#waitFor} destroys the game, and the window closing is not the game's end. The
	 * launcher's exit leaves the game's process running on its own; the threads are daemons and go with the JVM.
	 */
	static void stop(ScheduledExecutorService pool)
	{
		pool.shutdown();
	}

	/** The system's clipboard; on the event thread. */
	static void toClipboard(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	/** What a problem report says about the machine. */
	static Map<String, String> system()
	{
		Map<String, String> system = new HashMap<>();
		for (String key : List.of("os.name", "os.version", "java.version", "java.vendor"))
		{
			String value = System.getProperty(key);
			if (value != null)
			{
				system.put(key, value);
			}
		}
		return system;
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
		LauncherPanel[] panel = new LauncherPanel[1];
		panel[0] = new LauncherPanel(() -> run[0].play(), () -> run[0].retry(),
			() -> run[0].copyReport(panel[0]::reportCopied));
		run[0] = new WindowRun(new Screen(false, firstRun), session, steps::notice, WindowRun.Worker.threads(pool),
			SwingUtilities::invokeLater, panel[0]::render, clock, LauncherWindow::toClipboard, system());
		frame.setContentPane(panel[0]);
		// The first view (the check under way, the first-run line when it applies) is drawn before the frame is
		// sized, so the frame opens with room for it; what appears later (the notice, a detail) grows the frame.
		run[0].start();
		frame.pack();
		frame.setMinimumSize(frame.getSize());
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}
}
