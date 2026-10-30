package net.odinsgate.launcher;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Ed25519 checks for the manifest. The keys are made and kept on Sam's own PC, never in a session or a VM; only
 * their public halves appear here (see {@link TrustedKeys}). A signature file is the base64 of the 64-byte Ed25519
 * signature over the manifest's exact bytes, one line.
 */
public final class Signatures
{
	private Signatures()
	{
	}

	/**
	 * A public key from the base64 of its SubjectPublicKeyInfo DER, which is what
	 * {@code openssl pkey -pubout -outform DER | base64} prints.
	 */
	public static PublicKey publicKey(String base64Spki)
	{
		try
		{
			byte[] der = Base64.getDecoder().decode(base64Spki.trim());
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
		}
		catch (IllegalArgumentException | GeneralSecurityException e)
		{
			// A trusted key is compiled in or made by a test, so a bad one is a programming error, not input.
			throw new IllegalArgumentException("not a base64 Ed25519 SubjectPublicKeyInfo: " + e.getMessage(), e);
		}
	}

	/**
	 * Whether any of the trusted keys signed these exact bytes. Anything wrong with the signature - bad base64, the
	 * wrong length, the wrong key, tampered data - is false, never an exception: the caller's message to the friend
	 * is the same for all of them, and an empty key list trusts nothing.
	 */
	public static boolean verify(byte[] data, String signatureBase64, List<PublicKey> trustedKeys)
	{
		byte[] signature;
		try
		{
			signature = Base64.getDecoder().decode(signatureBase64.trim());
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
		for (PublicKey key : trustedKeys)
		{
			try
			{
				Signature verifier = Signature.getInstance("Ed25519");
				verifier.initVerify(key);
				verifier.update(data);
				if (verifier.verify(signature))
				{
					return true;
				}
			}
			catch (GeneralSecurityException e)
			{
				// A malformed signature for this key; the next trusted key still gets its try.
			}
		}
		return false;
	}
}
