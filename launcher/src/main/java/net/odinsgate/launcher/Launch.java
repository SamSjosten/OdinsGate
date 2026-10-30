package net.odinsgate.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One launch, planned from the mode ({@link LaunchOptions}), the system and the signed manifest: where this launch's
 * files go, the runtime config written first, the server it dials, and the game's command line. Whatever the mode,
 * the game gets its own home folder under our data folder (never the friend's real RuneLite profile), the local
 * runtime config, our API base, {@code --disable-telemetry}, and a loader told the exact jars, key, port and the
 * two endpoints the game may reach.
 *
 * @param dataDir the launcher's data folder, {@code --home} or the system's place
 * @param gameHome the folder the game sees as the user's home ({@code -Duser.home}), so RuneLite keeps its profile,
 * settings and cache under our data folder
 * @param rtconf the runtime config, written by {@link #plan} before anything else
 * @param server the server this launch dials, with the manifest's key
 * @param spec the game's command line, ready for {@link ClientCommand#build}
 */
public record Launch(LaunchOptions options, Path dataDir, Path gameHome, Path rtconf, Manifest.Server server,
	ClientCommand.Spec spec)
{
	/** The game's home folder's name under the data folder. */
	static final String GAME_HOME = "home";
	/** The runtime config's name under the data folder. */
	static final String RTCONF = "rtconf.json";

	/** The pinned RuneLite as the cache yields it: its jars in bootstrap order and the bootstrap's JVM arguments. */
	public record Pin(List<Path> gameJars, List<String> jvmArguments)
	{
		public Pin
		{
			Objects.requireNonNull(gameJars);
			Objects.requireNonNull(jvmArguments);
		}
	}

	/** Our own installed pieces: the bundled runtime and the loader's classpath (this jar, Gson and ASM). */
	public record Installed(Path runtimeDir, List<Path> loaderClasspath)
	{
		public Installed
		{
			Objects.requireNonNull(runtimeDir);
			Objects.requireNonNull(loaderClasspath);
		}
	}

	/**
	 * Plans a launch and writes its runtime config. The data folder comes from {@code options} or the system; the
	 * server from {@code options} or {@code signed}; the mode's developer flags from {@code options}.
	 */
	public static Launch plan(LaunchOptions options, Platform platform, Map<String, String> env, Path userHome,
		Manifest.Server signed, Installed installed, Pin pin) throws IOException
	{
		Path dataDir = options.dataDir(platform, env, userHome);
		Path gameHome = dataDir.resolve(GAME_HOME);
		Path rtconf = RuntimeConfig.write(dataDir.resolve(RTCONF));
		Manifest.Server server = options.launchServer(signed);
		ClientCommand.Spec spec = new ClientCommand.Spec(
			platform,
			installed.runtimeDir(),
			pin.jvmArguments(),
			gameHome,
			rtconf,
			server.host(),
			server.httpPort(),
			installed.loaderClasspath(),
			Loader.class.getName(),
			Loader.arguments(pin.gameJars(), server, Loader.RUNELITE_MAIN),
			options.developerMode());
		return new Launch(options, dataDir, gameHome, rtconf, server, spec);
	}

	/** The game's full command line, the bundled java first. */
	public List<String> command()
	{
		return ClientCommand.build(spec);
	}
}
