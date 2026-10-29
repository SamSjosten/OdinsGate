package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The logs move aside when large and never pile up past the kept count; the launcher's log reaches the console too. */
public class RotatingLogTest
{
	@TempDir
	Path dir;

	@Test
	public void aLargeLogMovesAsideAndTheOldestIsDropped() throws Exception
	{
		Path log = dir.resolve("launcher.log");
		Files.writeString(log, "run 1 xxxxxxxxxx");
		RotatingLog.rotate(log, 100, 2);
		assertTrue(Files.exists(log), "under the limit, nothing moves");

		for (int run = 1; run <= 4; run++)
		{
			Files.writeString(log, "run " + run + " " + "x".repeat(100));
			RotatingLog.rotate(log, 100, 2);
			assertFalse(Files.exists(log), "run " + run + " moved aside");
			assertTrue(Files.readString(RotatingLog.aside(log, 1)).startsWith("run " + run));
			if (run >= 2)
			{
				assertTrue(Files.readString(RotatingLog.aside(log, 2)).startsWith("run " + (run - 1)));
			}
			assertFalse(Files.exists(RotatingLog.aside(log, 3)), "never more than the kept count");
		}
	}

	@Test
	public void theLauncherLogAppendsRotatesAndReachesTheConsoleToo() throws Exception
	{
		Path log = dir.resolve("logs").resolve("launcher.log");
		ByteArrayOutputStream consoleBytes = new ByteArrayOutputStream();
		PrintStream console = new PrintStream(consoleBytes, true, StandardCharsets.UTF_8);
		try (PrintStream out = RotatingLog.open(log, console))
		{
			out.println("first");
		}
		try (PrintStream out = RotatingLog.open(log, console))
		{
			out.println("second");
		}
		assertEquals("first" + System.lineSeparator() + "second" + System.lineSeparator(), Files.readString(log));
		assertEquals("first" + System.lineSeparator() + "second" + System.lineSeparator(),
			consoleBytes.toString(StandardCharsets.UTF_8));
		console.println("after");
		assertTrue(consoleBytes.toString(StandardCharsets.UTF_8).endsWith("after" + System.lineSeparator()),
			"closing the log leaves the console open");

		Files.writeString(log, "x".repeat((int) RotatingLog.MAX_BYTES));
		try (PrintStream out = RotatingLog.open(log, console))
		{
			out.println("fresh");
		}
		assertEquals("fresh" + System.lineSeparator(), Files.readString(log));
		assertEquals(RotatingLog.MAX_BYTES, Files.size(RotatingLog.aside(log, 1)));
	}
}
