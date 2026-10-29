package net.odinsgate.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a small file whole: a reader (RuneLite, the VM's scripts) never sees it half-written or empty. */
final class Atomically
{
	private Atomically()
	{
	}

	/** Writes {@code content} as UTF-8 to a sibling temp file, then moves it over {@code file}, parents made first. */
	static void write(Path file, String content) throws IOException
	{
		Path parent = file.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		Path temp = parent.resolve(file.getFileName() + ".tmp");
		Files.writeString(temp, content, StandardCharsets.UTF_8);
		try
		{
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
