package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * The self-test run on this JVM, which is a JDK with its java and this code's classes on its class path: the real
 * child JVM is started, and each failure is seen once with a broken piece.
 */
public class SelfTestTest
{
	@TempDir
	Path dir;

	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

	private String log()
	{
		return bytes.toString(StandardCharsets.UTF_8);
	}

	@Test
	public void thisJvmPassesTheSelfTest()
	{
		int code = SelfTest.run(Platform.detect(), Main.thisJvm(), dir, out);
		assertEquals(0, code, log());
		assertTrue(log().contains("[self-test] ok the runtime at "), log());
		assertTrue(log().contains("not Windows, so the Windows certificate store isn't looked for")
			|| log().contains("[self-test] ok the runtime opens the Windows certificate store Windows-ROOT the way the "
			+ "game does ("), log());
		assertTrue(log().contains("ready at revision 241"), log());
		assertTrue(log().contains("[self-test] ok the child JVM from "), log());
		assertTrue(log().contains("child: " + Loader.SELF_TEST_OK), log());
		assertTrue(log().contains("child: [loader] self-test: 192.0.2.1:80 refused"), log());
		assertTrue(log().contains("no display here") || log().contains("[self-test] ok the window opened as"), log());
		assertTrue(log().contains("[self-test] passed"), log());
		assertTrue(Files.isRegularFile(dir.resolve(SelfTest.DIR).resolve(SelfTest.CHILD_LOG)), "the child's output");
		assertTrue(Files.isRegularFile(dir.resolve(SelfTest.DIR).resolve(Launch.RTCONF)), "the runtime config");
	}

	/**
	 * On Windows this JDK has the store, so the step passes; elsewhere a platform claiming Windows fails it the way an
	 * installer's runtime linked without jdk.crypto.mscapi does, naming the module (the java.exe it then looks for
	 * is missing too, a second, separate failure).
	 */
	@Test
	public void theWindowsCertificateStoreMustOpenWhereThePlatformIsWindows()
	{
		Platform windows = new Platform(Platform.Os.WINDOWS, Platform.detect().arch());
		String typeBefore = System.getProperty(SelfTest.TRUST_STORE_TYPE);
		int code = SelfTest.run(windows, Main.thisJvm(), dir, out);
		if (Platform.detect().os() == Platform.Os.WINDOWS)
		{
			assertEquals(0, code, log());
			assertTrue(log().contains("[self-test] ok the runtime opens the Windows certificate store Windows-ROOT the "
				+ "way the game does ("), log());
		}
		else
		{
			assertEquals(SelfTest.EXIT_FAILED, code, log());
			assertTrue(log().contains("[self-test] FAIL the runtime can't open the Windows certificate store "
				+ "Windows-ROOT, which the game reads its trust roots from at every start, so no game would start: "),
				log());
			assertTrue(log().contains("(the runtime was linked without jdk.crypto.mscapi)"), log());
			assertTrue(log().contains("[self-test] FAILED: 2 step(s): no java at "), log());
		}
		assertFalse(log().contains("not Windows, so"), log());
		assertEquals(typeBefore, System.getProperty(SelfTest.TRUST_STORE_TYPE), "the property is restored");
	}

	@Test
	public void aRuntimeWithoutJavaFailsBeforeAnyChildIsStarted()
	{
		Launch.Installed stripped = new Launch.Installed(dir.resolve("runtime"), Main.thisJvm().loaderClasspath());
		int code = SelfTest.run(Platform.detect(), stripped, dir, out);
		assertEquals(SelfTest.EXIT_FAILED, code, log());
		assertTrue(log().contains("[self-test] FAIL no java at "), log());
		assertFalse(log().contains("child JVM"), log());
		assertTrue(log().contains("ready at revision 241"), "the server check still runs: " + log());
		assertTrue(log().contains("[self-test] FAILED: 1 step(s): no java at "), log());
	}

	@Test
	public void aChildThatCannotRunTheLoaderIsAFailureWithItsOutput() throws IOException
	{
		Path empty = Files.createDirectories(dir.resolve("empty"));
		Launch.Installed noCode = new Launch.Installed(Main.thisJvm().runtimeDir(), List.of(empty));
		int code = SelfTest.run(Platform.detect(), noCode, dir, out);
		assertEquals(SelfTest.EXIT_FAILED, code, log());
		assertTrue(log().contains("[self-test] FAIL the child JVM exited with 1 without saying " + Loader.SELF_TEST_OK),
			log());
		assertTrue(log().contains("child: Error: Could not find or load main class net.odinsgate.launcher.Loader"),
			log());
	}

	@Test
	public void theFakeServerLooksLikeOursToTheCheck() throws Exception
	{
		SelfTest.FakeServer server = SelfTest.FakeServer.start();
		try
		{
			Manifest manifest = Manifest.parse(SelfTest.manifest(server));
			assertEquals("127.0.0.1", server.host(), "IPv4 by the numbers, whatever the JVM's address preference");
			assertEquals("127.0.0.1", manifest.server.host());
			assertEquals(server.gamePort(), manifest.server.gamePort());
			assertEquals(241, manifest.pin(241).revision());
			ServerCheck.Status status = new ServerCheck(server.host(), server.httpPort(), server.gamePort(),
				ServerCheck.DEFAULT_TIMEOUT, ServerCheck.DEFAULT_INTERNET_PROBE).status().orElseThrow();
			assertTrue(status.up() && status.acceptingLogins() && status.revision() == 241, status.toString());
		}
		finally
		{
			server.stop();
		}
	}
}
