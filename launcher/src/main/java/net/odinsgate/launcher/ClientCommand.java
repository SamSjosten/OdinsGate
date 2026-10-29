package net.odinsgate.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The game's command line. The game runs in a second Java process from our bundled runtime: a small loader of ours
 * (its own later PR) patches RuneLite's classes as they load and then calls RuneLite's main method, the way RuneLite
 * itself runs the client on a Mac, just moved into a child process. This class only builds the argument list; it
 * starts nothing, which is what makes every system's command testable from one CI runner.
 */
public final class ClientCommand
{
	/**
	 * Everything the command line is built from.
	 *
	 * @param bootstrapJvmArgs the pinned bootstrap's JVM arguments for the client, taken as they are
	 * @param gameHome RuneLite's home folder, under our data folder: the friend's real one is never read
	 * @param rtconf the local runtime config file the launcher always writes first; RuneLite dies without one, and
	 * with ours its remote config can never switch our pinned version off
	 * @param loaderClasspath the loader's own jars, not RuneLite's, which the loader itself loads and patches
	 * @param loaderArgs the loader's arguments up to its {@code --}; RuneLite's own follow it and are built here
	 * @param developerMode the test VM's mode: assertions on for RuneLite's main class, so --developer-mode takes,
	 * which is what lets the sideloaded test-runner plugin load
	 */
	public record Spec(
		Platform platform,
		Path runtimeDir,
		List<String> bootstrapJvmArgs,
		Path gameHome,
		Path rtconf,
		String host,
		int httpPort,
		List<Path> loaderClasspath,
		String loaderMain,
		List<String> loaderArgs,
		boolean developerMode)
	{
		public Spec
		{
			Objects.requireNonNull(platform);
			Objects.requireNonNull(runtimeDir);
			Objects.requireNonNull(bootstrapJvmArgs);
			Objects.requireNonNull(gameHome);
			Objects.requireNonNull(rtconf);
			Objects.requireNonNull(host);
			Objects.requireNonNull(loaderClasspath);
			Objects.requireNonNull(loaderMain);
			Objects.requireNonNull(loaderArgs);
		}
	}

	public static List<String> build(Spec spec)
	{
		List<String> command = new ArrayList<>();
		command.add(spec.platform().javaExecutable(spec.runtimeDir()).toString());
		command.addAll(spec.bootstrapJvmArgs());
		command.add("-Duser.home=" + spec.gameHome());
		command.add("-Drunelite.rtconf=" + spec.rtconf());
		// RuneLite's API base: the World Hopper and Default World plugins read the world list from it, so with ours
		// the list only ever holds our world, and no world-list patch is needed.
		command.add("-Drunelite.http-service.url=http://" + spec.host() + ":" + spec.httpPort() + "/rl/");
		if (spec.developerMode())
		{
			command.add("-ea:net.runelite.client.RuneLite");
		}
		StringJoiner classpath = new StringJoiner(String.valueOf(spec.platform().pathSeparator()));
		spec.loaderClasspath().forEach(entry -> classpath.add(entry.toString()));
		command.add("-cp");
		command.add(classpath.toString());
		command.add(spec.loaderMain());
		command.addAll(spec.loaderArgs());
		// From here on the arguments are RuneLite's, which the loader passes to its main method.
		command.add("--");
		command.add("--jav_config");
		command.add("http://" + spec.host() + ":" + spec.httpPort() + "/launcher/jav_config.ws");
		command.add("--disable-telemetry");
		command.add("--noupdate");
		if (spec.developerMode())
		{
			command.add("--developer-mode");
		}
		return List.copyOf(command);
	}

	private ClientCommand()
	{
	}
}
