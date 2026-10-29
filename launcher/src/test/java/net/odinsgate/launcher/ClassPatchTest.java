package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	public void theGamePortIsReplacedInTheFieldAndTheLdc() throws Exception
	{
		ClassPatch patch = new ClassPatch(FakeClient.CLIENT);
		// The fake holds the port twice, as a constant field and an ldc, the two ways javac compiles a constant.
		patch.addRule(new ClassPatch.Rule("game port", Preflight.DEFAULT_GAME_PORT, 44594, null, false, true, 2));
		Class<?> client = define(patch.apply(FakeClient.injectedClient()));
		assertEquals(44594, client.getField("PORT").get(null));
		assertEquals(44594, client.getMethod("port").invoke(null));
		// The string rules were not touched by an int rule: the key is still the client's.
		assertEquals(FakeClient.CLIENT_KEY, client.getMethod("key").invoke(null));
	}

	@Test
	public void aRuleMustReplaceLikeWithLike()
	{
		assertThrows(IllegalArgumentException.class,
			() -> new ClassPatch.Rule("mixed", Preflight.DEFAULT_GAME_PORT, "44594", null, false, true, 1));
		assertThrows(IllegalArgumentException.class,
			() -> new ClassPatch.Rule("mixed", "a", 1, null, false, true, 1));
	}

	/** Defines the class in a loader of its own, so a test can call the patched methods. */
	private static Class<?> define(byte[] bytes)
	{
		return new ClassLoader(ClassPatchTest.class.getClassLoader())
		{
			Class<?> define()
			{
				return defineClass(null, bytes, 0, bytes.length);
			}
		}.define();
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
