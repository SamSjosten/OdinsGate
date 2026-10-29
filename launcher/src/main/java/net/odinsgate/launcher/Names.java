package net.odinsgate.launcher;

/**
 * Every name a friend can see, in one place so NamesTest can hold them all to the rule: never RuneScape, OSRS,
 * Jagex, -scape or RSPS, in any case. Sam picked the name (2026-10-29): "Odin's Gate" in text, OdinsGate or
 * odinsgate for files and ids. Installers, bundle ids and window titles built in later PRs take their names from
 * here, not from string literals of their own.
 */
public final class Names
{
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

	private Names()
	{
	}
}
