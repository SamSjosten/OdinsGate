package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Nothing a friend can see may contain RuneScape, OSRS, Jagex, -scape or RSPS, in any case: not the app's name, not
 * a folder it makes, not a file in its releases. Sam's Linode rule (2026-10-28). Checked here on the Names constants
 * and on every file name in this module; the release PRs scan the built installers the same way.
 */
public class NamesTest
{
	private static final Pattern BANNED = Pattern.compile("runescape|osrs|jagex|scape|rsps", Pattern.CASE_INSENSITIVE);

	@Test
	public void everyFriendVisibleNameIsClean()
	{
		for (String name : List.of(Names.APP_NAME, Names.DIR_CAPITALIZED, Names.DIR_LOWERCASE, Names.BUNDLE_ID,
			Names.VENDOR))
		{
			assertFalse(BANNED.matcher(name).find(), name);
		}
	}

	@Test
	public void thePackageIsOursNotTheServers()
	{
		String packageName = Names.class.getPackageName();
		assertTrue(packageName.startsWith("net.odinsgate"), packageName);
		assertFalse(BANNED.matcher(packageName).find(), packageName);
	}

	@Test
	public void noFileInThisModuleCarriesABannedName() throws IOException
	{
		// Gradle runs tests with the project folder (launcher/) as the working directory.
		Path module = Path.of("").toAbsolutePath();
		assertTrue(Files.isDirectory(module.resolve("src")), "not running from launcher/: " + module);
		try (Stream<Path> files = Files.walk(module.resolve("src")))
		{
			files.forEach(file -> assertFalse(BANNED.matcher(file.getFileName().toString()).find(), file.toString()));
		}
		assertFalse(BANNED.matcher(module.getFileName().toString()).find(), module.toString());
	}
}
