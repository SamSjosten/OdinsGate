package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class VersionsTest
{
	@Test
	public void versionsCompareNumberByNumber()
	{
		assertEquals(0, Versions.compare("0.1.0", "0.1.0"));
		assertEquals(0, Versions.compare("1.2", "1.2.0"));
		assertTrue(Versions.isNewer("0.10.0", "0.9.0"), "ten is more than nine, whatever the text order says");
		assertTrue(Versions.isNewer("1.0.0", "0.99.99"));
		assertTrue(Versions.isNewer("0.1.1", "0.1"));
		assertFalse(Versions.isNewer("0.1", "0.1.1"));
		assertTrue(Versions.isNewer("0.0.010", "0.0.9"), "leading zeros count for nothing");
		assertTrue(Versions.isNewer("123456789012345678901234567890", "2"), "a number of any length");
	}

	@Test
	public void devIsNewerThanAnyRelease()
	{
		assertTrue(Versions.isNewer("dev", "99.0.0"));
		assertFalse(Versions.isNewer("99.0.0", "dev"));
		assertEquals(0, Versions.compare("dev", "dev"));
	}
}
