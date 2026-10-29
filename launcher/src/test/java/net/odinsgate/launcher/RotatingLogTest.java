package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	public void aLiveLogThatCannotMoveLeavesTheOlderOnesAlone() throws Exception
	{
		Path log = dir.resolve("game.log");
		Files.writeString(RotatingLog.aside(log, 1), "run 2");
		Files.writeString(RotatingLog.aside(log, 2), "run 1");
		Files.writeString(log, "run 3 " + "x".repeat(100));
		// A folder with something in it where the live log would move first: the move fails, as it does on Windows
		// when an earlier game still holds the log open.
		Path blocked = log.resolveSibling("game.log.moving");
		Files.createDirectories(blocked);
		Files.writeString(blocked.resolve("in-the-way"), "");
		assertThrows(java.io.IOException.class, () -> RotatingLog.rotate(log, 100, 3));
		assertTrue(Files.readString(log).startsWith("run 3"), "the live log is where it was");
		assertEquals("run 2", Files.readString(RotatingLog.aside(log, 1)));
		assertEquals("run 1", Files.readString(RotatingLog.aside(log, 2)));
		assertFalse(Files.exists(RotatingLog.aside(log, 3)));

		Files.delete(blocked.resolve("in-the-way"));
		Files.delete(blocked);
		RotatingLog.rotate(log, 100, 3);
		assertFalse(Files.exists(log));
		assertFalse(Files.exists(blocked), "the moving name is only ever held for a moment");
		assertTrue(Files.readString(RotatingLog.aside(log, 1)).startsWith("run 3"));
		assertEquals("run 2", Files.readString(RotatingLog.aside(log, 2)));
		assertEquals("run 1", Files.readString(RotatingLog.aside(log, 3)));
	}

	@Test
	public void olderLogsThatCannotShiftPutTheLiveLogBack() throws Exception
	{
		Path log = dir.resolve("game.log");
		Files.writeString(RotatingLog.aside(log, 1), "run 2");
		Files.writeString(RotatingLog.aside(log, 2), "run 1");
		Files.writeString(log, "run 3 " + "x".repeat(100));
		// The oldest name taken by a folder with something in it: dropping it fails, after the live log has moved.
		Path blocked = RotatingLog.aside(log, 3);
		Files.createDirectories(blocked);
		Files.writeString(blocked.resolve("in-the-way"), "");
		assertThrows(java.io.IOException.class, () -> RotatingLog.rotate(log, 100, 3));
		assertTrue(Files.readString(log).startsWith("run 3"), "the live log is back where it was");
		assertFalse(Files.exists(log.resolveSibling("game.log.moving")), "nothing is left under the moving name");
		assertEquals("run 2", Files.readString(RotatingLog.aside(log, 1)));
		assertEquals("run 1", Files.readString(RotatingLog.aside(log, 2)));
	}

	@Test
	public void aFileThatStopsTakingWritesLosesTheConsoleNothing() throws Exception
	{
		ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
		boolean[] diskFull = new boolean[1];
		java.io.OutputStream file = new java.io.OutputStream()
		{
			@Override
			public void write(int b) throws java.io.IOException
			{
				write(new byte[]{(byte) b}, 0, 1);
			}

			@Override
			public void write(byte[] bytes, int offset, int length) throws java.io.IOException
			{
				if (diskFull[0])
				{
					throw new java.io.IOException("No space left on device");
				}
				fileBytes.write(bytes, offset, length);
			}
		};
		ByteArrayOutputStream consoleBytes = new ByteArrayOutputStream();
		try (PrintStream out = RotatingLog.tee(file, consoleBytes))
		{
			out.println("before");
			diskFull[0] = true;
			out.println("during");
			out.println("after");
			assertFalse(out.checkError(), "the file's failure is not the stream's");
		}
		String eol = System.lineSeparator();
		assertEquals("before" + eol, fileBytes.toString(StandardCharsets.UTF_8));
		assertEquals("before" + eol + "during" + eol + "after" + eol, consoleBytes.toString(StandardCharsets.UTF_8));
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
