package net.odinsgate.launcher;

import java.util.regex.Pattern;

/**
 * Every name a friend can see, in one place so NamesTest can hold them all to the rule: never RuneScape, OSRS,
 * Jagex, -scape or RSPS, in any case. Sam picked the name (2026-10-29): "Odin's Gate" in text, OdinsGate or
 * odinsgate for files and ids. Installers, bundle ids and window titles take their names from here, not from string
 * literals of their own; the Inno Setup script (package/windows/OdinsGate.iss) and package.gradle can't read Java,
 * so NamesTest holds them to these, and ImageScan holds every built app image to {@link #BANNED}.
 */
public final class Names
{
	/** What no friend-visible name may contain, in any case. */
	public static final Pattern BANNED = Pattern.compile("runescape|osrs|jagex|scape|rsps", Pattern.CASE_INSENSITIVE);

	/** The app's name wherever text allows it: the window title, the installer, the Dock and the menu. */
	public static final String APP_NAME = "Odin's Gate";

	/** The folder name on systems that show it capitalized (Windows' AppData, the Mac's Application Support). */
	public static final String DIR_CAPITALIZED = "OdinsGate";

	/** The folder name on Linux, where lowercase is the custom. */
	public static final String DIR_LOWERCASE = "odinsgate";

	/** The bundle and installer id: reverse of odinsgate.net, which the server already answers for. */
	public static final String BUNDLE_ID = "net.odinsgate.launcher";

	/** The publisher line in installers, until a bought certificate replaces it with Sam's legal name. */
	public static final String VENDOR = "Odin's Gate";

	/** The app image's name, which is its executable's (OdinsGate.exe) and its install folder's. */
	public static final String IMAGE_NAME = "OdinsGate";

	/** The Windows installer's file name, the same in every release, so the download page's link never changes. */
	public static final String WINDOWS_INSTALLER = "OdinsGate-Setup.exe";

	/** The Mac disk image for Apple chips (M1 and newer), the same name in every release. */
	public static final String MAC_INSTALLER_ARM64 = "OdinsGate-mac-arm64.dmg";

	/** The Mac disk image for Intel Macs, the same name in every release. */
	public static final String MAC_INSTALLER_X64 = "OdinsGate-mac-x64.dmg";

	private Names()
	{
	}
}
