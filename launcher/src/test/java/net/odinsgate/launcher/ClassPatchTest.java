package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The bytecode mechanics of one class's patch, on ASM-built fakes: exact counts, host-check scope, method stopping. */
public class ClassPatchTest
{
	private static boolean holds(byte[] bytes, String constant)
	{
		// Constant-pool strings are stored as modified UTF-8; for ASCII constants a raw scan finds them.
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(constant);
	}

	@Test
	public void itReplacesTheKeyEverywhereTheScanCountedIt() throws Exception
	{
		byte[] original = FakeClient.injectedClient();
		assertTrue(holds(original, FakeClient.CLIENT_KEY));
		ClassPatch patch = new ClassPatch(FakeClient.CLIENT);
		// The fake holds the key twice: a constant field and an ldc, as the real injected client does.
		patch.addRule(new ClassPatch.Rule("server key", FakeClient.CLIENT_KEY, FakeClient.OUR_MODULUS,
			null, false, true, 2));
		byte[] patched = patch.apply(original);
		assertFalse(holds(patched, FakeClient.CLIENT_KEY), "the client's key is still there");
		assertTrue(holds(patched, FakeClient.OUR_MODULUS), "our key did not go in");
	}

	@Test
	public void aCountThatDoesNotMatchStopsEverything()
	{
		ClassPatch patch = new ClassPatch(FakeClient.CLIENT);
		patch.addRule(new ClassPatch.Rule("server key", FakeClient.CLIENT_KEY, FakeClient.OUR_MODULUS,
			null, false, true, 1)); // the fake has two, not one
		PatchException thrown = assertThrows(PatchException.class, () -> patch.apply(FakeClient.injectedClient()));
		assertTrue(thrown.getMessage().contains("server key"), thrown.getMessage());
	}

	@Test
	public void theHostCheckIsReplacedOnlyWhereEndsWithTakesIt() throws Exception
	{
		ClassPatch patch = new ClassPatch(FakeClient.CLIENT);
		patch.addRule(new ClassPatch.Rule("host check", Preflight.LOCALHOST, "", null, true, false, 1));
		byte[] patched = patch.apply(FakeClient.injectedClient());
		assertFalse(holds(patched, Preflight.LOCALHOST));
	}

	@Test
	public void stoppingANonVoidMethodIsRefused()
	{
		ClassPatch patch = new ClassPatch(FakeClient.CLIENT);
		patch.returnAtOnce("key()Ljava/lang/String;");
		PatchException thrown = assertThrows(PatchException.class, () -> patch.apply(FakeClient.injectedClient()));
		assertTrue(thrown.getMessage().contains("does not return void"), thrown.getMessage());
	}

	@Test
	public void aMethodToStopThatIsNotThereIsRefused()
	{
		ClassPatch patch = new ClassPatch(FakeClient.CLIENT);
		patch.returnAtOnce("ghost()V");
		PatchException thrown = assertThrows(PatchException.class, () -> patch.apply(FakeClient.injectedClient()));
		assertTrue(thrown.getMessage().contains("not found"), thrown.getMessage());
	}
}
