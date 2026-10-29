package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loader end to end: it loads the ASM-built fake client and applies the scan's plan as each class loads, so the
 * patches are seen through their effects — the key is ours, any host passes the check, the gamepack method does
 * nothing, and the Jagex host is blanked. No RuneLite or Jagex jar is ever present.
 */
public class PatchingClassLoaderTest
{
	@TempDir
	Path dir;

	private PatchingClassLoader goodLoader() throws Exception
	{
		Preflight scan = Preflight.scan(FakeClient.jars(dir), FakeClient.OUR_MODULUS);
		scan.requireComplete();
		return new PatchingClassLoader(FakeClient.jars(dir), scan.patches());
	}

	@Test
	public void theKeyAndTheHostCheckArePatchedAsTheClassLoads() throws Exception
	{
		try (PatchingClassLoader loader = goodLoader())
		{
			Class<?> client = loader.loadClass("net.odinsgate.testfake.Client");
			assertEquals(FakeClient.OUR_MODULUS, client.getMethod("key").invoke(null));
			// The host check became endsWith(""), which every host passes.
			assertEquals(true, client.getMethod("hostOk", String.class).invoke(null, "any.evil.example"));
			// A method no rule touches is unchanged.
			assertEquals("10001", client.getMethod("exponent").invoke(null));
		}
	}

	@Test
	public void theGamepackMethodIsStoppedAndTheJagexHostBlanked() throws Exception
	{
		try (PatchingClassLoader loader = goodLoader())
		{
			Class<?> clientLoader = loader.loadClass("net.runelite.client.rs.ClientLoader");
			Class<?> rsConfig = loader.loadClass("net.runelite.client.rs.RSConfig");
			Object instance = clientLoader.getDeclaredConstructor().newInstance();

			// Unpatched, updateVanilla throws; patched it returns at once.
			Method updateVanilla = clientLoader.getMethod("updateVanilla", rsConfig);
			assertDoesNotThrow(() -> updateVanilla.invoke(instance, new Object[]{null}));

			clientLoader.getMethod("downloadConfig").invoke(instance);
			assertEquals("", clientLoader.getField("lastHost").get(null));
		}
	}

	@Test
	public void aClassThatDoesNotFitThePlanFailsToLoadWithAClearCause() throws Exception
	{
		Map<String, ClassPatch> badPlan = new HashMap<>();
		ClassPatch patch = new ClassPatch(FakeClient.CLIENT);
		patch.addRule(new ClassPatch.Rule("server key", FakeClient.CLIENT_KEY, FakeClient.OUR_MODULUS,
			null, false, true, 99)); // the class holds the key twice, never 99 times
		badPlan.put(FakeClient.CLIENT, patch);
		try (PatchingClassLoader loader = new PatchingClassLoader(FakeClient.jars(dir), badPlan))
		{
			ClassNotFoundException thrown = assertThrows(ClassNotFoundException.class,
				() -> loader.loadClass("net.odinsgate.testfake.Client"));
			assertInstanceOf(PatchException.class, thrown.getCause());
			assertTrue(thrown.getMessage().contains("server key"), thrown.getMessage());
		}
	}
}
