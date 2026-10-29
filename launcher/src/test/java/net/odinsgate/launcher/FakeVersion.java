package net.odinsgate.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A stand-in launcher version for the shell to hand off to: a jar holding one class, {@code
 * net.odinsgate.launcher.Main}, whose {@code run(String[], PrintStream)} prints a line with its arguments and
 * returns the code it was built with. Made with ASM, so no launcher jar is ever built or committed for a test.
 */
final class FakeVersion
{
	private FakeVersion()
	{
	}

	/** The jar's bytes. {@code run} prints {@code "<tag>: " + String.join(" ", args)} and returns {@code code}. */
	static byte[] jar(String tag, int code) throws IOException
	{
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "net/odinsgate/launcher/Main", null,
			"java/lang/Object", null);
		MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
			"([Ljava/lang/String;Ljava/io/PrintStream;)I", null, null);
		run.visitCode();
		run.visitVarInsn(Opcodes.ALOAD, 1);
		run.visitLdcInsn(tag + ": ");
		run.visitLdcInsn(" ");
		run.visitVarInsn(Opcodes.ALOAD, 0);
		run.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "join",
			"(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;", false);
		run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
			"(Ljava/lang/String;)Ljava/lang/String;", false);
		run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
		run.visitLdcInsn(code);
		run.visitInsn(Opcodes.IRETURN);
		run.visitMaxs(0, 0);
		run.visitEnd();
		writer.visitEnd();
		return jarOf("net/odinsgate/launcher/Main.class", writer.toByteArray());
	}

	/** A jar with no Main at all, for a version that can't run. */
	static byte[] emptyJar() throws IOException
	{
		return jarOf("nothing.txt", "nothing".getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] jarOf(String entry, byte[] content) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (JarOutputStream jar = new JarOutputStream(bytes))
		{
			jar.putNextEntry(new JarEntry(entry));
			jar.write(content);
			jar.closeEntry();
		}
		return bytes.toByteArray();
	}
}
