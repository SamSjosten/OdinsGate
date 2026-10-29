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

	/** A version folder with one jar under lib/, as its manifest would name it. */
	private List<Path> version(String name, byte[] jar) throws IOException
	{
		return List.of(jar(name, "odinsgate-launcher-" + name + ".jar", jar));
	}

	private Path jar(String version, String name, byte[] jar) throws IOException
	{
		Path lib = dir.resolve(version).resolve("lib");
		Files.createDirectories(lib);
		Path file = lib.resolve(name);
		Files.write(file, jar);
		return file;
	}

	@Test
	public void theVersionsMainRunsWithTheArgumentsAndItsCodeComesBack() throws Exception
	{
		List<Path> version = version("0.2.0", FakeVersion.jar("fake 0.2.0", 42));
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
		List<Path> version = version("0.3.0", FakeVersion.jar("own", 42));
		assertEquals(42, VersionLoader.run(version, new String[]{}, out));
	}

	@Test
	public void aVersionWithoutAMainCannotRun() throws Exception
	{
		List<Path> version = version("0.4.0", FakeVersion.emptyJar());
		IOException refused = assertThrows(IOException.class,
			() -> VersionLoader.run(version, new String[]{}, out));
		assertTrue(refused.getMessage().contains("has no " + VersionLoader.MAIN_CLASS), refused.getMessage());
	}

	@Test
	public void aVersionWhoseMainFailsToLoadCannotRun() throws Exception
	{
		List<Path> version = version("0.5.0", FakeVersion.brokenJar());
		IOException refused = assertThrows(IOException.class,
			() -> VersionLoader.run(version, new String[]{}, out));
		assertTrue(refused.getMessage().contains("the version failed to load: java.lang.ExceptionInInitializerError"),
			refused.getMessage());
		assertTrue(refused.getCause() instanceof ExceptionInInitializerError);
	}

	@Test
	public void aVersionWithoutJarsCannotRun() throws Exception
	{
		IOException refused = assertThrows(IOException.class,
			() -> VersionLoader.run(List.of(), new String[]{}, out));
		assertTrue(refused.getMessage().contains("names no jars"), refused.getMessage());
	}

	@Test
	public void onlyTheJarsNamedAreLoadedNotEverythingUnderLib() throws Exception
	{
		// A stray jar sorts first and holds a Main of its own; it isn't named, so it is never on the class path.
		jar("0.6.0", "0-stray.jar", FakeVersion.jar("stray", 7));
		Path named = jar("0.6.0", "odinsgate-launcher-0.6.0.jar", FakeVersion.jar("named", 42));
		assertEquals(42, VersionLoader.run(List.of(named), new String[]{"x"}, out));
		assertEquals("named: x\n", outBytes.toString(StandardCharsets.UTF_8));
	}
}
