package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The image scan over a made-up app image that passes every check, with one thing spoiled per test. */
public class ImageScanTest
{
	@TempDir
	Path dir;

	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
	private Path image;
	private Path app;
	private List<ImageScan.Allowed> allowed;

	@BeforeEach
	public void aWindowsShapedImage() throws IOException
	{
		image = Files.createDirectories(dir.resolve("OdinsGate"));
		Files.writeString(image.resolve("OdinsGate.exe"), "exe");
		app = Files.createDirectories(image.resolve("app"));
		jar(app.resolve("odinsgate-launcher-0.1.0.jar"), "META-INF/MANIFEST.MF", "net/odinsgate/launcher/Main.class",
			"net/odinsgate/launcher/runelite.crt");
		Path gson = jar(app.resolve("gson-2.13.1.jar"), "META-INF/MANIFEST.MF", "module-info.class",
			"META-INF/versions/9/module-info.class", "com/google/gson/Gson.class");
		Path asm = jar(app.resolve("asm-9.11.jar"), "org/objectweb/asm/ClassReader.class");
		allowed = List.of(new ImageScan.Allowed("gson-2.13.1.jar", ImageScan.sha256(gson), "Gson"),
			new ImageScan.Allowed("asm-9.11.jar", ImageScan.sha256(asm), "ASM"));
		Files.writeString(app.resolve(ImageScan.NOTICES), "RuneLite BSD-2, RSProx MIT, ASM BSD-3, Gson Apache-2.0\n");
		Path runtime = image.resolve("runtime");
		Files.createDirectories(runtime.resolve("bin"));
		Files.writeString(runtime.resolve("bin").resolve("java.exe"), "java");
		Files.createDirectories(runtime.resolve("legal").resolve("java.base"));
		Files.writeString(runtime.resolve("legal").resolve("java.base").resolve("LICENSE"), "GPL");
		Files.createDirectories(runtime.resolve("lib"));
		jar(runtime.resolve("lib").resolve("jrt-fs.jar"), "jdk/internal/jrtfs/JrtFileSystem.class");
	}

	private String output()
	{
		return bytes.toString(StandardCharsets.UTF_8);
	}

	private List<String> scan() throws IOException
	{
		return ImageScan.scan(image, allowed, out);
	}

	private static Path jar(Path file, String... entries) throws IOException
	{
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file)))
		{
			for (String entry : entries)
			{
				jar.putNextEntry(new ZipEntry(entry));
				jar.write(entry.getBytes(StandardCharsets.UTF_8));
				jar.closeEntry();
			}
		}
		return file;
	}

	@Test
	public void aCleanImagePassesEveryCheck() throws IOException
	{
		assertEquals(List.of(), scan(), output());
		assertEquals(5, output().split("\\[scan\\] ok ", -1).length - 1, output());
		assertTrue(output().contains("[scan] ok jars: ours odinsgate-launcher-0.1.0.jar, allowed [asm-9.11.jar, "
			+ "gson-2.13.1.jar]"), output());
		assertTrue(output().contains("[scan] ok runtime: runtime with its java"), output());
		assertTrue(output().contains("names [RuneLite, RSProx, Gson, ASM]"), output());
	}

	@Test
	public void theCommandLineReadsTheAllowedListAndExitsByTheOutcome() throws IOException
	{
		Path list = dir.resolve("allowed.txt");
		Files.writeString(list, "# the test's jars\n\ngson-2.13.1.jar " + allowed.get(0).sha256() + " Gson\n"
			+ "asm-9.11.jar " + allowed.get(1).sha256() + " ASM\n");
		String[] args = {"--image", image.toString(), "--allowed", list.toString()};
		assertEquals(0, ImageScan.run(args, out), output());
		assertTrue(output().contains("[scan] passed: "), output());
		Files.writeString(app.resolve("gamepack.jar"), "no");
		assertEquals(1, ImageScan.run(args, out), output());
		assertTrue(output().contains("[scan] FAILED: 2 problem(s)"), output());
		assertEquals(ImageScan.EXIT_USAGE, ImageScan.run(new String[] {"--image", image.toString()}, out));
		assertEquals(ImageScan.EXIT_USAGE, ImageScan.run(new String[] {"--image", image.toString(), "--allowed",
			dir.resolve("missing.txt").toString()}, out));
		Files.writeString(list, "gson-2.13.1.jar nothex Gson\n");
		assertEquals(ImageScan.EXIT_USAGE, ImageScan.run(args, out));
		assertTrue(output().contains("not \"jar sha256 library\": gson-2.13.1.jar nothex Gson"), output());
	}

	@Test
	public void aBannedNameAnywhereFails() throws IOException
	{
		Files.writeString(image.resolve("runtime").resolve("legal").resolve("jagex.md"), "x");
		Files.createDirectories(app.resolve("OSRS data"));
		List<String> failures = scan();
		assertTrue(failures.contains("banned name: " + Path.of("runtime", "legal", "jagex.md")), failures.toString());
		assertTrue(failures.contains("banned name: " + Path.of("app", "OSRS data")), failures.toString());
		assertEquals(2, failures.size(), failures.toString());
	}

	@Test
	public void aClientOrCacheFileInTheImageOrInAJarFails() throws IOException
	{
		Files.writeString(image.resolve("runtime").resolve("lib").resolve("main_file_cache.dat2"), "x");
		Files.writeString(app.resolve("injected-client-241.bin"), "x");
		jar(app.resolve("odinsgate-launcher-0.1.0.jar"), "net/odinsgate/launcher/Main.class", "META-INF/JAGEXLTD.RSA",
			"net/odinsgate/launcher/gamepack.jar");
		List<String> failures = scan();
		assertTrue(failures.contains("a client or cache file: " + Path.of("runtime", "lib", "main_file_cache.dat2")),
			failures.toString());
		assertTrue(failures.contains("a client or cache file: " + Path.of("app", "injected-client-241.bin")),
			failures.toString());
		String ours = "a client, cache or signature file in " + Path.of("app", "odinsgate-launcher-0.1.0.jar");
		assertTrue(failures.contains(ours + ": META-INF/JAGEXLTD.RSA"), failures.toString());
		assertTrue(failures.contains(ours + ": net/odinsgate/launcher/gamepack.jar"), failures.toString());
		assertEquals(4, failures.size(), failures.toString());
	}

	@Test
	public void aRuneLiteClassADefaultPackageClassOrAStrangerInOurJarFails() throws IOException
	{
		jar(app.resolve("odinsgate-launcher-0.1.0.jar"), "net/odinsgate/launcher/Main.class",
			"net/runelite/client/RuneLite.class", "Injected.class", "com/other/Thing.class", "module-info.class");
		List<String> failures = scan();
		String ours = Path.of("app", "odinsgate-launcher-0.1.0.jar").toString();
		assertTrue(failures.contains("a RuneLite class in " + ours + ": net/runelite/client/RuneLite.class"),
			failures.toString());
		assertTrue(failures.contains("a class in the default package in " + ours + ": Injected.class"),
			failures.toString());
		assertTrue(failures.contains("a class outside net/odinsgate/ in our jar " + ours + ": com/other/Thing.class"),
			failures.toString());
		assertEquals(3, failures.size(), "module-info.class is fine: " + failures);
	}

	@Test
	public void aJarNotOnTheListOrNotTheBytesAllowedFails() throws IOException
	{
		jar(app.resolve("other-1.0.jar"), "com/other/Thing.class");
		jar(app.resolve("gson-2.13.1.jar"), "com/google/gson/Gson.class", "com/google/gson/Tampered.class");
		List<String> failures = scan();
		assertTrue(failures.contains("not an allowed jar: " + Path.of("app", "other-1.0.jar")), failures.toString());
		assertTrue(failures.stream().anyMatch(f -> f.startsWith(Path.of("app", "gson-2.13.1.jar")
			+ " isn't the allowed gson-2.13.1.jar: SHA-256 ")), failures.toString());
		assertEquals(2, failures.size(), failures.toString());
	}

	@Test
	public void aJarInTheRuntimeIsNotHeldToTheListButToTheContentRules() throws IOException
	{
		jar(image.resolve("runtime").resolve("lib").resolve("jrt-fs.jar"), "jdk/internal/jrtfs/X.class",
			"net/runelite/Smuggled.class");
		List<String> failures = scan();
		assertEquals(List.of("a RuneLite class in " + Path.of("runtime", "lib", "jrt-fs.jar")
			+ ": net/runelite/Smuggled.class"), failures);
	}

	@Test
	public void exactlyOneLauncherJarIsExpected() throws IOException
	{
		jar(app.resolve("odinsgate-launcher-0.2.0.jar"), "net/odinsgate/launcher/Main.class");
		List<String> failures = scan();
		assertEquals(List.of("two launcher jars: " + Path.of("app", "odinsgate-launcher-0.1.0.jar") + " and "
			+ Path.of("app", "odinsgate-launcher-0.2.0.jar")), failures);
		Files.delete(app.resolve("odinsgate-launcher-0.1.0.jar"));
		Files.delete(app.resolve("odinsgate-launcher-0.2.0.jar"));
		bytes.reset();
		assertEquals(List.of("no odinsgate-launcher-*.jar outside the runtime"), scan());
		assertTrue(!output().contains("notices"), "no jar, so no place to look for the notices: " + output());
	}

	@Test
	public void theRuntimeMustHaveItsJavaAndItsOwnNotices() throws IOException
	{
		Files.delete(image.resolve("runtime").resolve("legal").resolve("java.base").resolve("LICENSE"));
		Files.delete(image.resolve("runtime").resolve("legal").resolve("java.base"));
		assertEquals(List.of("runtime has no legal/java.base: OpenJDK's own notices are missing"), scan());
		Files.delete(image.resolve("runtime").resolve("bin").resolve("java.exe"));
		// Without its java the folder isn't a runtime, so its jar is a stranger too.
		assertEquals(List.of("not an allowed jar: " + Path.of("runtime", "lib", "jrt-fs.jar"),
			"no runtime folder with bin/java in the image"), scan());
	}

	@Test
	public void aMacShapedImageIsReadToo() throws IOException
	{
		Path mac = Files.createDirectories(dir.resolve("OdinsGate.app"));
		Path contents = Files.createDirectories(mac.resolve("Contents"));
		Files.createDirectories(contents.resolve("MacOS"));
		Files.writeString(contents.resolve("MacOS").resolve("OdinsGate"), "exe");
		Path macApp = Files.createDirectories(contents.resolve("app"));
		jar(macApp.resolve("odinsgate-launcher-0.1.0.jar"), "net/odinsgate/launcher/Main.class");
		Files.writeString(macApp.resolve(ImageScan.NOTICES), "RuneLite, RSProx, Gson, ASM\n");
		Path home = Files.createDirectories(contents.resolve("runtime").resolve("Contents").resolve("Home"));
		Files.createDirectories(home.resolve("bin"));
		Files.writeString(home.resolve("bin").resolve("java"), "java");
		Files.createDirectories(home.resolve("legal").resolve("java.base"));
		assertEquals(List.of(), ImageScan.scan(mac, allowed, out), output());
		assertEquals(home, ImageScan.runtime(mac));
	}

	@Test
	public void theNoticesMustSitNextToOurJarAndNameEveryLibrary() throws IOException
	{
		Files.writeString(app.resolve(ImageScan.NOTICES), "RuneLite and Gson only\n");
		assertEquals(List.of("THIRD-PARTY-NOTICES.txt doesn't name RSProx", "THIRD-PARTY-NOTICES.txt doesn't name ASM"),
			scan());
		Files.delete(app.resolve(ImageScan.NOTICES));
		assertEquals(List.of("no THIRD-PARTY-NOTICES.txt next to odinsgate-launcher-0.1.0.jar"), scan());
	}

	@Test
	public void theAllowedListIsLinesOfJarHashAndLibrary()
	{
		List<ImageScan.Allowed> parsed = ImageScan.allowed(List.of("# a comment", "", "  ",
			"gson-2.13.1.jar " + "a".repeat(64) + " Gson", "x.jar " + "b".repeat(64) + "   Error Prone  "));
		assertEquals(List.of(new ImageScan.Allowed("gson-2.13.1.jar", "a".repeat(64), "Gson"),
			new ImageScan.Allowed("x.jar", "b".repeat(64), "Error Prone")), parsed);
		assertThrows(IllegalArgumentException.class, () -> ImageScan.allowed(List.of("gson.jar " + "a".repeat(64))));
		assertThrows(IllegalArgumentException.class, () -> ImageScan.allowed(List.of("gson.jar abc Gson")));
	}

	@Test
	public void theProjectsOwnAllowedListParses() throws IOException
	{
		Path list = Path.of("package", "allowed-jars.txt").toAbsolutePath();
		assertTrue(Files.isRegularFile(list), "not running from launcher/: " + list);
		List<ImageScan.Allowed> parsed = ImageScan.allowed(Files.readAllLines(list, StandardCharsets.UTF_8));
		assertEquals(4, parsed.size(), parsed.toString());
		String notices = Files.readString(Path.of("THIRD-PARTY-NOTICES.txt"), StandardCharsets.UTF_8);
		for (ImageScan.Allowed jar : parsed)
		{
			assertTrue(notices.contains(jar.library()), jar.library());
			assertTrue(notices.contains(jar.jar()), jar.jar());
		}
		for (String always : ImageScan.ALWAYS_NOTICED)
		{
			assertTrue(notices.contains(always), always);
		}
	}
}
