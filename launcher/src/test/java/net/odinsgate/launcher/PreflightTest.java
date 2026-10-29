package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * The scan that reads the pinned jars before launch: it finds the patch points on the fake client, and names clearly
 * what is missing when the pin no longer fits. The fakes are ASM-built here, so no RuneLite or Jagex jar is in git.
 */
public class PreflightTest
{
	@TempDir
	Path dir;

	@Test
	public void itFindsEveryPatchPointOnAGoodPin() throws Exception
	{
		Preflight scan = Preflight.scan(FakeClient.jars(dir), FakeClient.OUR_MODULUS);
		assertTrue(scan.complete(), () -> "missing: " + scan.missing());
		scan.requireComplete();
		assertEquals(List.of(FakeClient.CLIENT, Preflight.CLIENT_LOADER), List.copyOf(scan.patches().keySet()));

		String client = scan.patches().get(FakeClient.CLIENT).summary();
		assertTrue(client.contains("server key"), client);
		assertTrue(client.contains("host check"), client);
		String loader = scan.patches().get(Preflight.CLIENT_LOADER).summary();
		assertTrue(loader.contains("updateVanilla returns at once"), loader);
		assertTrue(loader.contains("jagex host check"), loader);
	}

	@Test
	public void noLineOfTheReportCarriesAKey() throws Exception
	{
		// The report may name what it searched for (the loopback literal, the Jagex host: RSProx's public recipe),
		// but never a key: the client's own constant or ours, so a log is safe to attach to a problem report.
		Preflight scan = Preflight.scan(FakeClient.jars(dir), FakeClient.OUR_MODULUS);
		for (String line : scan.report())
		{
			assertFalse(line.contains(FakeClient.CLIENT_KEY), line);
			assertFalse(line.contains(FakeClient.OUR_MODULUS), line);
		}
	}

	@Test
	public void aKeyWithMoreDigitsThanTheClientsIsRefused() throws Exception
	{
		// Our key may not be longer than the client's, because the client reads its byte length from a 2-byte field.
		String tooLong = "f".repeat(FakeClient.CLIENT_KEY.length() + 2);
		Preflight scan = Preflight.scan(FakeClient.jars(dir), tooLong);
		assertFalse(scan.complete());
		assertTrue(scan.missing().stream().anyMatch(m -> m.contains("server key") && m.contains("more than")),
			() -> scan.missing().toString());
	}

	@Test
	public void aPinWithNoInjectedClientIsRefusedClearly() throws Exception
	{
		Preflight scan = Preflight.scan(List.of(FakeClient.clientJar(dir, FakeClient.clientLoader())),
			FakeClient.OUR_MODULUS);
		assertFalse(scan.complete());
		assertTrue(scan.missing().stream().anyMatch(m -> m.contains("injected-client")),
			() -> scan.missing().toString());
		PatchException refused = assertThrows(PatchException.class, scan::requireComplete);
		assertTrue(refused.getMessage().contains("no longer fits"), refused.getMessage());
	}

	@Test
	public void aClientLoaderWithoutDownloadConfigIsRefused() throws Exception
	{
		List<Path> jars = List.of(
			FakeClient.injectedJar(dir, FakeClient.injectedClient()),
			FakeClient.clientJar(dir, FakeClient.clientLoaderMissingDownloadConfig()));
		Preflight scan = Preflight.scan(jars, FakeClient.OUR_MODULUS);
		assertFalse(scan.complete());
		assertTrue(scan.missing().stream().anyMatch(m -> m.contains("downloadConfig")),
			() -> scan.missing().toString());
	}

	@Test
	public void aClientThatDoesNotReadOurWorldPropertyIsRefused() throws Exception
	{
		List<Path> jars = List.of(
			FakeClient.injectedJar(dir, FakeClient.injectedClient()),
			FakeClient.clientJarWithDeafModule(dir));
		Preflight scan = Preflight.scan(jars, FakeClient.OUR_MODULUS);
		assertFalse(scan.complete());
		assertTrue(scan.missing().stream().anyMatch(m -> m.contains(Preflight.HTTP_SERVICE_URL)),
			() -> scan.missing().toString());
	}

	@Test
	public void aPropertyNameThatNothingReadsIsRefused() throws Exception
	{
		// The name sits in a constant field, but no System.getProperty takes it: that is not a read.
		List<Path> jars = List.of(
			FakeClient.injectedJar(dir, FakeClient.injectedClient()),
			FakeClient.clientJarWithUnreadProperty(dir));
		Preflight scan = Preflight.scan(jars, FakeClient.OUR_MODULUS);
		assertFalse(scan.complete());
		assertTrue(scan.missing().stream().anyMatch(m -> m.contains(Preflight.HTTP_SERVICE_URL)),
			() -> scan.missing().toString());
	}

	@Test
	public void aJagexHostCheckWeDontPatchIsRefused() throws Exception
	{
		// downloadConfig checks ".jagex.net" instead: not our literal, so nothing would be patched, and the check
		// left in place could send RuneLite to its fallback config. That is a missing point, not "absent".
		List<Path> jars = List.of(
			FakeClient.injectedJar(dir, FakeClient.injectedClient()),
			FakeClient.clientJar(dir, FakeClient.clientLoaderWithRenamedJagexHost()));
		Preflight scan = Preflight.scan(jars, FakeClient.OUR_MODULUS);
		assertFalse(scan.complete());
		assertTrue(scan.missing().stream().anyMatch(m -> m.contains("downloadConfig") && m.contains("Jagex")),
			() -> scan.missing().toString());
	}

	@Test
	public void theGamePortIsPatchedOnlyWhenTheServerIsNotOnTheDefault() throws Exception
	{
		Preflight main = Preflight.scan(FakeClient.jars(dir), FakeClient.OUR_MODULUS);
		assertTrue(main.complete(), () -> "missing: " + main.missing());
		assertFalse(main.patches().get(FakeClient.CLIENT).summary().contains("game port"),
			main.patches().get(FakeClient.CLIENT).summary());
		assertTrue(main.report().stream().anyMatch(l -> l.contains("game port 43594") && l.contains("kept")),
			main.report().toString());

		Preflight staging = Preflight.scan(FakeClient.jars(dir), FakeClient.OUR_MODULUS, 44594);
		assertTrue(staging.complete(), () -> "missing: " + staging.missing());
		String summary = staging.patches().get(FakeClient.CLIENT).summary();
		assertTrue(summary.contains("game port x2"), summary);
		assertTrue(staging.report().stream().anyMatch(l -> l.contains("game port 43594") && l.contains("made 44594")),
			staging.report().toString());
	}

	@Test
	public void aClientWithoutThePortConstantRunsOnTheDefaultPortOnly() throws Exception
	{
		List<Path> jars = List.of(
			FakeClient.injectedJar(dir, FakeClient.injectedClientWithoutPort()),
			FakeClient.clientJar(dir, FakeClient.clientLoader()));
		assertTrue(Preflight.scan(jars, FakeClient.OUR_MODULUS).complete());
		Preflight staging = Preflight.scan(jars, FakeClient.OUR_MODULUS, 44594);
		assertFalse(staging.complete());
		assertTrue(staging.missing().stream().anyMatch(m -> m.contains("game port") && m.contains("44594")),
			() -> staging.missing().toString());
	}

	@Test
	public void aNonHexModulusOrABadPortIsAProgrammingError()
	{
		assertThrows(IllegalArgumentException.class,
			() -> Preflight.scan(FakeClient.jars(dir), "not hex at all"));
		assertThrows(IllegalArgumentException.class,
			() -> Preflight.scan(FakeClient.jars(dir), FakeClient.OUR_MODULUS, 0));
		assertThrows(IllegalArgumentException.class,
			() -> Preflight.scan(FakeClient.jars(dir), FakeClient.OUR_MODULUS, 70000));
	}
}
