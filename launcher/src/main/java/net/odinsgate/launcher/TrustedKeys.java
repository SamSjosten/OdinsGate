package net.odinsgate.launcher;

import java.security.PublicKey;
import java.util.List;

/**
 * The public keys a release manifest may be signed with: the primary and Sam's offline backup, both made on his own
 * PC. The list is empty until the release PR adds them, so until then no remote manifest verifies for anyone; tests
 * make their own key pairs. Rotation needs no new installer to be planned for: a manifest signed by the backup key
 * verifies like any other, and shipping new jars that pin a new primary is just a release.
 */
public final class TrustedKeys
{
	/** base64 SubjectPublicKeyInfo DER, one per key, primary first. */
	private static final List<String> SPKI_BASE64 = List.of();

	public static List<PublicKey> trusted()
	{
		return SPKI_BASE64.stream().map(Signatures::publicKey).toList();
	}

	private TrustedKeys()
	{
	}
}
