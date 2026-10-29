package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A downloaded version's jars run in this JVM through their own class loader, and come back with an exit code. */
public class VersionLoaderTest
{
	@TempDir
	Path dir;

	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

	private Path version(String name, byte[] jar) throws IOException
	{
		Path lib = dir.resolve(name).resolve(VersionLoader.LIB_DIR);
		Files.createDirectories(lib);
		Files.write(lib.resolve("odinsgate-launcher-" + name + ".jar"), jar);
		return dir.resolve(name);
	}

	@Test
	public void theVersionsMainRunsWithTheArgumentsAndItsCodeComesBack() throws Exception
	{
		Path version = version("0.2.0", FakeVersion.jar("fake 0.2.0", 42));
		ClassLoader before = Thread.currentThread().getContextClassLoader();
		int code = VersionLoader.run(version, new String[]{"--auto", "--manifest", "m.json"}, out);
		assertEquals(42, code);
		assertEquals("fake 0.2.0: --auto --manifest m.json\n", outBytes.toString(StandardCharsets.UTF_8));
		assertSame(before, Thread.currentThread().getContextClassLoader(), "the context loader is put back");
	}

	@Test
	public void theVersionsClassesAreItsOwnNotTheShells() throws Exception
	{
		// The real Main in this JVM has a run that needs a manifest; the fake one returns 42 whatever it is given,
		// so getting 42 proves the version's Main ran, not the shell's.
		Path version = version("0.3.0", FakeVersion.jar("own", 42));
		assertEquals(42, VersionLoader.run(version, new String[]{}, out));
	}

	@Test
	public void aVersionWithoutAMainCannotRun() throws Exception
	{
		Path version = version("0.4.0", FakeVersion.emptyJar());
		IOException refused = assertThrows(IOException.class,
			() -> VersionLoader.run(version, new String[]{}, out));
		assertTrue(refused.getMessage().contains("has no " + VersionLoader.MAIN_CLASS), refused.getMessage());
	}

	@Test
	public void aVersionWithoutJarsCannotRun() throws Exception
	{
		Path version = dir.resolve("0.5.0");
		Files.createDirectories(version.resolve(VersionLoader.LIB_DIR));
		IOException refused = assertThrows(IOException.class,
			() -> VersionLoader.run(version, new String[]{}, out));
		assertTrue(refused.getMessage().contains("no jars under"), refused.getMessage());
	}

	@Test
	public void theJarsAreListedInNameOrder() throws Exception
	{
		Path lib = dir.resolve("0.6.0").resolve(VersionLoader.LIB_DIR);
		Files.createDirectories(lib);
		for (String name : List.of("zed.jar", "asm.jar", "odinsgate-launcher-0.6.0.jar", "notes.txt"))
		{
			Files.writeString(lib.resolve(name), "x");
		}
		List<String> names = VersionLoader.jars(dir.resolve("0.6.0")).stream()
			.map(path -> path.getFileName().toString()).toList();
		assertEquals(List.of("asm.jar", "odinsgate-launcher-0.6.0.jar", "zed.jar"), names);
	}
}
