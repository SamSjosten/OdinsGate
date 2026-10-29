package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The status file the VM's scripts wait on: whole, small, and never anything a report shouldn't carry. */
public class StatusFileTest
{
	private static final Instant AT = Instant.parse("2026-10-29T16:20:00Z");

	@TempDir
	Path dir;

	@Test
	public void aStatusRoundTripsWithEveryField() throws Exception
	{
		Path file = dir.resolve("status").resolve("status.json");
		StatusFile.Status status = new StatusFile.Status("0.1.0", StatusFile.State.RUNNING, "revision 241, pin 1.13.4",
			241, 4321L, AT);
		StatusFile.write(file, status);
		assertEquals(status, StatusFile.read(file));
		String json = Files.readString(file);
		assertTrue(json.contains("\"state\": \"RUNNING\""), json);
		assertTrue(json.contains("\"gamePid\": 4321"), json);
		assertTrue(json.contains("\"at\": \"2026-10-29T16:20:00Z\""), json);
		assertFalse(Files.exists(dir.resolve("status").resolve("status.json.tmp")));
	}

	@Test
	public void whatIsNotKnownYetIsLeftOut() throws Exception
	{
		StatusFile.Status status = new StatusFile.Status("0.1.0", StatusFile.State.STARTING, null, null, null, AT);
		String json = StatusFile.toJson(status);
		assertFalse(json.contains("detail"), json);
		assertFalse(json.contains("revision"), json);
		assertFalse(json.contains("gamePid"), json);
		assertEquals(status, StatusFile.fromJson(json));
	}

	@Test
	public void eachWriteReplacesTheLastWhole() throws Exception
	{
		Path file = dir.resolve("status.json");
		StatusFile.write(file, new StatusFile.Status("0.1.0", StatusFile.State.LAUNCHING, "long detail here", 241,
			null, AT));
		StatusFile.write(file, new StatusFile.Status("0.1.0", StatusFile.State.FAILED, null, null, null, AT));
		StatusFile.Status read = StatusFile.read(file);
		assertEquals(StatusFile.State.FAILED, read.state());
		assertEquals(null, read.detail());
		assertEquals(null, read.revision());
	}

	@Test
	public void aFileThatIsNotAStatusIsAnIOExceptionNamingWhy() throws Exception
	{
		for (String bad : List.of(
			"not json",
			"[]",
			"{ \"state\": \"RUNNING\", \"at\": \"2026-10-29T16:20:00Z\" }",
			"{ \"version\": \"0.1.0\", \"state\": \"DANCING\", \"at\": \"2026-10-29T16:20:00Z\" }",
			"{ \"version\": \"0.1.0\", \"state\": \"RUNNING\", \"at\": \"yesterday\" }",
			"{ \"version\": \"0.1.0\", \"state\": \"RUNNING\", \"at\": \"2026-10-29T16:20:00Z\", \"gamePid\": {} }"))
		{
			IOException refused = assertThrows(IOException.class, () -> StatusFile.fromJson(bad), bad);
			assertTrue(refused.getMessage().startsWith("the status file"), refused.getMessage());
		}
	}
}
