package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bootstrap parser, against RuneLite's real revision-241 file and against synthetic bad ones. */
public class BootstrapTest
{
	private static final Platform WIN_X64 = new Platform(Platform.Os.WINDOWS, Platform.Arch.X64);
	private static final Platform WIN_ARM = new Platform(Platform.Os.WINDOWS, Platform.Arch.AARCH64);
	private static final Platform MAC_X64 = new Platform(Platform.Os.MAC, Platform.Arch.X64);
	private static final Platform MAC_ARM = new Platform(Platform.Os.MAC, Platform.Arch.AARCH64);
	private static final Platform LINUX_X64 = new Platform(Platform.Os.LINUX, Platform.Arch.X64);

	private static Bootstrap real() throws BootstrapException
	{
		return Bootstrap.parse(new String(Fixtures.realBootstrap(), StandardCharsets.UTF_8));
	}

	/** A valid bootstrap around the given artifact entries, so each test states only what it is about. */
	private static String around(String artifacts)
	{
		return "{\"version\":\"1.13.4\",\"clientJvm17Arguments\":[\"-Xmx768m\"],\"artifacts\":[" + artifacts + "]}";
	}

	private static String jar(String name, String platforms)
	{
		return "{\"name\":\"" + name + "\",\"path\":\"https://repo.example/" + name + "\","
			+ "\"size\":10,\"hash\":\"" + "ab".repeat(32) + "\""
			+ (platforms == null ? "" : ",\"platform\":[" + platforms + "]") + "}";
	}

	private static List<String> names(Bootstrap bootstrap, Platform platform)
	{
		return bootstrap.artifacts(platform).stream().map(Bootstrap.Artifact::name).toList();
	}

	@Test
	public void theRealBootstrapParsesAndTheClientLeadsTheClasspath() throws Exception
	{
		Bootstrap real = real();
		assertEquals("1.13.4", real.version);
		List<String> windows = names(real, WIN_X64);
		assertEquals(31, windows.size());
		assertEquals("client-1.13.4.jar", windows.get(0));
		assertTrue(windows.contains("lwjgl-3.3.2-natives-windows.jar"));
	}

	@Test
	public void eachSystemGetsItsOwnNativesAndNobodyGetsX86() throws Exception
	{
		Bootstrap real = real();
		assertTrue(names(real, WIN_ARM).contains("lwjgl-3.3.2-natives-windows-arm64.jar"));
		assertTrue(names(real, MAC_X64).contains("lwjgl-3.3.2-natives-macos.jar"));
		assertTrue(names(real, MAC_ARM).contains("lwjgl-3.3.2-natives-macos-arm64.jar"));
		assertTrue(names(real, LINUX_X64).contains("lwjgl-3.3.2-natives-linux.jar"));
		for (Platform platform : List.of(WIN_X64, WIN_ARM, MAC_X64, MAC_ARM, LINUX_X64))
		{
			List<String> names = names(real, platform);
			// The x64 entries carry no arch, so an x86 native (which names its arch) must never slip in.
			assertTrue(names.stream().noneMatch(name -> name.contains("-x86")), platform + " got x86 natives");
			assertEquals(platform == WIN_X64 ? 31 : 30, names.size());
		}
	}

	@Test
	public void macsGetTheEawtArgumentsAndEveryoneElseTheDllList() throws Exception
	{
		Bootstrap real = real();
		assertTrue(real.clientJvmArguments(MAC_ARM).stream().anyMatch(a -> a.contains("com.apple.eawt")));
		assertTrue(real.clientJvmArguments(WIN_X64).stream().anyMatch(a -> a.contains("blacklistedDlls")));
		assertFalse(real.clientJvmArguments(MAC_ARM).stream().anyMatch(a -> a.contains("blacklistedDlls")));
	}

	@Test
	public void withoutAMacListMacsShareTheCommonArguments() throws Exception
	{
		Bootstrap bootstrap = Bootstrap.parse(around(jar("client.jar", null)));
		assertEquals(List.of("-Xmx768m"), bootstrap.clientJvmArguments(MAC_ARM));
	}

	@Test
	public void thePlatformVocabularyIsRuneLites() throws Exception
	{
		Bootstrap bootstrap = Bootstrap.parse(around(String.join(",",
			jar("everyone.jar", null),
			jar("win-x64.jar", "{\"name\":\"win\"}"),
			jar("win-arm.jar", "{\"name\":\"win\",\"arch\":\"aarch64\"}"),
			jar("win-x86.jar", "{\"name\":\"win\",\"arch\":\"x86\"}"),
			jar("mac-arm.jar", "{\"name\":\"macos\",\"arch\":\"aarch64\"}"),
			jar("two-systems.jar", "{\"name\":\"win\"},{\"name\":\"macos\"}"))));
		assertEquals(List.of("everyone.jar", "win-x64.jar", "two-systems.jar"), names(bootstrap, WIN_X64));
		assertEquals(List.of("everyone.jar", "win-arm.jar"), names(bootstrap, WIN_ARM));
		assertEquals(List.of("everyone.jar", "mac-arm.jar"), names(bootstrap, MAC_ARM));
		assertEquals(List.of("everyone.jar", "two-systems.jar"), names(bootstrap, MAC_X64));
		assertEquals(List.of("everyone.jar"), names(bootstrap, LINUX_X64));
	}

	@Test
	public void theClasspathKeepsTheBootstrapsOrder() throws Exception
	{
		Bootstrap bootstrap = Bootstrap.parse(around(String.join(",",
			jar("c.jar", null), jar("a.jar", null), jar("b.jar", null))));
		assertEquals(List.of("c.jar", "a.jar", "b.jar"), names(bootstrap, LINUX_X64));
	}

	@Test
	public void whatIsntABootstrapIsRefused()
	{
		assertRefused("not json at all");
		assertRefused("[]");
		assertRefused("{\"clientJvm17Arguments\":[],\"artifacts\":[" + jar("a.jar", null) + "]}");
		assertRefused("{\"version\":\"1\",\"artifacts\":[" + jar("a.jar", null) + "]}");
		assertRefused("{\"version\":\"1\",\"clientJvm17Arguments\":[\"-Xmx768m\",3],\"artifacts\":["
			+ jar("a.jar", null) + "]}");
		assertRefused("{\"version\":\"1\",\"clientJvm17Arguments\":[]}");
		assertRefused(around(""));
		assertRefused(around("\"a string\""));
	}

	@Test
	public void anArtifactThatCouldHurtIsRefused()
	{
		// A name that walks out of the cache folder, or a jar fetched where someone could swap it.
		assertRefused(around(jar("../evil.jar", null)));
		assertRefused(around(jar(".hidden.jar", null)));
		assertRefused(around(
			"{\"name\":\"a.jar\",\"path\":\"http://evil.example/a.jar\",\"size\":10,\"hash\":\"" + "ab".repeat(32) + "\"}"));
		assertRefused(around(
			"{\"name\":\"a.jar\",\"path\":\"https://repo.example/a.jar\",\"size\":10,\"hash\":\"" + "AB".repeat(32) + "\"}"));
		assertRefused(around(
			"{\"name\":\"a.jar\",\"path\":\"https://repo.example/a.jar\",\"size\":10,\"hash\":\"abcd\"}"));
	}

	@Test
	public void aSizeThatIsntAWholeNumberIsRefused()
	{
		String head = "{\"name\":\"a.jar\",\"path\":\"https://repo.example/a.jar\",\"hash\":\"" + "ab".repeat(32) + "\"";
		assertRefused(around(head + ",\"size\":10.5}"));
		assertRefused(around(head + ",\"size\":-1}"));
		assertRefused(around(head + ",\"size\":\"10\"}"));
		assertRefused(around(head + "}"));
	}

	@Test
	public void aPlatformEntryThatIsntAnObjectIsRefused()
	{
		assertRefused(around(jar("a.jar", "\"win\"")));
		assertRefused(around(jar("a.jar", "{\"arch\":\"aarch64\"}")));
	}

	private static void assertRefused(String json)
	{
		assertThrows(BootstrapException.class, () -> Bootstrap.parse(json), json);
	}
}
