package net.odinsgate.launcher;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V21;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Stand-ins for the pinned RuneLite jars, built here with ASM so the loader tests never touch a real RuneLite or
 * Jagex jar — none is ever in git. The fakes carry exactly the patch points {@link Preflight} looks for and nothing
 * of anyone's real code: the server key next to the exponent "10001", a host check through {@code String.endsWith},
 * a {@code ClientLoader} with {@code updateVanilla} and a {@code downloadConfig} holding ".jagex.com", and the two
 * classes that read our system properties. The patch targets ({@code key()}, {@code port()}, {@code hostOk()},
 * {@code lastHost}) are observable so a test can load the fake through {@link PatchingClassLoader} and see each
 * change take, and a fake main class ({@link #MAIN}) records what it saw for the {@link Loader} tests.
 */
final class FakeClient
{
	/** The injected client's fake class, in the jar whose name starts "injected-client". */
	static final String CLIENT = "net/odinsgate/testfake/Client";
	/** A 512-digit (2048-bit) hex key, standing for the client's own; longer than any other string in the jars. */
	static final String CLIENT_KEY = "a3".repeat(256);
	/** Our server's modulus: 256 digits (1024-bit), fewer than the client's, so the length check passes. */
	static final String OUR_MODULUS = "9c".repeat(128);
	/** A fake RuneLite main class, in the client jar: its main records the key, port and arguments it saw. */
	static final String MAIN = "net/odinsgate/testfake/Main";
	/** The same, but its main throws after recording, as a RuneLite that fails to start would. */
	static final String THROWING_MAIN = "net/odinsgate/testfake/ThrowingMain";
	/** The system properties the fake main sets: the (patched) key and port it read from Client, and its args. */
	static final String KEY_PROPERTY = "odinsgate.testfake.key";
	static final String PORT_PROPERTY = "odinsgate.testfake.port";
	static final String ARGS_PROPERTY = "odinsgate.testfake.args";

	private FakeClient()
	{
	}

	/** The two jars in classpath order: the injected client, then the RuneLite classes. */
	static List<Path> jars(Path dir) throws IOException
	{
		return List.of(injectedJar(dir, injectedClient()), clientJar(dir, clientLoader()));
	}

	/** The injected-client jar alone, holding {@code clientBytes} as its one class. */
	static Path injectedJar(Path dir, byte[] clientBytes) throws IOException
	{
		Path injected = dir.resolve("injected-client-1.13.4.jar");
		writeJar(injected, Map.of(CLIENT, clientBytes));
		return injected;
	}

	/** The RuneLite-classes jar alone, holding {@code clientLoaderBytes} as its ClientLoader. */
	static Path clientJar(Path dir, byte[] clientLoaderBytes) throws IOException
	{
		Path client = dir.resolve("client-1.13.4.jar");
		writeJar(client, Map.of(
			Preflight.CLIENT_LOADER, clientLoaderBytes,
			"net/runelite/client/rs/RSConfig", trivial("net/runelite/client/rs/RSConfig"),
			Preflight.MODULE, readsProperty(Preflight.MODULE, Preflight.HTTP_SERVICE_URL),
			Preflight.RTCONF_LOADER, readsProperty(Preflight.RTCONF_LOADER, Preflight.RTCONF),
			MAIN, mainClass(MAIN, false),
			THROWING_MAIN, mainClass(THROWING_MAIN, true)));
		return client;
	}

	/** The injected client as it would be with no game port constant at all: fine on the default port only. */
	static byte[] injectedClientWithoutPort()
	{
		return injectedClient(false);
	}

	/** A ClientLoader whose {@code downloadConfig} does not exist and whose {@code updateVanilla} is absent too. */
	static byte[] clientLoaderMissingDownloadConfig()
	{
		ClassWriter cw = newClass(Preflight.CLIENT_LOADER);
		defaultCtor(cw, Preflight.CLIENT_LOADER);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A RuneLite-classes jar whose RuneLiteModule does not read the http-service URL property. */
	static Path clientJarWithDeafModule(Path dir) throws IOException
	{
		Path client = dir.resolve("client-1.13.4.jar");
		writeJar(client, Map.of(
			Preflight.CLIENT_LOADER, clientLoader(),
			"net/runelite/client/rs/RSConfig", trivial("net/runelite/client/rs/RSConfig"),
			Preflight.MODULE, readsProperty(Preflight.MODULE, "something.else"),
			Preflight.RTCONF_LOADER, readsProperty(Preflight.RTCONF_LOADER, Preflight.RTCONF)));
		return client;
	}

	static byte[] injectedClient()
	{
		return injectedClient(true);
	}

	private static byte[] injectedClient(boolean withPort)
	{
		ClassWriter cw = newClass(FakeClient.CLIENT);
		// public static final String KEY = <client key>  — a constant field, like the real injected client.
		cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "KEY", "Ljava/lang/String;", null, CLIENT_KEY).visitEnd();
		defaultCtor(cw, "net/odinsgate/testfake/Client");
		// public static String key() { return "<client key>"; }  — an ldc use of the same constant.
		returnsString(cw, "key", CLIENT_KEY);
		if (withPort)
		{
			// public static final int PORT = 43594; public static int port() { return 43594; }  — a field and an ldc.
			cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "PORT", "I", null, Preflight.DEFAULT_GAME_PORT)
				.visitEnd();
			MethodVisitor port = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "port", "()I", null, null);
			port.visitCode();
			port.visitLdcInsn(Preflight.DEFAULT_GAME_PORT);
			port.visitInsn(IRETURN);
			port.visitMaxs(0, 0);
			port.visitEnd();
		}
		// public static String exponent() { return "10001"; }  — the marker the scan keys on.
		returnsString(cw, "exponent", Preflight.EXPONENT);
		// public static boolean hostOk(String h) { return h.endsWith("127.0.0.1"); }  — the host check.
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hostOk", "(Ljava/lang/String;)Z", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitLdcInsn(Preflight.LOCALHOST);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false);
		mv.visitInsn(IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	static byte[] clientLoader()
	{
		return clientLoader(Preflight.JAGEX_HOST);
	}

	/** A ClientLoader whose downloadConfig checks a Jagex host other than the one the launcher patches. */
	static byte[] clientLoaderWithRenamedJagexHost()
	{
		return clientLoader(".jagex.net");
	}

	private static byte[] clientLoader(String host)
	{
		ClassWriter cw = newClass(Preflight.CLIENT_LOADER);
		cw.visitField(ACC_PUBLIC | ACC_STATIC, "lastHost", "Ljava/lang/String;", null, null).visitEnd();
		defaultCtor(cw, Preflight.CLIENT_LOADER);
		// public void updateVanilla(RSConfig c) { throw new RuntimeException("gamepack"); }  — stopped by the patch.
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "updateVanilla",
			"(Lnet/runelite/client/rs/RSConfig;)V", null, null);
		mv.visitCode();
		mv.visitTypeInsn(NEW, "java/lang/RuntimeException");
		mv.visitInsn(DUP);
		mv.visitLdcInsn("gamepack download attempted");
		mv.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
		mv.visitInsn(ATHROW);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		// public RSConfig downloadConfig() { lastHost = ".jagex.com"; return null; }  — the patch makes the host "".
		mv = cw.visitMethod(ACC_PUBLIC, "downloadConfig", "()Lnet/runelite/client/rs/RSConfig;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(host);
		mv.visitFieldInsn(PUTSTATIC, Preflight.CLIENT_LOADER, "lastHost", "Ljava/lang/String;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class that really reads the property: {@code property()} returns {@code System.getProperty(name)}. */
	private static byte[] readsProperty(String internalName, String property)
	{
		ClassWriter cw = newClass(internalName);
		defaultCtor(cw, internalName);
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "property", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(property);
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;",
			false);
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class that only holds the property's name, in a constant field nothing reads, and reads another one. */
	private static byte[] holdsPropertyUnread(String internalName, String property)
	{
		ClassWriter cw = newClass(internalName);
		cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "NAME", "Ljava/lang/String;", null, property).visitEnd();
		defaultCtor(cw, internalName);
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "property", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn("something.else");
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;",
			false);
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A RuneLite-classes jar whose RuneLiteModule holds the http-service URL property's name but never reads it. */
	static Path clientJarWithUnreadProperty(Path dir) throws IOException
	{
		Path client = dir.resolve("client-1.13.4.jar");
		writeJar(client, Map.of(
			Preflight.CLIENT_LOADER, clientLoader(),
			"net/runelite/client/rs/RSConfig", trivial("net/runelite/client/rs/RSConfig"),
			Preflight.MODULE, holdsPropertyUnread(Preflight.MODULE, Preflight.HTTP_SERVICE_URL),
			Preflight.RTCONF_LOADER, readsProperty(Preflight.RTCONF_LOADER, Preflight.RTCONF)));
		return client;
	}

	/**
	 * A main class as the loader would start RuneLite's: {@code main(String[])} sets {@link #KEY_PROPERTY} to
	 * {@code Client.key()}, {@link #PORT_PROPERTY} to {@code Client.port()} and {@link #ARGS_PROPERTY} to its
	 * arguments joined by spaces, then returns, or throws when {@code throwing}.
	 */
	private static byte[] mainClass(String internalName, boolean throwing)
	{
		ClassWriter cw = newClass(internalName);
		defaultCtor(cw, internalName);
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
		mv.visitCode();
		mv.visitLdcInsn(KEY_PROPERTY);
		mv.visitMethodInsn(INVOKESTATIC, CLIENT, "key", "()Ljava/lang/String;", false);
		setProperty(mv);
		mv.visitLdcInsn(PORT_PROPERTY);
		mv.visitMethodInsn(INVOKESTATIC, CLIENT, "port", "()I", false);
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
		setProperty(mv);
		mv.visitLdcInsn(ARGS_PROPERTY);
		mv.visitLdcInsn(" ");
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "join",
			"(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;", false);
		setProperty(mv);
		if (throwing)
		{
			mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
			mv.visitInsn(DUP);
			mv.visitLdcInsn("the fake client refused to start");
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V",
				false);
			mv.visitInsn(ATHROW);
		}
		else
		{
			mv.visitInsn(RETURN);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** With a name and a value on the stack: {@code System.setProperty(name, value)}, its result dropped. */
	private static void setProperty(MethodVisitor mv)
	{
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "setProperty",
			"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
		mv.visitInsn(POP);
	}

	private static byte[] trivial(String internalName)
	{
		ClassWriter cw = newClass(internalName);
		defaultCtor(cw, internalName);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassWriter newClass(String internalName)
	{
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(V21, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
		return cw;
	}

	private static void defaultCtor(ClassWriter cw, String owner)
	{
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void returnsString(ClassWriter cw, String method, String value)
	{
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, method, "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(value);
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void writeJar(Path jar, Map<String, byte[]> classes) throws IOException
	{
		try (OutputStream out = Files.newOutputStream(jar);
			JarOutputStream jos = new JarOutputStream(out))
		{
			for (Map.Entry<String, byte[]> entry : classes.entrySet())
			{
				jos.putNextEntry(new JarEntry(entry.getKey() + ".class"));
				jos.write(entry.getValue());
				jos.closeEntry();
			}
		}
	}
}
