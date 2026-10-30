package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The real keys live on Sam's PC; these are made here and thrown away, which is the only place keys may be made. */
public class SignaturesTest
{
	private static final KeyPair PRIMARY = generate();
	private static final KeyPair BACKUP = generate();
	private static final byte[] DATA = ManifestTest.good().getBytes(StandardCharsets.UTF_8);
	private static final String SIGNATURE = sign(PRIMARY, DATA);

	private static KeyPair generate()
	{
		try
		{
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private static String sign(KeyPair key, byte[] data)
	{
		try
		{
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(key.getPrivate());
			signer.update(data);
			return Base64.getEncoder().encodeToString(signer.sign());
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void theSigningKeyVerifies()
	{
		assertTrue(Signatures.verify(DATA, SIGNATURE, List.of(PRIMARY.getPublic())));
	}

	@Test
	public void theBackupKeyIsTriedToo()
	{
		assertTrue(Signatures.verify(DATA, SIGNATURE, List.of(BACKUP.getPublic(), PRIMARY.getPublic())));
	}

	@Test
	public void aTrailingNewlineOnTheSignatureFileIsFine()
	{
		assertTrue(Signatures.verify(DATA, SIGNATURE + "\n", List.of(PRIMARY.getPublic())));
	}

	@Test
	public void tamperedDataFails()
	{
		byte[] tampered = DATA.clone();
		tampered[tampered.length / 2] ^= 1;
		assertFalse(Signatures.verify(tampered, SIGNATURE, List.of(PRIMARY.getPublic())));
	}

	@Test
	public void theWrongKeyFails()
	{
		assertFalse(Signatures.verify(DATA, SIGNATURE, List.of(BACKUP.getPublic())));
	}

	@Test
	public void anEmptyKeyListTrustsNothing()
	{
		assertFalse(Signatures.verify(DATA, SIGNATURE, List.of()));
	}

	@Test
	public void theBuiltInKeysAreSamsTwoPrimaryFirst()
	{
		// The public halves Sam made on 2026-10-30: the primary signs releases, the backup stays offline. Their private
		// halves are nowhere a test could reach, so nothing signed here verifies against them.
		List<PublicKey> trusted = TrustedKeys.trusted();
		assertEquals(2, trusted.size());
		assertEquals("MCowBQYDK2VwAyEAoXNZypWro8tqPi6YuSDLkz/Wfba7JU3Vc2sCSc9QB1E=",
			Base64.getEncoder().encodeToString(trusted.get(0).getEncoded()));
		assertEquals("MCowBQYDK2VwAyEAnWYvWelLc6uLP0Pv3nfaq/t5QPpZSDGnintu6EmU400=",
			Base64.getEncoder().encodeToString(trusted.get(1).getEncoded()));
		assertFalse(Signatures.verify(DATA, SIGNATURE, trusted));
	}

	@Test
	public void aBrokenSignatureIsFalseNeverAnException()
	{
		assertFalse(Signatures.verify(DATA, "not base64 at all!", List.of(PRIMARY.getPublic())));
		byte[] signature = Base64.getDecoder().decode(SIGNATURE);
		assertFalse(Signatures.verify(DATA,
			Base64.getEncoder().encodeToString(Arrays.copyOf(signature, 32)), List.of(PRIMARY.getPublic())));
		assertFalse(Signatures.verify(DATA, "", List.of(PRIMARY.getPublic())));
	}

	@Test
	public void aPublicKeyRoundTripsThroughItsBase64Der()
	{
		String spki = Base64.getEncoder().encodeToString(PRIMARY.getPublic().getEncoded());
		PublicKey parsed = Signatures.publicKey(spki);
		assertTrue(Signatures.verify(DATA, SIGNATURE, List.of(parsed)));
	}

	@Test
	public void whatIsntAKeyIsAProgrammingError()
	{
		assertThrows(IllegalArgumentException.class, () -> Signatures.publicKey("bm90IGEga2V5"));
		assertThrows(IllegalArgumentException.class, () -> Signatures.publicKey("///"));
	}

	@Test
	public void verifiedParsesOnlyWhatVerifies() throws Exception
	{
		Manifest manifest = Manifest.verified(DATA, SIGNATURE, List.of(PRIMARY.getPublic()));
		assertEquals("0.1.0", manifest.version);

		ManifestException refused = assertThrows(ManifestException.class,
			() -> Manifest.verified(DATA, SIGNATURE, List.of(BACKUP.getPublic())));
		assertTrue(refused.getMessage().contains("signature"));
	}

	@Test
	public void aVerifiedSignatureDoesntExcuseABadManifest()
	{
		byte[] garbage = "{ \"format\": 1 }".getBytes(StandardCharsets.UTF_8);
		String signed = sign(PRIMARY, garbage);
		assertThrows(ManifestException.class, () -> Manifest.verified(garbage, signed, List.of(PRIMARY.getPublic())));
	}
}
