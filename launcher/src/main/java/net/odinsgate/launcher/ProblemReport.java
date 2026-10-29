package net.odinsgate.launcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The text behind "Copy problem report": what Sam needs to see what went wrong on a friend's machine, in one paste.
 * The system and chip, the launcher's version, the server's revision and the pin for it, the launcher's last
 * steps and checks, and the ends of the game's two logs (the child JVM's output and RuneLite's own client log).
 * Never a password: any line of a log that mentions one is left out; the data folder, which carries the friend's
 * user name, is written as {@code <data>}; the command line is not here at all. Reading the logs is the only thing
 * that touches a file; a log that isn't there is said so.
 */
final class ProblemReport
{
	/** How many lines of each log go into the report. */
	static final int TAIL_LINES = 60;
	/** How much of a log's end is read for them: a game that ran all day has a log far past the rotation size. */
	static final int TAIL_BYTES = 64 * 1024;
	/** A line matching this is replaced whole; the words RuneLite's and our own logs could carry a secret under. */
	static final Pattern SECRET =
		Pattern.compile("password|passwd|secret|token|authorization", Pattern.CASE_INSENSITIVE);
	static final String LEFT_OUT = "[a line that may hold a password was left out]";

	private ProblemReport()
	{
	}

	/**
	 * @param platform the system and chip
	 * @param system {@code os.name}, {@code os.version}, {@code java.version} and {@code java.vendor}, as known
	 * @param version the launcher's version
	 * @param revision the server's revision as last seen, or null
	 * @param pin the pin for that revision, or null
	 * @param recent the launcher's last steps and checks, newest last
	 * @param dataDir the launcher's data folder, which the lines name and the report hides as {@code <data>}
	 * @param gameLog the child JVM's log
	 * @param clientLog RuneLite's own log under the game's home
	 * @param now when the report was made
	 */
	static String build(Platform platform, Map<String, String> system, String version, Integer revision,
		Manifest.Pin pin, List<String> recent, Path dataDir, Path gameLog, Path clientLog, Instant now)
	{
		String folder = dataDir.toString();
		StringBuilder report = new StringBuilder();
		report.append(Names.APP_NAME).append(" problem report, ").append(now).append('\n');
		report.append("launcher ").append(version).append('\n');
		report.append("system ").append(platform.os().name().toLowerCase(Locale.ROOT)).append('/')
			.append(platform.arch().name().toLowerCase(Locale.ROOT))
			.append(", ").append(system.getOrDefault("os.name", "?")).append(' ')
			.append(system.getOrDefault("os.version", "?"))
			.append(", java ").append(system.getOrDefault("java.version", "?"))
			.append(" (").append(system.getOrDefault("java.vendor", "?")).append(")\n");
		report.append("revision ").append(revision == null ? "not seen yet" : revision.toString());
		if (pin != null)
		{
			report.append(", pin RuneLite ").append(pin.runelite()).append(" at ").append(pin.commit());
		}
		report.append('\n');
		report.append("\n== last steps and checks ==\n");
		if (recent.isEmpty())
		{
			report.append("(none yet)\n");
		}
		for (String line : recent)
		{
			report.append(scrub(line, folder)).append('\n');
		}
		appendTail(report, "game log (the last " + TAIL_LINES + " lines)", gameLog, folder);
		appendTail(report, "client log (the last " + TAIL_LINES + " lines)", clientLog, folder);
		return report.toString();
	}

	private static void appendTail(StringBuilder report, String title, Path log, String folder)
	{
		report.append("\n== ").append(title).append(" ==\n");
		List<String> lines;
		try
		{
			lines = tail(log, TAIL_LINES);
		}
		catch (IOException e)
		{
			// The message often carries the file's whole path, so it goes through the same scrub as a line.
			String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			report.append("(could not be read: ").append(scrub(why, folder)).append(")\n");
			return;
		}
		if (lines == null)
		{
			report.append("(no such log yet)\n");
			return;
		}
		if (lines.isEmpty())
		{
			report.append("(empty)\n");
		}
		for (String line : lines)
		{
			report.append(scrub(line, folder)).append('\n');
		}
	}

	/**
	 * The last {@code count} lines of {@code file}, or null when there is no such file. Only the file's last
	 * {@link #TAIL_BYTES} are read, whatever its size, and a line cut by that edge is left out. The bytes are read
	 * as UTF-8 with anything else shown as the replacement character, since a game's console output isn't always
	 * clean UTF-8 and a report must still show the rest.
	 */
	static List<String> tail(Path file, int count) throws IOException
	{
		if (!Files.isRegularFile(file))
		{
			return null;
		}
		byte[] end;
		boolean whole;
		try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ))
		{
			long size = channel.size();
			long from = Math.max(0, size - TAIL_BYTES);
			whole = from == 0;
			channel.position(from);
			ByteBuffer buffer = ByteBuffer.allocate((int) (size - from));
			while (buffer.hasRemaining() && channel.read(buffer) >= 0)
			{
				// Until the buffer is full or the file ends.
			}
			end = Arrays.copyOf(buffer.array(), buffer.position());
		}
		List<String> lines = new String(end, StandardCharsets.UTF_8).lines().toList();
		if (!whole && !lines.isEmpty())
		{
			// The first line most likely began before the bytes read did.
			lines = lines.subList(1, lines.size());
		}
		return new ArrayList<>(lines.subList(Math.max(0, lines.size() - count), lines.size()));
	}

	/**
	 * A line as it goes into the report: whole, unless it may hold a password, then a note in its place; the data
	 * folder, which carries the friend's user name on most systems, becomes {@code <data>}.
	 */
	static String scrub(String line, String folder)
	{
		if (SECRET.matcher(line).find())
		{
			return LEFT_OUT;
		}
		return folder.isEmpty() ? line : line.replace(folder, "<data>");
	}
}
