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
	public void theLauncherLogsUnderItsDataFolderAndStillToTheConsole() throws Exception
	{
		Path data = dir.resolve("data");
		try (PrintStream log = Main.openLog(data, out))
		{
			log.println("[launcher] STARTING: here");
		}
		Path file = data.resolve("logs").resolve("launcher.log");
		assertEquals("[launcher] STARTING: here" + System.lineSeparator(), Files.readString(file));
		assertTrue(output().contains("STARTING: here"), output());
		out.println("still open");
		assertTrue(output().contains("still open"), "closing the log leaves the console alone");

		try (PrintStream log = Main.openLog(data, out))
		{
			log.println("second run");
		}
		assertTrue(Files.readString(file).contains("here"), "the log is appended to across runs");
		assertTrue(Files.readString(file).contains("second run"));
	}

	@Test
	public void aDataFolderThatCannotHoldALogLeavesTheConsoleAsTheLog() throws Exception
	{
		Path blocked = dir.resolve("blocked");
		Files.writeString(blocked, "a file where the folder should be");
		try (PrintStream log = Main.openLog(blocked, out))
		{
			log.println("[launcher] CHECKING: anyway");
		}
		assertTrue(output().contains("could not be opened"), output());
		assertTrue(output().contains("CHECKING: anyway"), output());
	}

	@Test
	public void theVersionIsNeverEmpty()
	{
		assertFalse(Main.VERSION.isBlank());
	}
}
