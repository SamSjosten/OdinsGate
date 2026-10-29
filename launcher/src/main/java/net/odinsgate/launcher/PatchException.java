package net.odinsgate.launcher;

/**
 * A pinned RuneLite the launcher must not run: a patch point the scan expects is missing, or a class the loader
 * patched did not hold the constant or method the scan counted. Either means RuneLite changed under our pin in a way
 * our patches no longer fit, so the launcher stops before it starts a client that might reach the wrong server. The
 * message names the class and what was off, in words fit for a log, and never a class's bytes or the client's own
 * constants.
 */
public final class PatchException extends Exception
{
	public PatchException(String message)
	{
		super(message);
	}
}
