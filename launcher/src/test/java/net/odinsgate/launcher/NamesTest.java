package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Nothing a friend can see may contain RuneScape, OSRS, Jagex, -scape or RSPS, in any case: not the app's name, not
 * a folder it makes, not a file in its releases. Sam's Linode rule (2026-10-28). Checked here on the Names
 * constants, on every file name in this module and its packaging folder, and on the packaging files that can't
 * read Names (the Inno Setup script, package.gradle, the notices); ImageScan checks every built app image the same
 * way.
 */
public class NamesTest
{
	@Test
	public void everyFriendVisibleNameIsClean()
	{
		for (String name : List.of(Names.APP_NAME, Names.DIR_CAPITALIZED, Names.DIR_LOWERCASE, Names.BUNDLE_ID,
			Names.VENDOR, Names.IMAGE_NAME, Names.WINDOWS_INSTALLER, Names.MAC_INSTALLER_ARM64,
			Names.MAC_INSTALLER_X64))
		{
			assertFalse(Names.BANNED.matcher(name).find(), name);
		}
		assertTrue(Names.BANNED.matcher("OldSchoolRuneScape").find());
		assertTrue(Names.BANNED.matcher("my-rsps-client").find());
	}

	@Test
	public void thePackageIsOursNotTheServers()
	{
		String packageName = Names.class.getPackageName();
		assertTrue(packageName.startsWith("net.odinsgate"), packageName);
		assertFalse(Names.BANNED.matcher(packageName).find(), packageName);
	}

	@Test
	public void noFileInThisModuleCarriesABannedName() throws IOException
	{
		Path module = module();
		for (String folder : List.of("src", "package"))
		{
			try (Stream<Path> files = Files.walk(module.resolve(folder)))
			{
				files.forEach(file ->
					assertFalse(Names.BANNED.matcher(file.getFileName().toString()).find(), file.toString()));
			}
		}
		assertFalse(Names.BANNED.matcher(module.getFileName().toString()).find(), module.toString());
	}

	@Test
	public void theInstallerScriptUsesTheNames() throws IOException
	{
		String script = Files.readString(module().resolve("package/windows/OdinsGate.iss"), StandardCharsets.UTF_8);
		assertTrue(script.contains("\nAppName=" + Names.APP_NAME + "\n"), "AppName");
		assertTrue(script.contains("\nAppPublisher=" + Names.VENDOR + "\n"), "AppPublisher");
		assertTrue(script.contains("\nDefaultGroupName=" + Names.APP_NAME + "\n"), "the Start menu folder");
		assertTrue(script.contains("\nDefaultDirName={autopf}\\" + Names.IMAGE_NAME + "\n"), "the install folder");
		assertTrue(script.contains("\nUninstallDisplayName=" + Names.APP_NAME + "\n"), "Add or remove programs");
		assertTrue(script.contains("{app}\\" + Names.IMAGE_NAME + ".exe"), "the executable");
		String installer = Names.WINDOWS_INSTALLER.substring(0, Names.WINDOWS_INSTALLER.length() - ".exe".length());
		assertTrue(script.contains("\nOutputBaseFilename=" + installer + "\n"), "the installer's name");
		assertTrue(script.contains("\nPrivilegesRequired=lowest\n"), "per user, no admin rights");
	}

	/** package.gradle names the app, its id and the disk images itself (it reads no Java); they must be Names'. */
	@Test
	public void thePackagingScriptUsesTheNames() throws IOException
	{
		String script = Files.readString(module().resolve("package.gradle"), StandardCharsets.UTF_8);
		assertTrue(script.contains("def appName = '" + Names.IMAGE_NAME + "'"), "the image's name");
		assertTrue(script.contains("'--vendor', \"" + Names.VENDOR + "\""), "the vendor");
		assertTrue(script.contains("'--mac-package-identifier', '" + Names.BUNDLE_ID + "'"), "the bundle id");
		assertTrue(script.contains("'--mac-package-name', \"" + Names.APP_NAME + "\""), "the Mac app's shown name");
		assertTrue(Names.APP_NAME.length() <= 16, "jpackage caps the Mac package name at 16 characters");
		assertTrue(script.contains("'" + Names.MAC_INSTALLER_ARM64 + "'"), "the Apple chip disk image");
		assertTrue(script.contains("'" + Names.MAC_INSTALLER_X64 + "'"), "the Intel disk image");
	}

	@Test
	public void thePackagingFilesHoldNoBannedWord() throws IOException
	{
		for (String file : List.of("package/windows/OdinsGate.iss", "package/mac/entitlements.plist", "package.gradle",
			"package/allowed-jars.txt", "THIRD-PARTY-NOTICES.txt"))
		{
			String text = Files.readString(module().resolve(file), StandardCharsets.UTF_8);
			assertFalse(Names.BANNED.matcher(text).find(), file);
		}
	}

	/** Gradle runs tests with the project folder (launcher/) as the working directory. */
	private static Path module()
	{
		Path module = Path.of("").toAbsolutePath();
		assertTrue(Files.isDirectory(module.resolve("src")), "not running from launcher/: " + module);
		return module;
	}
}
