package net.odinsgate.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The launcher's in-memory read of the pinned RuneLite jars, done once before launch: it finds the four places the
 * loader must change so the client talks to our server and never a Jagex one, checks two more it relies on, and
 * stops the launch when any is missing. Nothing is written to disk and no jar is rewritten; the result is a plan of
 * {@link ClassPatch}es that {@link PatchingClassLoader} applies to those classes as they load.
 *
 * <p>The patch points, which are RSProx's, found by reading the bytecode rather than by name:
 * <ul>
 *   <li>the server's RSA key, in the one injected-client class that also holds the exponent {@code "10001"}; ours may
 *       not have more digits than the client's;</li>
 *   <li>{@code "127.0.0.1"} where {@code String.endsWith} takes it (the client's jav_config host check), made
 *       {@code ""} so our host passes;</li>
 *   <li>{@code ClientLoader.updateVanilla}, when the jar has it, made to return at once, so a forged config can never
 *       make the client download a gamepack (stopped before the host check below is relaxed);</li>
 *   <li>{@code ".jagex.com"} in {@code ClientLoader.downloadConfig}, when the jar has it, made {@code ""};</li>
 *   <li>and only when the server isn't on the default game port (the staging server, {@code --server}): the int
 *       constant {@value #DEFAULT_GAME_PORT} wherever the injected client holds it, made the server's port. RSProx
 *       never needs this because the client talks to RSProx on the default port; a direct connection must carry the
 *       port itself, since the jav_config and world list name hosts only.</li>
 * </ul>
 * And it checks, without patching, that {@code RuneLiteModule} reads {@code -Drunelite.http-service.url} (so the world
 * list is ours) and {@code RuntimeConfigLoader} reads {@code -Drunelite.rtconf} (so a local runtime config stands in
 * for RuneLite's remote one, which could otherwise mark our pin outdated).
 *
 * <p>Output names jars, classes, methods and counts or bit lengths only, never a class's bytes or the client's own
 * constants, so a log is safe to attach to a problem report.
 */
public final class Preflight
{
	static final String CLIENT_LOADER = "net/runelite/client/rs/ClientLoader";
	static final String DOWNLOAD_CONFIG = "downloadConfig()Lnet/runelite/client/rs/RSConfig;";
	static final String UPDATE_VANILLA = "updateVanilla(Lnet/runelite/client/rs/RSConfig;)V";
	static final String MODULE = "net/runelite/client/RuneLiteModule";
	static final String RTCONF_LOADER = "net/runelite/client/RuntimeConfigLoader";
	static final String HTTP_SERVICE_URL = "runelite.http-service.url";
	static final String RTCONF = "runelite.rtconf";
	static final String EXPONENT = "10001";
	static final String LOCALHOST = "127.0.0.1";
	static final String JAGEX_HOST = ".jagex.com";
	/** The game port the client is built for, the one it dials unless patched; our main server listens on it too. */
	static final int DEFAULT_GAME_PORT = 43594;
	private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");
	/** RuneLite's RSA keys are 512 hex digits (2048-bit); a server key constant is far longer than any other string. */
	private static final int KEY_DIGITS = 256;

	private final List<String> report = new ArrayList<>();
	private final List<String> missing = new ArrayList<>();
	private final Map<String, ClassPatch> patches = new LinkedHashMap<>();

	private Preflight()
	{
	}

	/** The classes to patch, by internal name, in the order the scan found them. */
	public Map<String, ClassPatch> patches()
	{
		return patches;
	}

	/** Every line the scan would log: what it found and will change. */
	public List<String> report()
	{
		return List.copyOf(report);
	}

	/** Each patch point that is not where the scan expected it; empty when the pin still fits our patches. */
	public List<String> missing()
	{
		return List.copyOf(missing);
	}

	public boolean complete()
	{
		return missing.isEmpty();
	}

	/** Throws with every missing patch point named, so the launcher can show exactly why it won't start. */
	public void requireComplete() throws PatchException
	{
		if (!missing.isEmpty())
		{
			throw new PatchException("the pinned RuneLite no longer fits our patches: " + String.join("; ", missing));
		}
	}

	private ClassPatch patch(String className)
	{
		return patches.computeIfAbsent(className, ClassPatch::new);
	}

	/** {@link #scan(List, String, int)} for a server on the default game port, where the port needs no patch. */
	public static Preflight scan(List<Path> jars, String modulus) throws IOException
	{
		return scan(jars, modulus, DEFAULT_GAME_PORT);
	}

	/**
	 * Scans the jars, in the bootstrap's (classpath) order, for the patch points, with {@code modulus} the server's
	 * RSA modulus in hex and {@code gamePort} the port the client must dial. The result's {@link #complete()} says
	 * whether every required point was found.
	 */
	public static Preflight scan(List<Path> jars, String modulus, int gamePort) throws IOException
	{
		if (!HEX.matcher(modulus).matches())
		{
			throw new IllegalArgumentException("the modulus must be hex");
		}
		if (gamePort < 1 || gamePort > 65535)
		{
			throw new IllegalArgumentException("not a port: " + gamePort);
		}
		Preflight scan = new Preflight();
		Set<String> seen = new HashSet<>();
		String injectedJar = null;
		int injectedClasses = 0;
		Map<String, List<String>> exponentClasses = new LinkedHashMap<>();
		Map<String, byte[]> exponentBytes = new LinkedHashMap<>();
		Map<String, int[]> localhost = new LinkedHashMap<>();
		Map<String, Integer> portUses = new LinkedHashMap<>();
		Map<String, byte[]> named = new LinkedHashMap<>();
		Map<String, String> jarOf = new LinkedHashMap<>();
		for (Path jar : jars)
		{
			String jarName = jar.getFileName().toString();
			boolean isInjected = jarName.startsWith("injected-client");
			if (isInjected)
			{
				injectedJar = jarName;
			}
			try (JarFile file = new JarFile(jar.toFile(), false))
			{
				for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); )
				{
					JarEntry entry = entries.nextElement();
					String name = entry.getName();
					if (!name.endsWith(".class") || name.startsWith("META-INF/"))
					{
						continue;
					}
					String className = name.substring(0, name.length() - ".class".length());
					// The class loader takes the first jar that has a class, so the scan does too.
					if (!seen.add(className))
					{
						continue;
					}
					byte[] bytes;
					try (InputStream in = file.getInputStream(entry))
					{
						bytes = in.readAllBytes();
					}
					if (List.of(CLIENT_LOADER, MODULE, RTCONF_LOADER).contains(className))
					{
						named.put(className, bytes);
						jarOf.put(className, jarName);
					}
					if (!isInjected)
					{
						continue;
					}
					injectedClasses++;
					Constants constants = Constants.of(new ClassReader(bytes));
					if (constants.strings().contains(EXPONENT))
					{
						exponentClasses.put(className, constants.strings().stream()
							.filter(s -> s.length() >= KEY_DIGITS && HEX.matcher(s).matches()).toList());
						exponentBytes.put(className, bytes);
					}
					if (constants.strings().contains(LOCALHOST))
					{
						localhost.put(className, countLocalhost(bytes));
					}
					if (constants.ints().contains(DEFAULT_GAME_PORT))
					{
						portUses.put(className, usesOf(bytes, DEFAULT_GAME_PORT));
					}
				}
			}
		}

		if (injectedJar == null)
		{
			scan.missing.add("no injected-client jar in the class path");
		}
		else
		{
			scan.report.add(injectedJar + ": " + injectedClasses + " classes scanned");
			scan.scanModulus(exponentClasses, exponentBytes, modulus);
			scan.scanLocalhost(localhost);
			scan.scanPort(portUses, gamePort);
		}
		scan.scanClientLoader(named.get(CLIENT_LOADER), jarOf.get(CLIENT_LOADER));
		scan.scanProperty(named.get(MODULE), MODULE, HTTP_SERVICE_URL, "the world list comes from our server");
		scan.scanProperty(named.get(RTCONF_LOADER), RTCONF_LOADER, RTCONF, "the runtime config is a local file");
		return scan;
	}

	private void scanModulus(Map<String, List<String>> exponentClasses, Map<String, byte[]> exponentBytes,
		String modulus)
	{
		List<String> candidates = exponentClasses.entrySet().stream()
			.filter(e -> !e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
		report.add("exponent \"10001\" in " + exponentClasses.size() + " class(es), " + candidates.size()
			+ " of them with a " + KEY_DIGITS + "+ digit hex constant");
		if (candidates.size() != 1)
		{
			missing.add("server key: need exactly one injected-client class with the exponent and a " + KEY_DIGITS
				+ "+ digit hex constant, found " + candidates.size());
			return;
		}
		String className = candidates.get(0);
		List<String> hex = exponentClasses.get(className);
		if (hex.size() != 1)
		{
			missing.add("server key: class " + className + " holds " + hex.size() + " long hex constants, need one");
			return;
		}
		String old = hex.get(0);
		int theirs = new BigInteger(old, 16).bitLength();
		int ours = new BigInteger(modulus, 16).bitLength();
		report.add("server key: class " + className + ", the client's key is " + theirs + " bits (" + old.length()
			+ " digits), ours " + ours + " bits (" + modulus.length() + " digits)");
		if (modulus.length() > old.length())
		{
			// The client reads the key's byte length from a 2-byte field; a longer modulus would not fit.
			missing.add("server key: ours has " + modulus.length() + " digits, more than the client's " + old.length());
			return;
		}
		int uses = usesOf(exponentBytes.get(className), old);
		if (uses == 0)
		{
			missing.add("server key: class " + className + " holds the constant but nothing uses it");
			return;
		}
		patch(className).addRule(new ClassPatch.Rule("server key", old, modulus, null, false, true, uses));
	}

	private void scanLocalhost(Map<String, int[]> localhost)
	{
		int endsWith = localhost.values().stream().mapToInt(counts -> counts[1]).sum();
		report.add("\"127.0.0.1\": taken by String.endsWith " + endsWith + " time(s) in " + localhost.size()
			+ " class(es)");
		if (endsWith == 0)
		{
			missing.add("host check: no \"127.0.0.1\" taken by String.endsWith in the injected client");
			return;
		}
		localhost.forEach((className, counts) ->
		{
			if (counts[1] > 0)
			{
				patch(className).addRule(
					new ClassPatch.Rule("host check", LOCALHOST, "", null, true, false, counts[1]));
			}
		});
	}

	private void scanPort(Map<String, Integer> portUses, int gamePort)
	{
		int uses = portUses.values().stream().mapToInt(Integer::intValue).sum();
		report.add("game port " + DEFAULT_GAME_PORT + ": " + uses + " use(s) in " + portUses.size() + " class(es), "
			+ (gamePort == DEFAULT_GAME_PORT ? "kept" : "made " + gamePort));
		if (gamePort == DEFAULT_GAME_PORT)
		{
			return;
		}
		if (uses == 0)
		{
			missing.add("game port: no " + DEFAULT_GAME_PORT + " constant in the injected client, so it can't be"
				+ " pointed at port " + gamePort);
			return;
		}
		portUses.forEach((className, count) ->
			patch(className).addRule(new ClassPatch.Rule("game port", DEFAULT_GAME_PORT, gamePort, null, false, true,
				count)));
	}

	private void scanClientLoader(byte[] bytes, String jarName)
	{
		if (bytes == null)
		{
			missing.add("no " + CLIENT_LOADER);
			return;
		}
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<String> methods = node.methods.stream().map(m -> m.name + m.desc).toList();
		int inDownloadConfig = 0;
		int otherJagexHosts = 0;
		for (MethodNode method : node.methods)
		{
			if (!(method.name + method.desc).equals(DOWNLOAD_CONFIG))
			{
				continue;
			}
			for (AbstractInsnNode insn : method.instructions)
			{
				if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String value))
				{
					continue;
				}
				if (JAGEX_HOST.equals(value))
				{
					inDownloadConfig++;
				}
				else if (value.toLowerCase(Locale.ROOT).contains("jagex"))
				{
					otherJagexHosts++;
				}
			}
		}
		boolean hasUpdateVanilla = methods.contains(UPDATE_VANILLA);
		report.add("ClientLoader (" + jarName + "): updateVanilla " + (hasUpdateVanilla ? "present, stopped" : "absent")
			+ ", \".jagex.com\" in downloadConfig "
			+ (inDownloadConfig == 0 ? "absent" : inDownloadConfig + " time(s)"));
		if (hasUpdateVanilla)
		{
			patch(CLIENT_LOADER).returnAtOnce(UPDATE_VANILLA);
		}
		if (!methods.contains(DOWNLOAD_CONFIG))
		{
			missing.add("ClientLoader has no " + DOWNLOAD_CONFIG);
			return;
		}
		if (otherJagexHosts > 0)
		{
			// No ".jagex.com" is fine (the public source has none), but another Jagex literal in the one method that
			// checks the config's host means the check moved: left in place it could fail on our host and send
			// RuneLite to its fallback config, which names Jagex's servers.
			missing.add("ClientLoader.downloadConfig checks a Jagex host this launcher doesn't patch ("
				+ otherJagexHosts + " literal(s))");
			return;
		}
		if (inDownloadConfig > 0)
		{
			patch(CLIENT_LOADER).addRule(new ClassPatch.Rule("jagex host check", JAGEX_HOST, "", DOWNLOAD_CONFIG,
				false, false, inDownloadConfig));
		}
	}

	private void scanProperty(byte[] bytes, String className, String value, String why)
	{
		boolean found = bytes != null && readsProperty(bytes, value);
		report.add(className.substring(className.lastIndexOf('/') + 1) + ": reads \"" + value + "\" "
			+ (found ? "yes, so " + why : "NOT FOUND"));
		if (!found)
		{
			missing.add(className + " does not read \"" + value + "\"");
		}
	}

	/**
	 * Whether the class passes {@code value} to {@code System.getProperty}: an {@code ldc} of it followed, within the
	 * next few instructions (a default value's expression at most), by that call. The name merely sitting in the
	 * constant pool, in a field or a dead branch, is not a read.
	 */
	private static boolean readsProperty(byte[] bytes, String value)
	{
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods)
		{
			for (AbstractInsnNode insn : method.instructions)
			{
				if (insn instanceof LdcInsnNode ldc && value.equals(ldc.cst) && reachesGetProperty(ldc))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean reachesGetProperty(AbstractInsnNode insn)
	{
		int steps = 0;
		for (AbstractInsnNode next = insn.getNext(); next != null && steps < 6; next = next.getNext())
		{
			if (next instanceof LabelNode || next instanceof LineNumberNode || next instanceof FrameNode)
			{
				continue;
			}
			if (next instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
				&& call.owner.equals("java/lang/System") && call.name.equals("getProperty"))
			{
				return true;
			}
			steps++;
		}
		return false;
	}

	/** How often a class uses a constant (a string or an int), in {@code ldc} instructions and constant fields. */
	private static int usesOf(byte[] bytes, Object value)
	{
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int uses = 0;
		for (FieldNode field : node.fields)
		{
			if (value.equals(field.value))
			{
				uses++;
			}
		}
		for (MethodNode method : node.methods)
		{
			for (AbstractInsnNode insn : method.instructions)
			{
				if (insn instanceof LdcInsnNode ldc && value.equals(ldc.cst))
				{
					uses++;
				}
			}
		}
		return uses;
	}

	/** Uses of {@code "127.0.0.1"} in a class: {@code [0]} all of them, {@code [1]} those String.endsWith takes. */
	private static int[] countLocalhost(byte[] bytes)
	{
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int[] counts = new int[2];
		for (MethodNode method : node.methods)
		{
			for (AbstractInsnNode insn : method.instructions)
			{
				if (insn instanceof LdcInsnNode ldc && LOCALHOST.equals(ldc.cst))
				{
					counts[0]++;
					if (ClassPatch.takenByEndsWith(ldc))
					{
						counts[1]++;
					}
				}
			}
		}
		return counts;
	}

	/**
	 * The string and int constants a class's constant pool holds, which its ldc instructions and constant fields use.
	 * Reading the pool is far cheaper than building a ClassNode, so it decides which of the injected client's classes
	 * get a full read.
	 */
	private record Constants(Set<String> strings, Set<Integer> ints)
	{
		static Constants of(ClassReader reader)
		{
			char[] buffer = new char[reader.getMaxStringLength()];
			Set<String> strings = new HashSet<>();
			Set<Integer> ints = new HashSet<>();
			for (int i = 1; i < reader.getItemCount(); i++)
			{
				int offset = reader.getItem(i);
				if (offset == 0)
				{
					continue; // the second slot of a long or double
				}
				int tag = reader.readByte(offset - 1);
				if (tag == 8) // CONSTANT_String
				{
					strings.add(reader.readUTF8(offset, buffer));
				}
				else if (tag == 3) // CONSTANT_Integer
				{
					ints.add(reader.readInt(offset));
				}
			}
			return new Constants(strings, ints);
		}
	}
}
