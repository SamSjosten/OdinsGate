package net.odinsgate.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The scan CI runs over every built app image (README, "Installers"), from the image's own runtime: nothing of the
 * game's or of RuneLite's is in what we ship, no name a friend sees is a banned one, every jar is ours or an allowed
 * dependency at its known hash, and the notices name every library. Five checks, each an {@code ok} or
 * {@code FAIL} line:
 * <ul>
 * <li>names: no file or folder in the image has a name {@link Names#BANNED} matches;</li>
 * <li>content: no file in the image and no entry in any of its jars is named like a client (injected-client*,
 * client-*.jar, gamepack*), a cache file (*.dat2, *.idx*) or the client's signature (JAGEXLTD.*), and no jar holds
 * a class under net/runelite or in the default package;</li>
 * <li>jars: outside the runtime, the one odinsgate-launcher-*.jar holds only net/odinsgate classes, and every other
 * jar is on the allowed list ({@code --allowed}, package/allowed-jars.txt: "name sha256 library" per line) with the
 * SHA-256 it was allowed at;</li>
 * <li>runtime: the folder named runtime (on a Mac, Contents/Home under it) has bin/java, which the game's child JVM
 * is started from, and legal/java.base, OpenJDK's own notices;</li>
 * <li>notices: THIRD-PARTY-NOTICES.txt sits next to our jar and names RuneLite, RSProx and every allowed library.</li>
 * </ul>
 * Exit 0 when all passed, 1 when any failed, {@value #EXIT_USAGE} for a bad command line or allowed list.
 * <pre>
 * --image &lt;dir&gt;     the app image: the folder jpackage made (OdinsGate, or OdinsGate.app)
 * --allowed &lt;file&gt;  the allowed jars
 * </pre>
 */
public final class ImageScan
{
	static final int EXIT_USAGE = 64;
	static final String NOTICES = "THIRD-PARTY-NOTICES.txt";
	static final String OUR_JAR_PREFIX = "odinsgate-launcher-";
	static final String OUR_PACKAGE = "net/odinsgate/";
	static final String RUNTIME_DIR = "runtime";
	/** Named in the notices whatever the allowed list says: the two projects this launcher learned from. */
	static final List<String> ALWAYS_NOTICED = List.of("RuneLite", "RSProx");
	private static final Pattern CLIENT_FILE = Pattern.compile(
		"injected-client.*|client-.*\\.jar|gamepack.*|.*\\.dat2|.*\\.idx.*|JAGEXLTD\\..*", Pattern.CASE_INSENSITIVE);
	private static final Pattern MODULE_INFO = Pattern.compile("(META-INF/versions/\\d+/)?module-info\\.class");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/** One line of the allowed list: a jar, the bytes it must be, and the library the notices must name for it. */
	record Allowed(String jar, String sha256, String library)
	{
	}

	private ImageScan()
	{
	}

	public static void main(String[] args)
	{
		System.exit(run(args, System.out));
	}

	static int run(String[] args, PrintStream out)
	{
		Path image = null;
		Path allowedFile = null;
		for (int i = 0; i + 1 < args.length; i += 2)
		{
			switch (args[i])
			{
				case "--image" -> image = Path.of(args[i + 1]);
				case "--allowed" -> allowedFile = Path.of(args[i + 1]);
				default -> image = allowedFile = null;
			}
		}
		if (image == null || allowedFile == null || args.length % 2 != 0)
		{
			out.println("[scan] arguments: --image <dir> --allowed <file>");
			return EXIT_USAGE;
		}
		List<Allowed> allowed;
		try
		{
			allowed = allowed(Files.readAllLines(allowedFile, StandardCharsets.UTF_8));
		}
		catch (IOException | IllegalArgumentException e)
		{
			out.println("[scan] " + allowedFile + ": " + e.getMessage());
			return EXIT_USAGE;
		}
		List<String> failures;
		try
		{
			failures = scan(image, allowed, out);
		}
		catch (IOException e)
		{
			out.println("[scan] FAIL " + image + " could not be read: " + e.getMessage());
			return 1;
		}
		if (failures.isEmpty())
		{
			out.println("[scan] passed: " + image);
			return 0;
		}
		out.println("[scan] FAILED: " + failures.size() + " problem(s) in " + image);
		return 1;
	}

	/** The allowed list's lines; an {@link IllegalArgumentException} names a line that isn't "jar sha256 library". */
	static List<Allowed> allowed(List<String> lines)
	{
		List<Allowed> allowed = new ArrayList<>();
		for (String line : lines)
		{
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#"))
			{
				continue;
			}
			String[] parts = trimmed.split("\\s+", 3);
			if (parts.length < 3 || !SHA256.matcher(parts[1]).matches())
			{
				throw new IllegalArgumentException("not \"jar sha256 library\": " + line);
			}
			allowed.add(new Allowed(parts[0], parts[1], parts[2]));
		}
		return List.copyOf(allowed);
	}

	/** The five checks over {@code image}, each reported on {@code out}; the failures, empty when it passed. */
	static List<String> scan(Path image, List<Allowed> allowed, PrintStream out) throws IOException
	{
		if (!Files.isDirectory(image))
		{
			throw new IOException("not a folder");
		}
		List<String> failures = new ArrayList<>();
		List<Path> all;
		try (Stream<Path> walk = Files.walk(image))
		{
			all = walk.filter(path -> !path.equals(image)).sorted().toList();
		}

		List<String> names = new ArrayList<>();
		for (Path path : all)
		{
			if (Names.BANNED.matcher(path.getFileName().toString()).find())
			{
				names.add("banned name: " + image.relativize(path));
			}
		}
		check(out, failures, names, "names: " + all.size() + " entries, none banned");

		Path runtime = runtime(image);
		List<Path> files = all.stream().filter(Files::isRegularFile).toList();
		List<String> content = new ArrayList<>();
		List<String> jarProblems = new ArrayList<>();
		List<String> allowedSeen = new ArrayList<>();
		Path ourJar = null;
		int jars = 0;
		int entries = 0;
		for (Path file : files)
		{
			String name = file.getFileName().toString();
			String rel = image.relativize(file).toString();
			if (CLIENT_FILE.matcher(name).matches())
			{
				content.add("a client or cache file: " + rel);
			}
			if (!name.endsWith(".jar"))
			{
				continue;
			}
			jars++;
			boolean inRuntime = runtime != null && file.startsWith(runtime);
			boolean ours = !inRuntime && name.startsWith(OUR_JAR_PREFIX);
			try (ZipFile zip = new ZipFile(file.toFile()))
			{
				for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();)
				{
					String entry = e.nextElement().getName();
					entries++;
					if (CLIENT_FILE.matcher(entry.substring(entry.lastIndexOf('/') + 1)).matches())
					{
						content.add("a client, cache or signature file in " + rel + ": " + entry);
					}
					if (!entry.endsWith(".class") || MODULE_INFO.matcher(entry).matches())
					{
						continue;
					}
					if (entry.startsWith("net/runelite/"))
					{
						content.add("a RuneLite class in " + rel + ": " + entry);
					}
					else if (!entry.contains("/"))
					{
						content.add("a class in the default package in " + rel + ": " + entry);
					}
					else if (ours && !entry.startsWith(OUR_PACKAGE))
					{
						jarProblems.add("a class outside " + OUR_PACKAGE + " in our jar " + rel + ": " + entry);
					}
				}
			}
			catch (ZipException e)
			{
				content.add("not a jar: " + rel + " (" + e.getMessage() + ")");
				continue;
			}
			if (inRuntime)
			{
				continue;
			}
			if (ours)
			{
				if (ourJar != null)
				{
					jarProblems.add("two launcher jars: " + image.relativize(ourJar) + " and " + rel);
				}
				ourJar = file;
				continue;
			}
			Allowed match = allowed.stream().filter(a -> a.jar().equals(name)).findFirst().orElse(null);
			if (match == null)
			{
				jarProblems.add("not an allowed jar: " + rel);
				continue;
			}
			String sha256 = sha256(file);
			if (!sha256.equals(match.sha256()))
			{
				jarProblems.add(rel + " isn't the allowed " + name + ": SHA-256 " + sha256 + ", allowed "
					+ match.sha256());
			}
			else
			{
				allowedSeen.add(name);
			}
		}
		if (ourJar == null)
		{
			jarProblems.add("no " + OUR_JAR_PREFIX + "*.jar outside the runtime");
		}
		check(out, failures, content, "content: " + files.size() + " files and " + entries + " entries in " + jars
			+ " jars, nothing of a client among them");
		check(out, failures, jarProblems, "jars: ours " + (ourJar == null ? "missing" : ourJar.getFileName())
			+ ", allowed " + allowedSeen);

		List<String> runtimeProblems = new ArrayList<>();
		if (runtime == null)
		{
			runtimeProblems.add("no " + RUNTIME_DIR + " folder with bin/java in the image");
		}
		else if (!Files.isDirectory(runtime.resolve("legal").resolve("java.base")))
		{
			runtimeProblems.add(image.relativize(runtime)
				+ " has no legal/java.base: OpenJDK's own notices are missing");
		}
		check(out, failures, runtimeProblems, "runtime: " + (runtime == null ? "" : image.relativize(runtime))
			+ " with its java and legal/java.base");

		List<String> noticeProblems = new ArrayList<>();
		Set<String> wanted = new LinkedHashSet<>(ALWAYS_NOTICED);
		allowed.forEach(a -> wanted.add(a.library()));
		if (ourJar != null)
		{
			Path notices = ourJar.getParent().resolve(NOTICES);
			if (!Files.isRegularFile(notices))
			{
				noticeProblems.add("no " + NOTICES + " next to " + ourJar.getFileName());
			}
			else
			{
				String text = Files.readString(notices, StandardCharsets.UTF_8);
				for (String library : wanted)
				{
					if (!text.contains(library))
					{
						noticeProblems.add(NOTICES + " doesn't name " + library);
					}
				}
			}
			check(out, failures, noticeProblems, "notices: " + NOTICES + " names " + wanted);
		}
		return failures;
	}

	/** The runtime's home: a folder named runtime, or Contents/Home under one (a Mac), holding bin/java. */
	static Path runtime(Path image) throws IOException
	{
		List<Path> candidates;
		try (Stream<Path> walk = Files.walk(image))
		{
			candidates = walk.filter(Files::isDirectory)
				.filter(dir -> dir.getFileName().toString().equals(RUNTIME_DIR))
				.sorted()
				.toList();
		}
		for (Path candidate : candidates)
		{
			for (Path home : List.of(candidate, candidate.resolve("Contents").resolve("Home")))
			{
				if (Files.isRegularFile(home.resolve("bin").resolve("java"))
					|| Files.isRegularFile(home.resolve("bin").resolve("java.exe")))
				{
					return home;
				}
			}
		}
		return null;
	}

	static String sha256(Path file) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static void check(PrintStream out, List<String> failures, List<String> problems, String ok)
	{
		if (problems.isEmpty())
		{
			out.println("[scan] ok " + ok);
			return;
		}
		problems.forEach(problem -> out.println("[scan] FAIL " + problem));
		failures.addAll(problems);
	}
}
