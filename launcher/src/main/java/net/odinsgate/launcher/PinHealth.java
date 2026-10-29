package net.odinsgate.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * The weekly pin health check ({@code .github/workflows/pin-health.yml}, {@code ./gradlew -p launcher pinHealth}):
 * for every pin in the server's {@code runelite-pins.json}, the bootstrap still verifies (the pinned SHA-256 and
 * RuneLite's signature, through the same {@link RuneLiteCache} the launcher uses), every jar it names for any system
 * still answers a HEAD request on RuneLite's repository with the size the bootstrap gives, and RuneLite's runtime
 * config doesn't list the pinned client version as outdated. Nothing is downloaded but the bootstrap and the config:
 * a jar is only asked about, so Actions never holds a RuneLite jar. One line per check, {@code ok} or {@code FAIL};
 * exit 0 when every pin is healthy, 1 when any check failed, 2 for a bad call or a pins file that doesn't read.
 */
public final class PinHealth
{
	/**
	 * Where RuneLite's client reads its runtime config, whose {@code outdatedClientVersions} puts an update message
	 * over a client; our launcher gives the client a local one ({@link RuntimeConfig}), so this is a warning of what
	 * RuneLite thinks of our pin, not something a friend sees.
	 */
	static final String DEFAULT_CONFIG_URL = "https://static.runelite.net/config.json";
	static final Duration TIMEOUT = Duration.ofSeconds(30);
	static final String USAGE = "usage: PinHealth --pins <runelite-pins.json> [--static-base <url>] [--config <url>]";

	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	private final String staticBase;
	private final String configUrl;
	private final List<PublicKey> runeliteKeys;
	private final Duration timeout;
	private final HttpClient http;
	private final PrintStream out;

	PinHealth(String staticBase, String configUrl, List<PublicKey> runeliteKeys, Duration timeout, PrintStream out)
	{
		this.staticBase = staticBase;
		this.configUrl = configUrl;
		this.runeliteKeys = runeliteKeys;
		this.timeout = timeout;
		this.out = out;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	public static void main(String[] args)
	{
		System.exit(run(args, RuneLiteSignature.trustedKeys(), System.out));
	}

	/** The command line against {@code keys}: tests pass a throwaway key and a loopback host. */
	static int run(String[] args, List<PublicKey> keys, PrintStream out)
	{
		Path pins = null;
		String staticBase = RuneLiteCache.DEFAULT_STATIC_BASE;
		String configUrl = DEFAULT_CONFIG_URL;
		for (int i = 0; i < args.length; i++)
		{
			String value = i + 1 < args.length ? args[i + 1] : null;
			if (value == null || value.startsWith("--"))
			{
				out.println(args[i] + " needs a value\n" + USAGE);
				return 2;
			}
			switch (args[i])
			{
				case "--pins" -> pins = Path.of(value);
				case "--static-base" -> staticBase = value;
				case "--config" -> configUrl = value;
				default ->
				{
					out.println("unknown argument " + args[i] + "\n" + USAGE);
					return 2;
				}
			}
			i++;
		}
		if (pins == null)
		{
			out.println(USAGE);
			return 2;
		}
		List<Manifest.Pin> table;
		try
		{
			table = readPins(pins);
		}
		catch (IOException | IllegalArgumentException e)
		{
			out.println(pins + ": " + e.getMessage());
			return 2;
		}
		return new PinHealth(staticBase, configUrl, keys, TIMEOUT, out).check(table) ? 0 : 1;
	}

	/** Every check of every pin, each on its own line; true when all passed. */
	boolean check(List<Manifest.Pin> pins)
	{
		List<String> outdated = outdatedClientVersions();
		boolean healthy = outdated != null;
		for (Manifest.Pin pin : pins)
		{
			healthy &= check(pin, outdated);
		}
		out.println(healthy
			? "pin health: every pin is healthy (" + pins.size() + " checked)"
			: "pin health: FAILED, see the FAIL lines above");
		return healthy;
	}

	private boolean check(Manifest.Pin pin, List<String> outdated)
	{
		String name = "revision " + pin.revision() + " (RuneLite " + pin.runelite() + " at "
			+ pin.commit().substring(0, 8) + ")";
		Bootstrap bootstrap;
		Path scratch;
		try
		{
			scratch = Files.createTempDirectory("pin-health");
		}
		catch (IOException e)
		{
			out.println("FAIL " + name + ": no temp folder for the bootstrap: " + e.getMessage());
			return false;
		}
		try
		{
			// The cache fetches the bootstrap and its signature, requires the pinned SHA-256 and RuneLite's signature,
			// and keeps the bootstrap under the scratch folder: that and the config are the only downloads here.
			bootstrap = new RuneLiteCache(scratch, staticBase, runeliteKeys, timeout).bootstrap(pin);
		}
		catch (IOException | BootstrapException e)
		{
			out.println("FAIL " + name + ": " + e.getMessage());
			return false;
		}
		finally
		{
			try
			{
				RuneLiteCache.deleteTree(scratch);
			}
			catch (IOException e)
			{
				out.println("note " + name + ": the scratch folder " + scratch + " was not deleted: " + e.getMessage());
			}
		}
		out.println("ok   " + name + ": the bootstrap carries the pinned SHA-256 and RuneLite's signature");
		boolean ok = true;
		if (!bootstrap.version.equals(pin.runelite()))
		{
			out.println("FAIL " + name + ": the bootstrap is RuneLite " + bootstrap.version + ", the pin says "
				+ pin.runelite());
			ok = false;
		}
		for (Bootstrap.Artifact artifact : everyArtifact(bootstrap))
		{
			ok &= answersHead(name, artifact);
		}
		if (outdated != null)
		{
			if (outdated.contains(pin.runelite()))
			{
				out.println("FAIL " + name + ": RuneLite's runtime config lists " + pin.runelite() + " as outdated");
				ok = false;
			}
			else
			{
				out.println("ok   " + name + ": RuneLite's runtime config doesn't list " + pin.runelite()
					+ " as outdated");
			}
		}
		return ok;
	}

	/** Every jar the bootstrap names for any system the launcher runs on, once each, in the bootstrap's order. */
	static List<Bootstrap.Artifact> everyArtifact(Bootstrap bootstrap)
	{
		Map<String, Bootstrap.Artifact> byPath = new LinkedHashMap<>();
		for (Platform.Os os : Platform.Os.values())
		{
			for (Platform.Arch arch : Platform.Arch.values())
			{
				for (Bootstrap.Artifact artifact : bootstrap.artifacts(new Platform(os, arch)))
				{
					byPath.putIfAbsent(artifact.path(), artifact);
				}
			}
		}
		return List.copyOf(byPath.values());
	}

	/** HEAD, never GET: the jar must answer 200 and, when it says its size, the bootstrap's size. */
	private boolean answersHead(String name, Bootstrap.Artifact artifact)
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(artifact.path())).timeout(timeout)
			.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
		try
		{
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() != 200)
			{
				out.println("FAIL " + name + ": " + artifact.name() + " answered " + response.statusCode() + " at "
					+ artifact.path());
				return false;
			}
			OptionalLong size = response.headers().firstValueAsLong("Content-Length");
			if (size.isPresent() && size.getAsLong() != artifact.size())
			{
				out.println("FAIL " + name + ": " + artifact.name() + " is " + size.getAsLong() + " bytes at "
					+ artifact.path() + ", the bootstrap says " + artifact.size());
				return false;
			}
			out.println("ok   " + name + ": " + artifact.name() + " answers HEAD"
				+ (size.isPresent() ? " with its " + artifact.size() + " bytes" : " (no size told)"));
			return true;
		}
		catch (IOException e)
		{
			out.println("FAIL " + name + ": " + artifact.name() + " at " + artifact.path() + ": " + e.getMessage());
			return false;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			out.println("FAIL " + name + ": interrupted while asking for " + artifact.name());
			return false;
		}
	}

	/**
	 * RuneLite's {@code outdatedClientVersions}, an empty list when the config has none; null, with a FAIL line,
	 * when the config can't be read or isn't what RuneLite publishes.
	 */
	private List<String> outdatedClientVersions()
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(configUrl)).timeout(timeout).GET().build();
		String body;
		try
		{
			HttpResponse<String> response =
				http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200)
			{
				out.println("FAIL RuneLite's runtime config at " + configUrl + " answered " + response.statusCode());
				return null;
			}
			body = response.body();
		}
		catch (IOException e)
		{
			out.println("FAIL RuneLite's runtime config at " + configUrl + ": " + e.getMessage());
			return null;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			out.println("FAIL interrupted while reading RuneLite's runtime config");
			return null;
		}
		List<String> outdated = new ArrayList<>();
		try
		{
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject())
			{
				throw new IllegalArgumentException("isn't a JSON object");
			}
			JsonElement list = parsed.getAsJsonObject().get("outdatedClientVersions");
			if (list != null && !list.isJsonNull())
			{
				for (JsonElement element : list.getAsJsonArray())
				{
					outdated.add(element.getAsString());
				}
			}
		}
		catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException
			| IllegalArgumentException e)
		{
			out.println("FAIL RuneLite's runtime config at " + configUrl + " " + e.getMessage());
			return null;
		}
		out.println("ok   RuneLite's runtime config lists " + outdated.size() + " outdated client version(s)"
			+ (outdated.isEmpty() ? "" : ": " + String.join(", ", outdated)));
		return outdated;
	}

	/**
	 * The server's pin table, {@code { "pins": [ { "revision", "runelite", "commit", "bootstrapSha256", ... } ] }},
	 * the same four fields the manifest's pins carry (the server's {@code RuneLitePins} reads the same file).
	 */
	static List<Manifest.Pin> readPins(Path file) throws IOException
	{
		JsonObject root;
		try
		{
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject())
			{
				throw new IllegalArgumentException("isn't a JSON object");
			}
			root = parsed.getAsJsonObject();
		}
		catch (JsonSyntaxException e)
		{
			throw new IllegalArgumentException("isn't JSON: " + e.getMessage());
		}
		JsonElement list = root.get("pins");
		if (list == null || !list.isJsonArray())
		{
			throw new IllegalArgumentException("has no \"pins\" list");
		}
		List<Manifest.Pin> pins = new ArrayList<>();
		for (JsonElement element : list.getAsJsonArray())
		{
			if (!element.isJsonObject())
			{
				throw new IllegalArgumentException("pin " + (pins.size() + 1) + " isn't an object");
			}
			JsonObject entry = element.getAsJsonObject();
			int revision;
			try
			{
				revision = field(entry, "revision", pins.size() + 1).getAsInt();
			}
			catch (NumberFormatException | UnsupportedOperationException e)
			{
				throw new IllegalArgumentException("pin " + (pins.size() + 1) + ": \"revision\" isn't a number");
			}
			String runelite = text(entry, "revision " + revision, "runelite");
			String commit = text(entry, "revision " + revision, "commit");
			String sha256 = text(entry, "revision " + revision, "bootstrapSha256");
			if (!COMMIT.matcher(commit).matches())
			{
				throw new IllegalArgumentException("revision " + revision
					+ ": \"commit\" isn't 40 lowercase hex digits");
			}
			if (!SHA256.matcher(sha256).matches())
			{
				throw new IllegalArgumentException("revision " + revision
					+ ": \"bootstrapSha256\" isn't 64 lowercase hex digits");
			}
			pins.add(new Manifest.Pin(revision, runelite, commit, sha256));
		}
		if (pins.isEmpty())
		{
			throw new IllegalArgumentException("\"pins\" is empty");
		}
		return pins;
	}

	private static JsonElement field(JsonObject entry, String name, int index)
	{
		JsonElement value = entry.get(name);
		if (value == null || value.isJsonNull())
		{
			throw new IllegalArgumentException("pin " + index + " has no \"" + name + "\"");
		}
		return value;
	}

	private static String text(JsonObject entry, String which, String name)
	{
		JsonElement value = entry.get(name);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
			|| value.getAsString().isEmpty())
		{
			throw new IllegalArgumentException(which + ": \"" + name + "\" is missing or not a string");
		}
		return value.getAsString();
	}
}
