package net.odinsgate.launcher;

/**
 * Launcher versions, dotted digits such as 0.1.0 or 1.2, compared number by number with a missing number as 0, so
 * 1.2 is 1.2.0 and 0.10.0 is newer than 0.9.0. {@code dev}, the version of a launcher run from classes rather than a
 * jar, counts as newer than any release, so a developer's run never hands off to a downloaded version and never hears
 * that it needs a newer installer.
 */
final class Versions
{
	static final String DEV = "dev";

	private Versions()
	{
	}

	/** Negative, zero or positive as {@code a} is older than, the same as, or newer than {@code b}. */
	static int compare(String a, String b)
	{
		if (a.equals(b))
		{
			return 0;
		}
		if (DEV.equals(a))
		{
			return 1;
		}
		if (DEV.equals(b))
		{
			return -1;
		}
		String[] as = a.split("\\.");
		String[] bs = b.split("\\.");
		for (int i = 0; i < Math.max(as.length, bs.length); i++)
		{
			int order = compareNumber(i < as.length ? as[i] : "0", i < bs.length ? bs[i] : "0");
			if (order != 0)
			{
				return order;
			}
		}
		return 0;
	}

	static boolean isNewer(String a, String b)
	{
		return compare(a, b) > 0;
	}

	/** Two digit strings of any length: leading zeros dropped, then the longer is larger, then the lexical order. */
	private static int compareNumber(String a, String b)
	{
		String x = a.replaceFirst("^0+(?=\\d)", "");
		String y = b.replaceFirst("^0+(?=\\d)", "");
		return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
	}
}
