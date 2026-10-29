package net.odinsgate.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The launcher's log files, kept from growing without end: a file over {@link #MAX_BYTES} is moved aside when it is
 * next opened ({@code name.1}, the older {@code name.2}, ... up to {@link #KEEP}, the oldest dropped), so a problem
 * report always has the recent past and a disk never fills with ours. The launcher's own log is opened once per
 * run; the game's log once per game start. Rotation happens at opening only, never under a writer.
 */
final class RotatingLog
{
	/** A file this big or bigger is moved aside at the next opening. */
	static final long MAX_BYTES = 1L << 20;
	/** How many moved-aside files are kept besides the live one. */
	static final int KEEP = 3;

	private RotatingLog()
	{
	}

	/** Moves {@code file} aside when it has reached {@code maxBytes}, keeping {@code keep} older ones. */
	static void rotate(Path file, long maxBytes, int keep) throws IOException
	{
		if (!Files.isRegularFile(file) || Files.size(file) < maxBytes)
		{
			return;
		}
		Files.deleteIfExists(aside(file, keep));
		for (int i = keep - 1; i >= 1; i--)
		{
			Path older = aside(file, i);
			if (Files.exists(older))
			{
				Files.move(older, aside(file, i + 1), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.move(file, aside(file, 1), StandardCopyOption.REPLACE_EXISTING);
	}

	/** Opens {@code file} for appending, rotated first, with every line also written to {@code alsoTo}. */
	static PrintStream open(Path file, PrintStream alsoTo) throws IOException
	{
		Files.createDirectories(file.toAbsolutePath().getParent());
		rotate(file, MAX_BYTES, KEEP);
		OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		return new PrintStream(new Tee(out, alsoTo), true, StandardCharsets.UTF_8);
	}

	static Path aside(Path file, int index)
	{
		return file.resolveSibling(file.getFileName() + "." + index);
	}

	/** Writes to a file and a second stream alike; closing closes the file only, the second stream is the caller's. */
	private static final class Tee extends OutputStream
	{
		private final OutputStream file;
		private final OutputStream also;

		Tee(OutputStream file, OutputStream also)
		{
			this.file = file;
			this.also = also;
		}

		@Override
		public void write(int b) throws IOException
		{
			file.write(b);
			also.write(b);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException
		{
			file.write(bytes, offset, length);
			also.write(bytes, offset, length);
		}

		@Override
		public void flush() throws IOException
		{
			file.flush();
			also.flush();
		}

		@Override
		public void close() throws IOException
		{
			file.close();
		}
	}
}
