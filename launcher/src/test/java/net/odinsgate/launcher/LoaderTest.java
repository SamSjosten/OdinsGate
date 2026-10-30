package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
	private static final Manifest.Server SERVER = new Manifest.Server("odinsgate.net", 44594, 44595,
		FakeClient.OUR_MODULUS);
	private static final List<String> ALLOWED = List.of("odinsgate.net:44594", "odinsgate.net:44595");

	@TempDir
	Path dir;

	private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
	private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
	private final List<Throwable> failures = new CopyOnWriteArrayList<>();

	private ProxySelector selectorBefore;

	@BeforeEach
	@AfterEach
	public void clearWhatTheFakeMainRecords()
	{
		PROPERTIES.forEach(System::clearProperty);
	}

	/**
	 * The loader the started thread runs on holds the jars open; closed here, once the fake main returned, or
	 * Windows won't let the temp folder go. A real child JVM exits instead.
	 */
	private static void closeLoaderOf(Thread thread) throws IOException
	{
		if (thread.getContextClassLoader() instanceof PatchingClassLoader loader)
		{
			loader.close();
		}
	}

	/** A start installs the guard as this JVM's selector; the other tests' loopback servers need the one before. */
	@BeforeEach
	public void keepTheSelector()
	{
		selectorBefore = ProxySelector.getDefault();
	}

	@AfterEach
	public void restoreTheSelector() throws IOException
	{
		if (ProxySelector.getDefault() instanceof ConnectGuard guard)
		{
			guard.close();
		}
		ProxySelector.setDefault(selectorBefore);
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
		List<String> args = new ArrayList<>(Loader.arguments(jars, SERVER, Loader.RUNELITE_MAIN));
		args.add("--");
		args.add("--jav_config");
		args.add("http://odinsgate.net:43595/launcher/jav_config.ws");
		args.add("--disable-telemetry");
		Loader.Options options = Loader.parse(args.toArray(String[]::new));
		assertEquals(jars, options.jars());
		assertEquals(FakeClient.OUR_MODULUS, options.modulus());
		assertEquals(44594, options.gamePort());
		assertEquals(ALLOWED, options.allowed(), "the server's two ports are what the game may reach");
		assertEquals(Loader.RUNELITE_MAIN, options.mainClass());
		assertEquals(
			List.of("--jav_config", "http://odinsgate.net:43595/launcher/jav_config.ws", "--disable-telemetry"),
			options.runeliteArgs());
	}

	@Test
	public void aBadCommandLineIsRefused()
	{
		String jar = Path.of("cache", "client-1.13.4.jar").toString();
		String allow = "odinsgate.net:43594";
		for (String[] bad : List.of(
			new String[]{},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "43594", "--allow", allow},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--allow", allow, "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "43594", "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", "zz", "--port", "43594", "--allow", allow, "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "70000", "--allow", allow,
				"--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "x", "--allow", allow,
				"--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "43594", "--allow",
				"odinsgate.net", "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "43594", "--allow",
				"odinsgate.net:0", "--main", "a.Main"},
			new String[]{"--jar", jar, "--modulus", FakeClient.OUR_MODULUS, "--port", "43594", "--allow", allow,
				"--main"},
			new String[]{"--jar", jar, "--what", "ever", "--modulus", FakeClient.OUR_MODULUS, "--port", "1",
				"--allow", allow, "--main", "a.Main"}))
		{
			assertThrows(IllegalArgumentException.class, () -> Loader.parse(bad), String.join(" ", bad));
		}
	}

	@Test
	public void itPatchesTheClientAndStartsTheMainClassWithRuneLitesArguments() throws Exception
	{
		Loader.Options options = new Loader.Options(FakeClient.jars(dir), FakeClient.OUR_MODULUS, 44594, ALLOWED,
			FakeClient.MAIN.replace('/', '.'), List.of("--jav_config", "http://odinsgate.net:43595/x.ws"));
		Thread thread = Loader.start(options, log, failures::add);
		thread.join(30_000);
		closeLoaderOf(thread);
		assertFalse(thread.isAlive(), "the fake main returned");
		assertTrue(failures.isEmpty(), failures.toString());
		assertTrue(ProxySelector.getDefault() instanceof ConnectGuard, "the guard is the JVM's selector");
		assertEquals(Set.copyOf(ALLOWED), ((ConnectGuard) ProxySelector.getDefault()).endpoints());
		assertTrue(logText().contains("[loader] the game may reach odinsgate.net:44594, odinsgate.net:44595 and"
			+ " loopback; every other host is refused"), logText());
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
		Loader.Options options = new Loader.Options(jars, FakeClient.OUR_MODULUS, 43594, ALLOWED,
			FakeClient.MAIN.replace('/', '.'), List.of());
		PatchException refused = assertThrows(PatchException.class, () -> Loader.start(options, log, failures::add));
		assertTrue(refused.getMessage().contains("injected-client"), refused.getMessage());
		assertNull(System.getProperty(FakeClient.ARGS_PROPERTY), "the main class never ran");
		assertTrue(logText().contains("[loader] MISSING "), logText());
		assertFalse(ProxySelector.getDefault() instanceof ConnectGuard, "no guard before the scan passes");
	}

	@Test
	public void aMainClassThatThrowsIsReportedOnItsThread() throws Exception
	{
		Loader.Options options = new Loader.Options(FakeClient.jars(dir), FakeClient.OUR_MODULUS, 43594, ALLOWED,
			FakeClient.THROWING_MAIN.replace('/', '.'), List.of("--one"));
		Thread thread = Loader.start(options, log, failures::add);
		thread.join(30_000);
		closeLoaderOf(thread);
		assertEquals(1, failures.size(), failures.toString());
		assertTrue(failures.get(0) instanceof IllegalStateException, failures.get(0).toString());
		assertEquals("the fake client refused to start", failures.get(0).getMessage());
		assertEquals("--one", System.getProperty(FakeClient.ARGS_PROPERTY), "it ran up to the throw");
	}

	@Test
	public void aJarThatIsNotThereIsAnIOException()
	{
		Loader.Options options = new Loader.Options(List.of(dir.resolve("injected-client-gone.jar")),
			FakeClient.OUR_MODULUS, 43594, ALLOWED, "a.Main", List.of());
		IOException refused = assertThrows(IOException.class, () -> Loader.start(options, log, failures::add));
		assertTrue(refused.getMessage().contains("injected-client-gone.jar"), refused.getMessage());
	}

	/** The self-test child in this JVM: the guard set, the allowed port reached, elsewhere refused by the guard. */
	@Test
	public void theSelfTestChildReachesTheAllowedPortAndIsRefusedElsewhere() throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream log = new PrintStream(bytes, true, StandardCharsets.UTF_8);
		try (java.net.ServerSocket game = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()))
		{
			String allowed = "127.0.0.1:" + game.getLocalPort();
			int code = Loader.selfTest(new String[] {Loader.SELF_TEST, "--allow", allowed, "--allow", "127.0.0.1:1",
				"--", "--jav_config", "ignored"}, log);
			String out = bytes.toString(StandardCharsets.UTF_8);
			assertEquals(0, code, out);
			assertTrue(out.contains("[loader] self-test: reached " + allowed + " through the guard"), out);
			assertTrue(out.contains("[loader] self-test: 192.0.2.1:80 refused ("), out);
			assertTrue(out.contains(Loader.SELF_TEST_OK + ": java "), out);
			assertEquals(List.of(Loader.SELF_TEST, "--allow", "h:1", "--allow", "h:2"),
				Loader.selfTestArguments(new Manifest.Server("h", 1, 2, "ab")));
		}
		bytes.reset();
		assertEquals(Loader.EXIT_USAGE, Loader.selfTest(new String[] {Loader.SELF_TEST}, log));
		assertEquals(Loader.EXIT_USAGE, Loader.selfTest(new String[] {Loader.SELF_TEST, "--jar", "x"}, log));
		assertEquals(Loader.EXIT_USAGE, Loader.selfTest(new String[] {Loader.SELF_TEST, "--allow", "no port"}, log));
		String out = bytes.toString(StandardCharsets.UTF_8);
		assertTrue(out.contains("at least one --allow") && out.contains("unknown argument --jar"), out);
	}
}
