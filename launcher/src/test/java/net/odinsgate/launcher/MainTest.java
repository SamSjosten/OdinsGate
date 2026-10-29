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

/** The launcher's entry point up to the run itself: the command line, the manifest file, this JVM's pieces. */
public class MainTest
{
	@TempDir
	Path dir;

	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

	private String output()
	{
		return outBytes.toString(StandardCharsets.UTF_8);
	}

	@Test
	public void aManifestIsNeededForNow()
	{
		assertEquals(Main.EXIT_USAGE, Main.run(new String[]{"--auto"}, out));
		assertTrue(output().contains("--manifest"), output());
		assertTrue(output().contains(LaunchOptions.USAGE), output());
	}

	@Test
	public void aBadArgumentIsAUsageError()
	{
		assertEquals(Main.EXIT_USAGE, Main.run(new String[]{"--what"}, out));
		assertTrue(output().contains("--what"), output());
	}

	@Test
	public void aManifestThatIsNotThereIsNamed()
	{
		Path missing = dir.resolve("none.json");
		assertEquals(Main.EXIT_MANIFEST, Main.run(new String[]{"--manifest", missing.toString()}, out));
		assertTrue(output().contains(missing.toString()), output());
	}

	@Test
	public void aManifestThatDoesNotParseIsNamedWithItsFault() throws Exception
	{
		Path bad = dir.resolve("bad.json");
		Files.writeString(bad, "{ \"version\": \"0.1.0\" }");
		assertEquals(Main.EXIT_MANIFEST, Main.run(new String[]{"--manifest", bad.toString()}, out));
		assertTrue(output().contains("format"), output());
	}

	@Test
	public void thisJvmIsTheRuntimeAndTheClassPath()
	{
		Launch.Installed installed = Main.thisJvm();
		assertEquals(Path.of(System.getProperty("java.home")), installed.runtimeDir());
		assertFalse(installed.loaderClasspath().isEmpty());
		assertTrue(installed.loaderClasspath().stream().allMatch(p -> !p.toString().isBlank()));
	}

	@Test
	public void theVersionIsNeverEmpty()
	{
		assertFalse(Main.VERSION.isBlank());
	}
}
