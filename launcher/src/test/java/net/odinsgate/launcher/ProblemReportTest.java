package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The problem report's text: what it carries, what it says when a log isn't there, and what it never carries. */
public class ProblemReportTest
{
	private static final Platform MAC = new Platform(Platform.Os.MAC, Platform.Arch.AARCH64);
	private static final Instant AT = Instant.parse("2026-10-29T19:30:00Z");
	private static final Manifest.Pin PIN = new Manifest.Pin(241, "1.13.4", ManifestTest.COMMIT, ManifestTest.SHA256);

	@TempDir
	Path dir;

	@Test
	public void theReportNamesTheMachineTheVersionThePinAndTheLastSteps() throws Exception
	{
		Path gameLog = dir.resolve("game.log");
		Files.writeString(gameLog, "[loader] 31 jars\nthe game drew a frame\n");
		String report = ProblemReport.build(MAC, Map.of("os.name", "Mac OS X", "os.version", "15.1",
				"java.version", "21.0.5", "java.vendor", "Eclipse Adoptium"), "0.1.0", 241, PIN,
			List.of("2026-10-29T19:29:00Z STARTING: launcher 0.1.0, data in " + dir,
				"2026-10-29T19:29:01Z CHECKING: odinsgate.net"), dir, gameLog, dir.resolve("client.log"), AT);
		assertTrue(report.startsWith("Odin's Gate problem report, 2026-10-29T19:30:00Z\n"), report);
		assertTrue(report.contains("launcher 0.1.0\n"), report);
		assertTrue(report.contains("system mac/aarch64, Mac OS X 15.1, java 21.0.5 (Eclipse Adoptium)\n"), report);
		assertTrue(report.contains("revision 241, pin RuneLite 1.13.4 at " + ManifestTest.COMMIT + "\n"), report);
		assertTrue(report.contains("== last steps and checks ==\n2026-10-29T19:29:00Z STARTING: launcher 0.1.0, data in"
			+ " <data>\n"), report);
		assertFalse(report.contains(dir.toString()), "the data folder, with the user's name in it, is hidden");
		assertTrue(report.contains("[loader] 31 jars\nthe game drew a frame\n"), report);
		assertTrue(report.contains("== client log (the last 60 lines) ==\n(no such log yet)\n"), report);
	}

	@Test
	public void beforeAnyCheckTheReportSaysSo()
	{
		String report = ProblemReport.build(MAC, Map.of(), "dev", null, null, List.of(), dir, dir.resolve("none"),
			dir.resolve("none2"), AT);
		assertTrue(report.contains("revision not seen yet\n"), report);
		assertFalse(report.contains("pin"), report);
		assertTrue(report.contains("(none yet)"), report);
		assertTrue(report.contains("system mac/aarch64, ? ?, java ? (?)"), report);
	}

	@Test
	public void onlyTheEndOfALongLogGoesIn() throws Exception
	{
		Path log = dir.resolve("long.log");
		Files.write(log, IntStream.rangeClosed(1, 200).mapToObj(i -> "line " + i).toList());
		List<String> tail = ProblemReport.tail(log, ProblemReport.TAIL_LINES);
		assertEquals(ProblemReport.TAIL_LINES, tail.size());
		assertEquals("line 141", tail.get(0));
		assertEquals("line 200", tail.get(tail.size() - 1));
		assertNull(ProblemReport.tail(dir.resolve("missing.log"), 10));
		String report = ProblemReport.build(MAC, Map.of(), "dev", null, null, List.of(), dir, log, log, AT);
		assertFalse(report.contains("line 140\n"), report);
		assertTrue(report.contains("line 141\n"), report);
	}

	@Test
	public void aHugeLogIsReadFromItsEndOnly() throws Exception
	{
		Path log = dir.resolve("huge.log");
		String padding = "x".repeat(90);
		Files.write(log, IntStream.rangeClosed(1, 5000).mapToObj(i -> "line " + i + " " + padding).toList());
		assertTrue(Files.size(log) > 4 * ProblemReport.TAIL_BYTES, "well past what is read");
		List<String> tail = ProblemReport.tail(log, ProblemReport.TAIL_LINES);
		assertEquals(ProblemReport.TAIL_LINES, tail.size());
		assertTrue(tail.get(0).startsWith("line 4941 "), tail.get(0));
		assertTrue(tail.get(tail.size() - 1).startsWith("line 5000 "), tail.get(tail.size() - 1));
		assertTrue(tail.stream().allMatch(line -> line.startsWith("line ") && line.endsWith(padding)),
			"every line whole, none cut where the reading began");
	}

	@Test
	public void aLogThatIsNotCleanUtf8StillShowsItsLines() throws Exception
	{
		Path log = dir.resolve("game.log");
		Files.write(log, "fine\n\u00ff\u00fe not utf-8 \n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		List<String> tail = ProblemReport.tail(log, 10);
		assertEquals(2, tail.size());
		assertEquals("fine", tail.get(0));
		assertTrue(tail.get(1).contains("not utf-8"), tail.get(1));
	}

	@Test
	public void aLineThatMayHoldAPasswordIsLeftOut() throws Exception
	{
		for (String line : List.of("password=hunter2", "-Dpassword hunter2", "\"Password\": \"hunter2\"",
			"passwd: hunter2", "client_secret=hunter2", "token hunter2", "Authorization: Bearer hunter2"))
		{
			assertEquals(ProblemReport.LEFT_OUT, ProblemReport.scrub(line, ""), line);
		}
		assertEquals("logged in as test", ProblemReport.scrub("logged in as test", ""));
		assertEquals("its log in <data>/logs/game.log", ProblemReport.scrub("its log in /home/sam/data/logs/game.log",
			"/home/sam/data"));
		assertEquals("<data>/logs/game.log: Permission denied",
			ProblemReport.scrub("/home/sam/data/logs/game.log: Permission denied", "/home/sam/data"),
			"a read failure's message carries the path and is scrubbed like a line");

		Path gameLog = dir.resolve("game.log");
		Files.writeString(gameLog, "fine\npassword=hunter2\nalso fine\n");
		String report = ProblemReport.build(MAC, Map.of(), "dev", 241, PIN,
			List.of("2026-10-29T19:29:00Z note: the password is hunter2"), dir, gameLog, dir.resolve("none"), AT);
		assertFalse(report.contains("hunter2"), report);
		assertTrue(report.contains("fine\n" + ProblemReport.LEFT_OUT + "\nalso fine\n"), report);
	}

	@Test
	public void whatTheFriendWroteGoesAfterTheHeaderScrubbedAndCut()
	{
		Path data = Path.of("/home/jane/.local/share/OdinsGate");
		String report = "Odin's Gate problem report, now\nlauncher 1.0.5\nrevision 241\n\n== last steps and checks ==\nstep\n";
		assertEquals(report, ProblemReport.withWhatHappened(report, "  \n ", data), "nothing written, nothing added");
		String with = ProblemReport.withWhatHappened(report,
			"it froze\nmy password is hunter2\nsee " + data + "/logs", data);
		assertEquals("Odin's Gate problem report, now\nlauncher 1.0.5\nrevision 241\n\n" + ProblemReport.WHAT_HAPPENED
			+ "\nit froze\n" + ProblemReport.LEFT_OUT + "\nsee <data>/logs\n\n== last steps and checks ==\nstep\n", with);
		String longer = ProblemReport.withWhatHappened(report, "x".repeat(ProblemReport.WHAT_HAPPENED_CHARS + 50), data);
		assertTrue(longer.contains("x".repeat(ProblemReport.WHAT_HAPPENED_CHARS) + "\n\n=="), longer.length() + "");
		// A cut that would fall inside the data folder's path comes after the scrub, so no piece of the name is left.
		String split = ProblemReport.withWhatHappened(report,
			"x".repeat(ProblemReport.WHAT_HAPPENED_CHARS - 10) + data + "/logs", data);
		assertFalse(split.contains("/home/jan"), split);
		// Another home folder the friend pastes loses its name too.
		String pasted = ProblemReport.withWhatHappened(report,
			"it failed opening /home/jane/Downloads/OdinsGate and C:\\Users\\Jane Doe\\Desktop", data);
		assertTrue(pasted.contains("it failed opening /home/<user>/Downloads/OdinsGate and C:\\Users\\<user>\\Desktop\n"),
			pasted);
		// A cut that would fall inside an emoji keeps it whole or leaves it out, so the report can be written as UTF-8.
		String emoji = ProblemReport.withWhatHappened(report, "x".repeat(ProblemReport.WHAT_HAPPENED_CHARS - 1) + "😀", data);
		assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(emoji), "no half of a character is left");
	}
}
