package net.odinsgate.launcher;

/**
 * A RuneLite bootstrap or jar the launcher must not run: the pinned hash or RuneLite's signature doesn't match, or a
 * value fails validation. The message says which, in words fit for a log.
 */
public final class BootstrapException extends Exception
{
	public BootstrapException(String message)
	{
		super(message);
	}
}
