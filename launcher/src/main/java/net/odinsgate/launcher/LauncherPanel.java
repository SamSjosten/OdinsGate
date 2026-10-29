package net.odinsgate.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * The launcher window's one panel, a thin view of a {@link Screen.View}: the status line, the line under it, a
 * progress bar that moves while the launcher is busy, the notice, the first-run line, the Play and Retry buttons
 * and "Copy problem report". Every text is shown as it is: the labels have Swing's HTML rendering switched off and
 * the notice is a text area, which never interprets markup, so a notice or a server detail that holds
 * {@code <html>} shows the tag. Nothing here decides anything; {@link WindowRun} does, and calls {@link #render}
 * on the window's thread.
 */
public final class LauncherPanel extends JPanel
{
	/** Switches Swing's HTML rendering off for a label: its text is shown as it is. */
	static final String HTML_OFF = "html.disable";

	private final JLabel status = plain(new JLabel(" "));
	private final JLabel detail = plain(new JLabel(" "));
	private final JProgressBar progress = new JProgressBar();
	private final JTextArea notice = new JTextArea();
	private final JLabel firstRun = plain(new JLabel(Screen.FIRST_RUN_LINE));
	private final JButton play = new JButton("Play");
	private final JButton retry = new JButton("Retry");
	private final JButton report = new JButton(REPORT_LABEL);
	private final Timer reportLabelBack =
		new Timer(COPIED_FOR_MILLIS, event -> report.setText(REPORT_LABEL));

	static final String REPORT_LABEL = "Copy problem report";
	static final String COPIED_LABEL = "Copied";
	static final String NOT_COPIED_LABEL = "Couldn't copy, try again";
	static final int COPIED_FOR_MILLIS = 3000;

	/**
	 * @param onPlay what the Play button does
	 * @param onRetry what the Retry button does
	 * @param onCopyReport what the "Copy problem report" button does
	 */
	public LauncherPanel(Runnable onPlay, Runnable onRetry, Runnable onCopyReport)
	{
		super(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

		status.setFont(status.getFont().deriveFont(Font.BOLD, status.getFont().getSize2D() * 1.3f));
		progress.setPreferredSize(new Dimension(360, progress.getPreferredSize().height));
		progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, progress.getPreferredSize().height));

		notice.setEditable(false);
		notice.setFocusable(false);
		notice.setLineWrap(true);
		notice.setWrapStyleWord(true);
		notice.setOpaque(false);
		notice.setFont(detail.getFont());
		notice.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
			BorderFactory.createEmptyBorder(8, 10, 8, 10)));
		notice.setVisible(false);

		firstRun.setFont(detail.getFont().deriveFont(Font.ITALIC));
		firstRun.setVisible(false);

		play.setEnabled(false);
		retry.setEnabled(false);
		play.addActionListener(event -> onPlay.run());
		retry.addActionListener(event -> onRetry.run());
		report.addActionListener(event -> onCopyReport.run());
		reportLabelBack.setRepeats(false);

		JPanel lines = new JPanel();
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		for (Component line : new Component[]{status, detail, progress, notice, firstRun})
		{
			((javax.swing.JComponent) line).setAlignmentX(LEFT_ALIGNMENT);
			lines.add(line);
			lines.add(Box.createVerticalStrut(8));
		}
		add(lines, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new BorderLayout());
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		left.add(report);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		right.add(retry);
		right.add(play);
		buttons.add(left, BorderLayout.WEST);
		buttons.add(right, BorderLayout.EAST);
		add(buttons, BorderLayout.SOUTH);
	}

	/** The report is on the clipboard, or couldn't be put there: the button says which for a moment. */
	public void reportCopied(boolean copied)
	{
		report.setText(copied ? COPIED_LABEL : NOT_COPIED_LABEL);
		reportLabelBack.restart();
	}

	/** Draws {@code view}; on the window's thread. */
	public void render(Screen.View view)
	{
		status.setText(view.status().isEmpty() ? " " : view.status());
		detail.setText(view.detail().isEmpty() ? " " : view.detail());
		progress.setIndeterminate(view.busy());
		if (!view.busy())
		{
			boolean done = view.canPlay() || view.phase() == Screen.Phase.RUNNING;
			progress.setValue(done ? progress.getMaximum() : progress.getMinimum());
		}
		notice.setText(view.notice());
		notice.setVisible(!view.notice().isEmpty());
		firstRun.setVisible(view.firstRun());
		play.setEnabled(view.canPlay());
		retry.setEnabled(view.canRetry());
		revalidate();
		repaint();
		makeRoom();
	}

	/**
	 * Grows the window around this panel when a view needs more than the window has: the notice and the first-run
	 * line appear after the window was packed, and a long detail line would otherwise be clipped. The window is
	 * never made smaller, so a window the friend enlarged stays that way.
	 */
	private void makeRoom()
	{
		Window window = SwingUtilities.getWindowAncestor(this);
		if (window == null)
		{
			return;
		}
		Dimension has = window.getSize();
		Dimension needs = roomFor(has, window.getPreferredSize());
		if (!needs.equals(has))
		{
			window.setSize(needs);
			window.validate();
		}
	}

	/** The size a window of {@code has} needs to show {@code wants}: each side the larger of the two. */
	static Dimension roomFor(Dimension has, Dimension wants)
	{
		return new Dimension(Math.max(has.width, wants.width), Math.max(has.height, wants.height));
	}

	JButton playButton()
	{
		return play;
	}

	JButton retryButton()
	{
		return retry;
	}

	JButton reportButton()
	{
		return report;
	}

	JLabel statusLabel()
	{
		return status;
	}

	JLabel detailLabel()
	{
		return detail;
	}

	/** The notice's text area, visible only while there is a notice. */
	JTextArea noticeArea()
	{
		return notice;
	}

	JLabel firstRunLabel()
	{
		return firstRun;
	}

	boolean firstRunShown()
	{
		return firstRun.isVisible();
	}

	boolean busy()
	{
		return progress.isIndeterminate();
	}

	private static JLabel plain(JLabel label)
	{
		label.putClientProperty(HTML_OFF, Boolean.TRUE);
		return label;
	}
}
