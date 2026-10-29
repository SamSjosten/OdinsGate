package net.odinsgate.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs a downloaded launcher version in this JVM: the jars its own signed manifest names (the ones hashed before
 * the launch; nothing else under its folder is loaded, whatever is dropped there) go into a class loader whose
 * parent is the platform loader (the JDK's classes and nothing of this shell's), and its {@code Main.run} is called
 * with the arguments the shell was given plus the manifest and healthy marker the shell chose. One process, so the
 * version's window is this process's window and its exit code is this one's; a version that fails to load or
 * throws before it returns is reported with its reason, which the shell turns into a bad version.
 * {@code Main.run(String[], PrintStream)} is the one contract between a shell and every later version: it stays.
 */
final class VersionLoader extends URLClassLoader
{
	static final String MAIN_CLASS = "net.odinsgate.launcher.Main";

	private VersionLoader(URL[] jars)
	{
		super(jars, ClassLoader.getPlatformClassLoader());
	}

	/** Runs the version made of {@code jars}: its {@code Main.run(args, out)}, whose exit code comes back. */
	static int run(List<Path> jars, String[] args, PrintStream out) throws IOException
	{
		if (jars.isEmpty())
		{
			throw new IOException("the version's manifest names no jars");
		}
		URL[] urls = new URL[jars.size()];
		for (int i = 0; i < urls.length; i++)
		{
			try
			{
				urls[i] = jars.get(i).toUri().toURL();
			}
			catch (MalformedURLException e)
			{
				throw new IOException(jars.get(i) + " isn't a URL: " + e.getMessage(), e);
			}
		}
		Thread thread = Thread.currentThread();
		ClassLoader before = thread.getContextClassLoader();
		try (VersionLoader loader = new VersionLoader(urls))
		{
			thread.setContextClassLoader(loader);
			Method run = Class.forName(MAIN_CLASS, true, loader).getMethod("run", String[].class, PrintStream.class);
			Object code = run.invoke(null, (Object) args, out);
			return (Integer) code;
		}
		catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | ClassCastException e)
		{
			throw new IOException("the version has no " + MAIN_CLASS + ".run(String[], PrintStream): " + e, e);
		}
		catch (LinkageError e)
		{
			// A static initialiser that throws, a class that doesn't link: the version can't even load.
			throw new IOException("the version failed to load: " + e, e);
		}
		catch (InvocationTargetException e)
		{
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new IOException("the version failed: " + cause, cause);
		}
		finally
		{
			thread.setContextClassLoader(before);
		}
	}
}
