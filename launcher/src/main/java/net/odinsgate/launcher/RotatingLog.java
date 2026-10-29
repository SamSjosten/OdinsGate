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

	/**
	 * Moves {@code file} aside when it has reached {@code maxBytes}, keeping {@code keep} older ones. The live file
	 * moves first, to a {@code .moving} name: it is the one move that can fail (a game that still holds it open on
	 * Windows), and when it does nothing else has been touched, so the older files are not shifted or dropped by a
	 * launcher that tries again and again. Should the older files fail to shift after that, the live file is put
	 * back where it was, so nothing is ever left under the {@code .moving} name.
	 */
	static void rotate(Path file, long maxBytes, int keep) throws IOException
	{
		if (!Files.isRegularFile(file) || Files.size(file) < maxBytes)
		{
			return;
		}
		Path moving = file.resolveSibling(file.getFileName() + ".moving");
		Files.move(file, moving, StandardCopyOption.REPLACE_EXISTING);
		try
		{
			Files.deleteIfExists(aside(file, keep));
			for (int i = keep - 1; i >= 1; i--)
			{
				Path older = aside(file, i);
				if (Files.exists(older))
				{
					Files.move(older, aside(file, i + 1), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.move(moving, aside(file, 1), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			try
			{
				Files.move(moving, file, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException putBack)
			{
				e.addSuppressed(putBack);
			}
			throw e;
		}
	}

	/** Opens {@code file} for appending, rotated first, with every line also written to {@code alsoTo}. */
	static PrintStream open(Path file, PrintStream alsoTo) throws IOException
	{
		Files.createDirectories(file.toAbsolutePath().getParent());
		rotate(file, MAX_BYTES, KEEP);
		return tee(Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND), alsoTo);
	}

	/**
	 * A stream writing to {@code file} and {@code alsoTo} alike; a file that stops taking writes costs nothing else.
	 */
	static PrintStream tee(OutputStream file, OutputStream alsoTo)
	{
		return new PrintStream(new Tee(file, alsoTo), true, StandardCharsets.UTF_8);
	}

	static Path aside(Path file, int index)
	{
		return file.resolveSibling(file.getFileName() + "." + index);
	}

	/**
	 * Writes to a file and a second stream alike. The file is the one that can fail (a disk full, a folder gone, a
	 * permission changed under a running launcher); when it does, it is given up on and the second stream, the
	 * console or what a wrapper script collects, still gets every line. Closing closes the file only, the second
	 * stream is the caller's.
	 */
	private static final class Tee extends OutputStream
	{
		private final OutputStream file;
		private final OutputStream also;
		private boolean fileGivenUp;

		Tee(OutputStream file, OutputStream also)
		{
			this.file = file;
			this.also = also;
		}

		@Override
		public void write(int b) throws IOException
		{
			if (!fileGivenUp)
			{
				try
				{
					file.write(b);
				}
				catch (IOException e)
				{
					fileGivenUp = true;
				}
			}
			also.write(b);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException
		{
			if (!fileGivenUp)
			{
				try
				{
					file.write(bytes, offset, length);
				}
				catch (IOException e)
				{
					fileGivenUp = true;
				}
			}
			also.write(bytes, offset, length);
		}

		@Override
		public void flush() throws IOException
		{
			if (!fileGivenUp)
			{
				try
				{
					file.flush();
				}
				catch (IOException e)
				{
					fileGivenUp = true;
				}
			}
			also.flush();
		}

		@Override
		public void close() throws IOException
		{
			file.close();
		}
	}
}
