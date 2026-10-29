package net.odinsgate.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;

/**
 * RuneLite's own signature over its bootstrap: SHA256withRSA, published as the raw signature bytes in
 * bootstrap.json.sha256 next to each bootstrap.json. The certificate is RuneLite's public runelite.crt (BSD-2, from
 * the runelite/launcher repository), committed here so the check needs nothing from the network. Only the key inside
 * it is used: like RuneLite's own launcher we don't check the certificate's validity window, so a bootstrap pinned
 * today still verifies after the certificate's notAfter day (April 2027), and our pinned SHA-256 guards the pin
 * either way - this signature proves the file is RuneLite's, the pin proves it is the one we chose.
 */
public final class RuneLiteSignature
{
	private RuneLiteSignature()
	{
	}

	/** The keys inside launcher/src/main/resources' runelite.crt (every certificate in the file counts). */
	public static List<PublicKey> trustedKeys()
	{
		try (InputStream certificates = RuneLiteSignature.class.getResourceAsStream("runelite.crt"))
		{
			if (certificates == null)
			{
				throw new IllegalStateException("no runelite.crt in the resources");
			}
			return CertificateFactory.getInstance("X.509").generateCertificates(certificates).stream()
				.map(Certificate::getPublicKey).toList();
		}
		catch (IOException | GeneralSecurityException e)
		{
			// The resource is compiled in, so failing to read it is a build problem, not input.
			throw new IllegalStateException("can't read runelite.crt: " + e.getMessage(), e);
		}
	}

	/** Whether any of the keys signed these exact bytes. Anything wrong is false, never an exception. */
	public static boolean verify(byte[] data, byte[] signature, List<PublicKey> keys)
	{
		for (PublicKey key : keys)
		{
			try
			{
				Signature verifier = Signature.getInstance("SHA256withRSA");
				verifier.initVerify(key);
				verifier.update(data);
				if (verifier.verify(signature))
				{
					return true;
				}
			}
			catch (GeneralSecurityException e)
			{
				// A malformed signature for this key; the next key still gets its try.
			}
		}
		return false;
	}
}
