package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The local runtime config RuneLite must always find, since it dies without one and its remote one could block us. */
public class RuntimeConfigTest
{
	@TempDir
	Path dir;

	@Test
	public void itWritesAnEmptyObjectAndMakesTheFolders() throws Exception
	{
		Path file = dir.resolve("data").resolve("rtconf.json");
		assertEquals(file, RuntimeConfig.write(file));
		assertEquals("{}\n", Files.readString(file));
		assertFalse(Files.exists(dir.resolve("data").resolve("rtconf.json.tmp")), "the temp file is gone");
	}

	@Test
	public void aStaleFileIsReplacedWhole() throws Exception
	{
		Path file = dir.resolve("rtconf.json");
		Files.writeString(file, "{ \"outdatedClientVersions\": [\"1.13.4\"] }");
		RuntimeConfig.write(file);
		assertEquals(RuntimeConfig.CONTENT, Files.readString(file));
		assertTrue(Files.isRegularFile(file));
	}
}
