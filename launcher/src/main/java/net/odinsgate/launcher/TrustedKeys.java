package net.odinsgate.launcher;

import java.security.PublicKey;
import java.util.List;

/**
 * The public keys a release manifest may be signed with: the primary and Sam's offline backup, both made on his own
 * PC on 2026-10-30 and built in here with the 1.0.0 release; tests make their own key pairs. Rotation needs no new
 * installer to be planned for: a manifest signed by the backup key verifies like any other, and shipping new jars
 * that pin a new primary is just a release.
 */
public final class TrustedKeys
{
	/** base64 SubjectPublicKeyInfo DER, one per key, primary first, the backup last. */
	private static final List<String> SPKI_BASE64 = List.of(
		"MCowBQYDK2VwAyEAoXNZypWro8tqPi6YuSDLkz/Wfba7JU3Vc2sCSc9QB1E=",
		"MCowBQYDK2VwAyEAnWYvWelLc6uLP0Pv3nfaq/t5QPpZSDGnintu6EmU400=");

	public static List<PublicKey> trusted()
	{
		return SPKI_BASE64.stream().map(Signatures::publicKey).toList();
	}

	private TrustedKeys()
	{
	}
}
