package net.odinsgate.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Loads the pinned RuneLite from its own jars and applies {@link Preflight}'s plan to the named classes as they load.
 * This is how RuneLite's own launcher runs the client on a Mac, moved into the launcher's child JVM: no Java agent, no
 * jar rewritten on disk, and a class RuneLite never loads is never patched. The child JVM's entry point (its own later
 * PR) builds one of these, loads RuneLite's main class through it, and calls {@code main}.
 *
 * <p>A patched class keeps the code signers of its jar entry, so a package's classes all carry the same signers,
 * patched or not, and the jar's signature is still verified as the entry is read to its end.
 */
public final class PatchingClassLoader extends URLClassLoader
{
	static
	{
		registerAsParallelCapable();
	}

	private final List<JarFile> files = new ArrayList<>();
	private final List<URL> urls = new ArrayList<>();
	private final Map<String, ClassPatch> patches;

	/**
	 * @param jars RuneLite's jars, in the bootstrap's (classpath) order
	 * @param patches the classes to patch, by internal name, from {@link Preflight#patches()}
	 */
	public PatchingClassLoader(List<Path> jars, Map<String, ClassPatch> patches) throws IOException
	{
		super("runelite", toUrls(jars), ClassLoader.getPlatformClassLoader());
		for (Path jar : jars)
		{
			files.add(new JarFile(jar.toFile(), true));
			urls.add(jar.toUri().toURL());
		}
		this.patches = Map.copyOf(patches);
	}

	private static URL[] toUrls(List<Path> jars) throws IOException
	{
		URL[] urls = new URL[jars.size()];
		for (int i = 0; i < jars.size(); i++)
		{
			urls[i] = jars.get(i).toUri().toURL();
		}
		return urls;
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException
	{
		String className = name.replace('.', '/');
		ClassPatch patch = patches.get(className);
		if (patch == null)
		{
			return super.findClass(name);
		}
		for (int i = 0; i < files.size(); i++)
		{
			JarFile file = files.get(i);
			JarEntry entry = file.getJarEntry(className + ".class");
			if (entry == null)
			{
				continue;
			}
			try
			{
				byte[] bytes;
				try (InputStream in = file.getInputStream(entry))
				{
					bytes = in.readAllBytes();
				}
				// Known once the entry has been read to its end, which also verified it against the jar's signature.
				CodeSigner[] signers = entry.getCodeSigners();
				byte[] patched = patch.apply(bytes);
				definePackageOf(name, file, urls.get(i));
				return defineClass(name, patched, 0, patched.length, new CodeSource(urls.get(i), signers));
			}
			catch (IOException | RuntimeException | PatchException e)
			{
				throw new ClassNotFoundException(name + ": " + e.getMessage(), e);
			}
		}
		throw new ClassNotFoundException(name);
	}

	private void definePackageOf(String name, JarFile file, URL url) throws IOException
	{
		int dot = name.lastIndexOf('.');
		if (dot < 0)
		{
			return;
		}
		String pkg = name.substring(0, dot);
		if (getDefinedPackage(pkg) != null)
		{
			return;
		}
		Manifest manifest = file.getManifest();
		try
		{
			if (manifest != null)
			{
				definePackage(pkg, manifest, url);
			}
			else
			{
				definePackage(pkg, null, null, null, null, null, null, null);
			}
		}
		catch (IllegalArgumentException definedMeanwhile)
		{
			// Another thread defined it first; its definition stands.
		}
	}

	@Override
	public void close() throws IOException
	{
		IOException first = null;
		try
		{
			super.close();
		}
		catch (IOException e)
		{
			first = e;
		}
		for (JarFile file : files)
		{
			try
			{
				file.close();
			}
			catch (IOException e)
			{
				if (first == null)
				{
					first = e;
				}
			}
		}
		if (first != null)
		{
			throw first;
		}
	}
}
