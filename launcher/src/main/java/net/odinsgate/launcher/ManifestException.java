package net.odinsgate.launcher;

/**
 * A manifest the launcher must not act on: its signature doesn't verify, it isn't our format, or a value fails
 * validation. The message says which, in words fit for a log.
 */
public final class ManifestException extends Exception
{
	public ManifestException(String message)
	{
		super(message);
	}
}
