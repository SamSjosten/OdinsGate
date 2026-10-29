package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The child JVM's entry point, run in this JVM against the ASM-built fake client: it scans, refuses or patches, and
 * starts the main class with RuneLite's arguments on a thread of its own.
 */
public class LoaderTest
{
	private static final List<String> PROPERTIES =
		List.of(FakeClient.KEY_PROPERTY, FakeClient.PORT_PROPERTY, FakeClient.ARGS_PROPERTY);

	@TempDir
	Path dir;

	private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
	private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
	private final List<Throwable> failures = new CopyOnWriteArrayList<>();

	@BeforeEach
	@AfterEach
	public void clearWhatTheFakeMainRecords()
	{
		PROPERTIES.forEach(System::clearProperty);
	}

	private String logText()
	{
		return logBytes.toString(StandardCharsets.UTF_8);
	}

	@Test
	public void theArgumentsTheLauncherBuildsParseBackToTheSameOptions()
	{
		List<Path> jars = List.of(Path.of("cache", "injected-client-1.13.4.jar"),
			Path.of("cache", "client-1.13.4.jar"));
		List<String> args =
			new ArrayList<>(Loader.arguments(jars, FakeClient.OUR_MODULUS, 44594, Loader.RUNELITE_MAIN));
		args.add("--");
		args.add("--jav_config");
		args.add("http://odinsgate.net:43595/launcher/jav_config.ws");
		args.add("--disable-telemetry");
		Loader.Options options = Loader.parse(args.toArray(String[]::new));
		assertEquals(jars, options.jars());
		assertEquals(FakeClient.OUR_MODULUS, options.modulus());
		assertEquals(44594, options.gamePort());
		assertEquals(Loader.RUNELITE_MAIN, options.mainClass());
		assertEquals(
			List.of("--jav_config", "http://odinsgate.net:43595/launcher/jav_config.ws", "--disable-telemetry"),
			options.runeliteArgs());
	}

	@Test
	public void aBadCommandLineIsRefused()
	{
		String jar = Path.of("cache", "client-1.13.4.jar").toString();
		for (String[] bad : List.of(
			new String[]{},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "43594"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", "zz", "--port", "43594", "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "70000", "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "x", "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "43594", "--main"},
			new String[]{"--jar", jar, "--what", "ever", "--modulus", FakeClient.OUR_MODULUS, "--port", "1",
				"--main", "a.Main"}))
		{
			assertThrows(IllegalArgumentException.class, () -> Loader.parse(bad), String.join(" ", bad));
		}
	}

	@Test
	public void itPatchesTheClientAndStartsTheMainClassWithRuneLitesArguments() throws Exception
	{
		Loader.Options options = new Loader.Options(FakeClient.jars(dir), FakeClient.OUR_MODULUS, 44594,
			FakeClient.MAIN.replace('/', '.'), List.of("--jav_config", "http://odinsgate.net:43595/x.ws"));
		Thread thread = Loader.start(options, log, failures::add);
		thread.join(30_000);
		assertFalse(thread.isAlive(), "the fake main returned");
		assertTrue(failures.isEmpty(), failures.toString());
		assertEquals(FakeClient.OUR_MODULUS, System.getProperty(FakeClient.KEY_PROPERTY), "the key was patched");
		assertEquals("44594", System.getProperty(FakeClient.PORT_PROPERTY), "the port was patched");
		assertEquals("--jav_config http://odinsgate.net:43595/x.ws", System.getProperty(FakeClient.ARGS_PROPERTY));
		assertEquals("RuneLite", thread.getName());
		String text = logText();
		assertTrue(text.contains("[loader] will patch net.odinsgate.testfake.Client: "), text);
		assertTrue(text.contains("[loader] starting net.odinsgate.testfake.Main --jav_config"), text);
		assertFalse(text.contains(FakeClient.OUR_MODULUS), "no key in the log");
		assertFalse(text.contains(FakeClient.CLIENT_KEY), "no key in the log");
	}

	@Test
	public void aPinThatNoLongerFitsStopsBeforeAnyClassLoads() throws Exception
	{
		List<Path> jars = List.of(FakeClient.clientJar(dir, FakeClient.clientLoader()));
		Loader.Options options = new Loader.Options(jars, FakeClient.OUR_MODULUS, 43594,
			FakeClient.MAIN.replace('/', '.'), List.of());
		PatchException refused = assertThrows(PatchException.class, () -> Loader.start(options, log, failures::add));
		assertTrue(refused.getMessage().contains("injected-client"), refused.getMessage());
		assertNull(System.getProperty(FakeClient.ARGS_PROPERTY), "the main class never ran");
		assertTrue(logText().contains("[loader] MISSING "), logText());
	}

	@Test
	public void aMainClassThatThrowsIsReportedOnItsThread() throws Exception
	{
		Loader.Options options = new Loader.Options(FakeClient.jars(dir), FakeClient.OUR_MODULUS, 43594,
			FakeClient.THROWING_MAIN.replace('/', '.'), List.of("--one"));
		Thread thread = Loader.start(options, log, failures::add);
		thread.join(30_000);
		assertEquals(1, failures.size(), failures.toString());
		assertTrue(failures.get(0) instanceof IllegalStateException, failures.get(0).toString());
		assertEquals("the fake client refused to start", failures.get(0).getMessage());
		assertEquals("--one", System.getProperty(FakeClient.ARGS_PROPERTY), "it ran up to the throw");
	}

	@Test
	public void aJarThatIsNotThereIsAnIOException()
	{
		Loader.Options options = new Loader.Options(List.of(dir.resolve("injected-client-gone.jar")),
			FakeClient.OUR_MODULUS, 43594, "a.Main", List.of());
		IOException refused = assertThrows(IOException.class, () -> Loader.start(options, log, failures::add));
		assertTrue(refused.getMessage().contains("injected-client-gone.jar"), refused.getMessage());
	}
}
