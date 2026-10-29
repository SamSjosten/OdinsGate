package net.odinsgate.launcher;

import java.io.IOException;
import java.io.InputStream;

/**
 * The committed copies of RuneLite's real revision-241 release files, exactly as static.runelite.net serves them:
 * the bootstrap, and the raw SHA256withRSA signature RuneLite published next to it. They let the tests run the
 * production verify-and-parse path against the real thing without the network.
 */
final class Fixtures
{
	/** The pinned static.runelite.net commit for revision 241. */
	static final String REAL_COMMIT = "1e634e64717418713898b06061a189ec1cdf948d";
	static final String REAL_SHA256 = "25f22ff874ddf8c4016c8d03150c24920cdaa536ae7ad44aed36173c826c0b2a";
	static final Manifest.Pin REAL_PIN = new Manifest.Pin(241, "1.13.4", REAL_COMMIT, REAL_SHA256);

	private Fixtures()
	{
	}

	static byte[] realBootstrap()
	{
		return resource("bootstrap-241.json");
	}

	static byte[] realBootstrapSignature()
	{
		return resource("bootstrap-241.json.sha256");
	}

	static byte[] resource(String name)
	{
		try (InputStream in = Fixtures.class.getResourceAsStream(name))
		{
			if (in == null)
			{
				throw new AssertionError("no test resource " + name);
			}
			return in.readAllBytes();
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}
}
