package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;

/**
 * The Swing panel, headless: each part of a view lands in its component, the buttons call back only while the
 * view enables them, and no text is ever rendered as HTML. (The frame itself needs a display; the installer PRs'
 * {@code --self-test} opens it on each system.)
 */
public class LauncherPanelTest
{
	private static Screen.View view(Screen.Phase phase, String status, String detail, boolean busy,
		boolean canPlay, boolean canRetry, String notice, boolean firstRun)
	{
		return new Screen.View(phase, status, detail, busy, canPlay, canRetry, notice, firstRun);
	}

	@Test
	public void eachPartOfTheViewIsDrawn()
	{
		LauncherPanel panel = new LauncherPanel(() -> {}, () -> {}, () -> {});
		panel.render(view(Screen.Phase.WAITING, "The server is offline or restarting.", "Trying again in 30 seconds.",
			false, false, true, "", true));
		assertEquals("The server is offline or restarting.", panel.statusLabel().getText());
		assertEquals("Trying again in 30 seconds.", panel.detailLabel().getText());
		assertFalse(panel.busy());
		assertFalse(panel.playButton().isEnabled());
		assertTrue(panel.retryButton().isEnabled());
		assertTrue(panel.firstRunShown());
		assertEquals(Screen.FIRST_RUN_LINE, panel.firstRunLabel().getText());
		assertFalse(panel.noticeArea().isVisible(), "no notice, no box");

		panel.render(view(Screen.Phase.DOWNLOADING, "Updating the game client for the server…", "Revision 241",
			true, false, false, "Back up at nine.", false));
		assertTrue(panel.busy());
		assertFalse(panel.playButton().isEnabled());
		assertFalse(panel.retryButton().isEnabled());
		assertFalse(panel.firstRunShown());
		assertTrue(panel.noticeArea().isVisible());
		assertEquals("Back up at nine.", panel.noticeArea().getText());

		panel.render(view(Screen.Phase.READY, "Ready to play.", "", false, true, false, "", false));
		assertTrue(panel.playButton().isEnabled());
		assertFalse(panel.busy());
		assertEquals(" ", panel.detailLabel().getText(), "an empty line keeps its height");
	}

	@Test
	public void aViewThatNeedsMoreRoomGrowsTheWindowAndNeverShrinksIt()
	{
		assertEquals(new Dimension(500, 200), LauncherPanel.roomFor(new Dimension(400, 200), new Dimension(500, 150)));
		assertEquals(new Dimension(400, 260), LauncherPanel.roomFor(new Dimension(400, 200), new Dimension(380, 260)));
		assertEquals(new Dimension(400, 200), LauncherPanel.roomFor(new Dimension(400, 200), new Dimension(300, 100)),
			"a window the friend enlarged keeps its size");

		LauncherPanel panel = new LauncherPanel(() -> {}, () -> {}, () -> {});
		panel.render(view(Screen.Phase.CHECKING, "Checking the server…", "", true, false, false, "", false));
		int plain = panel.getPreferredSize().width;
		panel.render(view(Screen.Phase.CHECKING, "Checking the server…", "", true, false, false, "", true));
		assertTrue(panel.getPreferredSize().width > plain, "the first-run line asks for its full width, unclipped");
		assertTrue(panel.getPreferredSize().width >= panel.firstRunLabel().getPreferredSize().width);
	}

	@Test
	public void theButtonsCallBackOnlyWhileEnabled()
	{
		AtomicInteger plays = new AtomicInteger();
		AtomicInteger retries = new AtomicInteger();
		LauncherPanel panel = new LauncherPanel(plays::incrementAndGet, retries::incrementAndGet, () -> {});
		panel.playButton().doClick();
		panel.retryButton().doClick();
		assertEquals(0, plays.get() + retries.get(), "nothing before the first view");

		panel.render(view(Screen.Phase.READY, "Ready to play.", "", false, true, false, "", false));
		panel.playButton().doClick();
		panel.retryButton().doClick();
		assertEquals(1, plays.get());
		assertEquals(0, retries.get());

		panel.render(view(Screen.Phase.STOPPED, "The server's key changed. Please tell Sam.", "", false, false, true,
			"", false));
		panel.playButton().doClick();
		panel.retryButton().doClick();
		assertEquals(1, plays.get());
		assertEquals(1, retries.get());
	}

	@Test
	public void theReportButtonIsAlwaysThereAndSaysCopiedForAMoment()
	{
		AtomicInteger reports = new AtomicInteger();
		LauncherPanel panel = new LauncherPanel(() -> {}, () -> {}, reports::incrementAndGet);
		assertTrue(panel.reportButton().isEnabled(), "a report helps before the first view too");
		panel.reportButton().doClick();
		assertEquals(1, reports.get());
		panel.render(view(Screen.Phase.STOPPED, "The game could not be started.", "no java", false, false, true, "",
			false));
		assertTrue(panel.reportButton().isEnabled());
		assertEquals(LauncherPanel.REPORT_LABEL, panel.reportButton().getText());
		panel.reportCopied(true);
		assertEquals(LauncherPanel.COPIED_LABEL, panel.reportButton().getText());
		panel.reportCopied(false);
		assertEquals(LauncherPanel.NOT_COPIED_LABEL, panel.reportButton().getText());
		assertTrue(panel.reportButton().isEnabled(), "and it can be tried again");
	}

	@Test
	public void noTextIsEverRenderedAsHtml()
	{
		LauncherPanel panel = new LauncherPanel(() -> {}, () -> {}, () -> {});
		String markup = "<html><b>Down</b> tonight</html>";
		panel.render(view(Screen.Phase.STOPPED, markup, markup, false, false, true, markup, false));
		for (JLabel label : new JLabel[]{panel.statusLabel(), panel.detailLabel(), panel.firstRunLabel()})
		{
			assertEquals(Boolean.TRUE, label.getClientProperty(LauncherPanel.HTML_OFF));
			assertNull(label.getClientProperty("html"), "Swing made no HTML view for " + label.getText());
		}
		assertEquals(markup, panel.statusLabel().getText());

		JLabel control = new JLabel(markup);
		assertNotNull(control.getClientProperty("html"), "a plain label would have rendered it");

		JTextArea notice = panel.noticeArea();
		assertEquals(markup, notice.getText());
		assertTrue(notice.getDocument() instanceof PlainDocument, "a text area holds plain text only");
		assertFalse(notice.isEditable());
	}
}
