package net.odinsgate.launcher;

import java.io.IOException;
import java.nio.file.Path;

/**
 * RuneLite's runtime config, the file {@code -Drunelite.rtconf} names. RuneLite otherwise fetches it from its own
 * servers, and that remote copy can mark a launcher or client version outdated, which would put a message over our
 * pinned client; a missing or unreadable file is fatal to RuneLite instead. So the launcher writes this one before
 * every launch, in every mode, and never lets RuneLite go without it.
 *
 * <p>An empty object is the whole content: every key RuneLite reads from the config is optional, and the spike (PR
 * #113) ran the pinned 1.13.4 with exactly this file in normal and developer mode and saw no outdated message.
 */
public final class RuntimeConfig
{
	public static final String CONTENT = "{}\n";

	private RuntimeConfig()
	{
	}

	/** Writes the config to {@code file}, replacing whatever was there, and returns the file. */
	public static Path write(Path file) throws IOException
	{
		Atomically.write(file, CONTENT);
		return file;
	}
}
