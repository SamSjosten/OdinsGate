package net.odinsgate.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;

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

	/** One file move; the real one is {@link Files#move} replacing, a test's can fail where it likes. */
	interface Mover
	{
		void move(Path from, Path to) throws IOException;
	}

	/**
	 * Moves {@code file} aside when it has reached {@code maxBytes}, keeping {@code keep} older ones. Every step is
	 * a rename, and the rotation is all or nothing: the live file moves first, to a {@code .moving} name (it is the
	 * one move that can fail in the ordinary course, a game that still holds it open on Windows, and then nothing
	 * else has been touched); the oldest file moves to a {@code .dropping} name rather than being deleted; the rest
	 * shift up; and only once all of that has gone through is the dropped one deleted. A failure anywhere undoes
	 * every move made, newest first, so a launcher that tries again and again never wears the older logs away.
	 */
	static void rotate(Path file, long maxBytes, int keep) throws IOException
	{
		rotate(file, maxBytes, keep, (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING));
	}

	static void rotate(Path file, long maxBytes, int keep, Mover mover) throws IOException
	{
		if (!Files.isRegularFile(file) || Files.size(file) < maxBytes)
		{
			return;
		}
		Path moving = file.resolveSibling(file.getFileName() + ".moving");
		Path dropping = file.resolveSibling(file.getFileName() + ".dropping");
		Deque<Path[]> done = new ArrayDeque<>();
		try
		{
			step(mover, done, file, moving);
			if (Files.exists(aside(file, keep)))
			{
				step(mover, done, aside(file, keep), dropping);
			}
			for (int i = keep - 1; i >= 1; i--)
			{
				if (Files.exists(aside(file, i)))
				{
					step(mover, done, aside(file, i), aside(file, i + 1));
				}
			}
			step(mover, done, moving, aside(file, 1));
		}
		catch (IOException e)
		{
			while (!done.isEmpty())
			{
				Path[] made = done.removeLast();
				try
				{
					mover.move(made[1], made[0]);
				}
				catch (IOException undo)
				{
					e.addSuppressed(undo);
				}
			}
			throw e;
		}
		Files.deleteIfExists(dropping);
	}

	private static void step(Mover mover, Deque<Path[]> done, Path from, Path to) throws IOException
	{
		mover.move(from, to);
		done.addLast(new Path[]{from, to});
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
