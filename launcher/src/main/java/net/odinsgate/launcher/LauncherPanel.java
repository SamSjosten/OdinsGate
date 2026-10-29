package net.odinsgate.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * The launcher window's one panel, a thin view of a {@link Screen.View}: the status line, the line under it, a
 * progress bar that moves while the launcher is busy, the notice, the first-run line, and the Play and Retry
 * buttons. Every text is shown as it is: the labels have Swing's HTML rendering switched off and the notice is a
 * text area, which never interprets markup, so a notice or a server detail that holds {@code <html>} shows the
 * tag. Nothing here decides anything; {@link WindowRun} does, and calls {@link #render} on the window's thread.
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

	/**
	 * @param onPlay what the Play button does
	 * @param onRetry what the Retry button does
	 */
	public LauncherPanel(Runnable onPlay, Runnable onRetry)
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

		JPanel lines = new JPanel();
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		for (Component line : new Component[]{status, detail, progress, notice, firstRun})
		{
			((javax.swing.JComponent) line).setAlignmentX(LEFT_ALIGNMENT);
			lines.add(line);
			lines.add(Box.createVerticalStrut(8));
		}
		add(lines, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(retry);
		buttons.add(play);
		add(buttons, BorderLayout.SOUTH);
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
	}

	JButton playButton()
	{
		return play;
	}

	JButton retryButton()
	{
		return retry;
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
