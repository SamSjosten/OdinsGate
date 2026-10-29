package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

		Path gameLog = dir.resolve("game.log");
		Files.writeString(gameLog, "fine\npassword=hunter2\nalso fine\n");
		String report = ProblemReport.build(MAC, Map.of(), "dev", 241, PIN,
			List.of("2026-10-29T19:29:00Z note: the password is hunter2"), dir, gameLog, dir.resolve("none"), AT);
		assertFalse(report.contains("hunter2"), report);
		assertTrue(report.contains("fine\n" + ProblemReport.LEFT_OUT + "\nalso fine\n"), report);
	}
}
