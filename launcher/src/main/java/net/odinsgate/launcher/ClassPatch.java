package net.odinsgate.launcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * One RuneLite class's changes, found by {@link Preflight} and applied by {@link PatchingClassLoader} as the class
 * loads: constants replaced (the server key and the two host checks, strings; the game port, an int, when the server
 * isn't on the default port) and {@code void} methods made to return at once ({@code updateVanilla}, which could
 * otherwise download a gamepack). Each rule carries the exact count the scan
 * found; {@link #apply} throws {@link PatchException} if the class it is handed does not match that count, so a
 * RuneLite that changed under our pin stops the launch rather than running half-patched.
 *
 * <p>This is RSProx's recipe (its {@code RuneLitePatcher}/{@code ClientLoaderPatcher}), redone with ASM on the bytes
 * as they load: no jar is ever rewritten on disk, and a class RuneLite never loads is never touched.
 */
public final class ClassPatch
{
	/**
	 * A constant to replace: a {@link String}, or an {@link Integer} for the game port.
	 *
	 * @param what a short name for the log ("server key", "host check", "game port")
	 * @param from the constant as the scan found it
	 * @param to what it becomes, of the same type
	 * @param method when set, only inside this {@code name+descriptor} method
	 * @param endsWithOnly when true, only where {@code String.endsWith} takes it (the client's host check)
	 * @param fields when true, also a constant field's value, not only {@code ldc} instructions
	 * @param expected how many replacements the scan counted; {@link #apply} insists on exactly this many
	 */
	public record Rule(String what, Object from, Object to, String method, boolean endsWithOnly, boolean fields,
		int expected)
	{
		public Rule
		{
			if (!(from instanceof String || from instanceof Integer) || from.getClass() != to.getClass())
			{
				throw new IllegalArgumentException(what + ": from and to must both be strings or both be ints");
			}
		}
	}

	private final String className;
	private final List<Rule> rules = new ArrayList<>();
	private final Set<String> returnAtOnce = new TreeSet<>();

	public ClassPatch(String className)
	{
		this.className = className;
	}

	public String className()
	{
		return className;
	}

	void addRule(Rule rule)
	{
		rules.add(rule);
	}

	void returnAtOnce(String nameAndDescriptor)
	{
		returnAtOnce.add(nameAndDescriptor);
	}

	/** A short description of every change, for the launcher's log; never a constant's own text. */
	public String summary()
	{
		List<String> parts = new ArrayList<>();
		rules.forEach(rule -> parts.add(rule.what() + " x" + rule.expected()));
		returnAtOnce.forEach(method -> parts.add(method.substring(0, method.indexOf('(')) + " returns at once"));
		return String.join(", ", parts);
	}

	/**
	 * The class's bytes with every rule applied the exact number of times the scan counted, and every named method
	 * made to return. A mismatch, either way, throws {@link PatchException}: the pin changed and these patches no
	 * longer fit it.
	 */
	public byte[] apply(byte[] bytes) throws PatchException
	{
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int[] done = new int[rules.size()];
		for (FieldNode field : node.fields)
		{
			for (int i = 0; i < rules.size(); i++)
			{
				Rule rule = rules.get(i);
				if (rule.fields() && rule.from().equals(field.value))
				{
					field.value = rule.to();
					done[i]++;
				}
			}
		}
		Set<String> returned = new HashSet<>();
		for (MethodNode method : node.methods)
		{
			String key = method.name + method.desc;
			if (returnAtOnce.contains(key))
			{
				returnVoid(method);
				returned.add(key);
				continue;
			}
			for (AbstractInsnNode insn : method.instructions)
			{
				if (!(insn instanceof LdcInsnNode ldc))
				{
					continue;
				}
				for (int i = 0; i < rules.size(); i++)
				{
					Rule rule = rules.get(i);
					if (rule.from().equals(ldc.cst) && (rule.method() == null || rule.method().equals(key))
						&& (!rule.endsWithOnly() || takenByEndsWith(ldc)))
					{
						ldc.cst = rule.to();
						done[i]++;
						break;
					}
				}
			}
		}
		for (int i = 0; i < rules.size(); i++)
		{
			Rule rule = rules.get(i);
			if (done[i] != rule.expected())
			{
				throw new PatchException(className + ": " + rule.what() + " replaced " + done[i]
					+ " time(s), the scan found " + rule.expected());
			}
		}
		if (!returned.equals(returnAtOnce))
		{
			Set<String> missing = new TreeSet<>(returnAtOnce);
			missing.removeAll(returned);
			throw new PatchException(className + ": method(s) to stop not found: " + missing);
		}
		// COMPUTE_MAXS recomputes the stack and locals sizes; frames are kept as they were, since no rule changes a
		// branch and a stopped method has none.
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static void returnVoid(MethodNode method) throws PatchException
	{
		if (!method.desc.endsWith(")V"))
		{
			throw new PatchException(method.name + method.desc + " does not return void");
		}
		method.instructions.clear();
		method.tryCatchBlocks.clear();
		method.localVariables = null;
		method.visibleLocalVariableAnnotations = null;
		method.invisibleLocalVariableAnnotations = null;
		method.instructions.add(new InsnNode(Opcodes.RETURN));
	}

	/** Whether this constant is the argument of a following {@code String.endsWith} call (the client's host check). */
	static boolean takenByEndsWith(AbstractInsnNode insn)
	{
		AbstractInsnNode next = insn.getNext();
		while (next instanceof LabelNode || next instanceof LineNumberNode || next instanceof FrameNode)
		{
			next = next.getNext();
		}
		return next instanceof MethodInsnNode call && call.owner.equals("java/lang/String")
			&& call.name.equals("endsWith") && call.desc.equals("(Ljava/lang/String;)Z");
	}
}
