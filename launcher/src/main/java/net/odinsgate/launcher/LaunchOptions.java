package net.odinsgate.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The launcher's own command line, which sets the mode of a launch. Friends start it with no arguments at all; the
 * test VM's scripts and CI pass these:
 * <pre>
 * --auto                         start the game without a click on Play
 * --dev                          developer mode: assertions on and --developer-mode, so the sideloaded test-runner
 *                                plugin loads (the test VM only; friends never get it)
 * --home &lt;dir&gt;                   the launcher's data folder, instead of the system's own place: the VM keeps
 *                                everything under C:\RSPS, and two launchers can run side by side
 * --status-file &lt;file&gt;           where to write the status file the VM's scripts wait on ({@link StatusFile})
 * --server host:port[:httpPort]  another server than the signed manifest's: the staging server on VM 214's spare
 *                                ports. The game port is patched into the client; httpPort defaults to port + 1,
 *                                the way our server pairs them. The key stays the manifest's.
 * --manifest &lt;file&gt;              a local manifest to launch from, trusted as it is, and no update looked for:
 *                                the VM's own, or the accepted one the installed shell hands a downloaded
 *                                version ({@link Updater})
 * --healthy &lt;file&gt;               the marker to write once this launcher is up (its window shown, or its steps
 *                                under way), which the shell that handed off reads as "not a crash"
 * </pre>
 * Parsing is all this class does; nothing here touches a file or the network.
 */
public record LaunchOptions(boolean auto, boolean developerMode, Path home, Path statusFile, ServerOverride server,
	Path manifest, Path healthy)
{
	/** {@code --server}'s value: where to dial instead of the manifest's server. */
	public record ServerOverride(String host, int gamePort, int httpPort)
	{
	}

	public static final String USAGE = "arguments: [--auto] [--dev] [--home <dir>] [--status-file <file>]"
		+ " [--server host:port[:httpPort]] [--manifest <file>] [--healthy <file>]";

	public static final LaunchOptions NONE = new LaunchOptions(false, false, null, null, null, null, null);

	/** Parses the launcher's arguments; an {@link IllegalArgumentException} names the bad one and gives the usage. */
	public static LaunchOptions parse(List<String> args)
	{
		boolean auto = false;
		boolean developerMode = false;
		Path home = null;
		Path statusFile = null;
		ServerOverride server = null;
		Path manifest = null;
		Path healthy = null;
		for (int i = 0; i < args.size(); i++)
		{
			String arg = args.get(i);
			switch (arg)
			{
				case "--auto" -> auto = true;
				case "--dev" -> developerMode = true;
				case "--home" -> home = path(value(args, ++i, arg));
				case "--status-file" -> statusFile = path(value(args, ++i, arg));
				case "--server" -> server = parseServer(value(args, ++i, arg));
				case "--manifest" -> manifest = path(value(args, ++i, arg));
				case "--healthy" -> healthy = path(value(args, ++i, arg));
				default -> throw new IllegalArgumentException("unknown argument \"" + arg + "\"; " + USAGE);
			}
		}
		return new LaunchOptions(auto, developerMode, home, statusFile, server, manifest, healthy);
	}

	/** The server this launch dials: the override when one was given, else the signed one, and always its key. */
	public Manifest.Server launchServer(Manifest.Server signed)
	{
		if (server == null)
		{
			return signed;
		}
		return new Manifest.Server(server.host(), server.gamePort(), server.httpPort(), signed.modulus());
	}

	/** The launcher's data folder: {@code --home} when given, else the system's place for it. */
	public Path dataDir(Platform platform, Map<String, String> env, Path userHome)
	{
		return home != null ? home : platform.dataDir(env, userHome);
	}

	private static String value(List<String> args, int index, String flag)
	{
		if (index >= args.size() || args.get(index).startsWith("--"))
		{
			throw new IllegalArgumentException(flag + " needs a value; " + USAGE);
		}
		return args.get(index);
	}

	private static Path path(String value)
	{
		if (value.isBlank())
		{
			throw new IllegalArgumentException("an empty path; " + USAGE);
		}
		return Path.of(value).toAbsolutePath().normalize();
	}

	private static ServerOverride parseServer(String value)
	{
		String[] parts = value.split(":", -1);
		if (parts.length < 2 || parts.length > 3 || !Manifest.HOST.matcher(parts[0]).matches())
		{
			throw new IllegalArgumentException("--server wants host:port[:httpPort], not \"" + value + "\"");
		}
		int gamePort = port(parts[1], value);
		int httpPort = parts.length == 3 ? port(parts[2], value) : gamePort + 1;
		if (httpPort > 65535)
		{
			throw new IllegalArgumentException("--server: no room for an HTTP port above " + gamePort);
		}
		return new ServerOverride(parts[0], gamePort, httpPort);
	}

	private static int port(String text, String value)
	{
		int port;
		try
		{
			port = Integer.parseInt(text);
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException("--server: \"" + text + "\" isn't a port, in \"" + value + "\"");
		}
		if (port < 1 || port > 65535)
		{
			throw new IllegalArgumentException("--server: port " + port + " is out of range, in \"" + value + "\"");
		}
		return port;
	}
}
