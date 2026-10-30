package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RuneLite's bootstrap signature, checked against the real committed release files and the committed runelite.crt. */
public class RuneLiteSignatureTest
{
	@Test
	public void theCommittedCertificateVerifiesTheRealBootstrap()
	{
		byte[] bootstrap = Fixtures.realBootstrap();
		assertEquals(Fixtures.REAL_SHA256, RuneLiteCache.sha256(bootstrap));
		assertTrue(RuneLiteSignature.verify(bootstrap, Fixtures.realBootstrapSignature(),
			RuneLiteSignature.trustedKeys()));
	}

	@Test
	public void aTamperedBootstrapFails()
	{
		byte[] tampered = Fixtures.realBootstrap();
		tampered[tampered.length / 2] ^= 1;
		assertFalse(RuneLiteSignature.verify(tampered, Fixtures.realBootstrapSignature(),
			RuneLiteSignature.trustedKeys()));
	}

	@Test
	public void aBrokenSignatureIsFalseNeverAnException()
	{
		byte[] bootstrap = Fixtures.realBootstrap();
		List<PublicKey> keys = RuneLiteSignature.trustedKeys();
		assertFalse(RuneLiteSignature.verify(bootstrap, new byte[0], keys));
		assertFalse(RuneLiteSignature.verify(bootstrap, new byte[]{1, 2, 3}, keys));
		assertFalse(RuneLiteSignature.verify(bootstrap, bootstrap, keys));
		assertFalse(RuneLiteSignature.verify(bootstrap, Fixtures.realBootstrapSignature(), List.of()));
	}

	/**
	 * Like RuneLite's own launcher we use the certificate's key and ignore its validity window, so the pinned
	 * bootstrap keeps verifying after runelite.crt's notAfter day (April 2027). The fixture certificate expired in
	 * 2021; its signature over the fixture sentence must still verify.
	 */
	@Test
	public void anExpiredCertificateStillVerifies() throws Exception
	{
		X509Certificate expired = (X509Certificate) CertificateFactory.getInstance("X.509")
			.generateCertificate(new ByteArrayInputStream(Fixtures.resource("expired-test.crt")));
		assertTrue(expired.getNotAfter().toInstant().isBefore(Instant.now()), "the fixture certificate must be expired");
		byte[] data = "the launcher checks the signature, not the clock\n".getBytes(StandardCharsets.UTF_8);
		assertTrue(RuneLiteSignature.verify(data, Fixtures.resource("expired-test.sig"),
			List.of(expired.getPublicKey())));
	}

	@Test
	public void theTrustedKeysComeOutOfTheCommittedCertificate()
	{
		List<PublicKey> keys = RuneLiteSignature.trustedKeys();
		assertFalse(keys.isEmpty());
		assertTrue(keys.stream().allMatch(key -> "RSA".equals(key.getAlgorithm())));
	}
}
