package net.odinsgate.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The launcher's manifest: the one piece of remote data that decides what runs. CI publishes it as a GitHub Release
 * asset next to its Ed25519 signature, and the launcher takes it only through {@link #verified}, so a server or a
 * network path that can hand us plain data can never change the launcher's files, the RuneLite pin table or the
 * server's key. Unknown JSON members are ignored, so a newer manifest may add members without stranding old
 * launchers; a new shape that old launchers must refuse bumps {@code format} instead.
 *
 * <pre>
 * { "format": 1,
 *   "version": "0.1.0",
 *   "minBootstrapVersion": "0.1.0",
 *   "server": { "host": "odinsgate.net", "gamePort": 43594, "httpPort": 43595, "modulus": "993466dd...c51" },
 *   "pins": [ { "revision": 241, "runelite": "1.13.4", "commit": "1e634e64...48d",
 *               "bootstrapSha256": "25f22ff8...b2a" } ],
 *   "files": [ { "path": "lib/odinsgate-launcher-0.1.0.jar", "sha256": "...", "size": 123456 } ],
 *   "keys": { "primary": "MCowBQYDK2VwAyEA..." } }
 * </pre>
 *
 * {@code keys} is optional: a manifest signed by the offline backup key may name a new primary key there (base64
 * SubjectPublicKeyInfo), which the updater then trusts in place of the old one, so a lost or leaked primary never
 * strands a friend ({@link Updater}).
 *
 * Validation here is strict because every value is used somewhere dangerous: the commit goes into a URL, the hashes
 * gate what runs, the modulus is patched into the client, and each file path is written under the launcher's folder.
 */
public final class Manifest
{
	/** The format this launcher reads. A manifest with a higher one means "please download the new installer". */
	public static final int FORMAT = 1;

	/** Dotted digits, such as 0.1.0: comparable, and safe inside a folder name (versions/0.1.0/). */
	private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");
	/**
	 * A hostname or IPv4 address that can form a request URI: dot-separated labels, each starting and ending
	 * alphanumeric, so "odinsgate..net" or a trailing hyphen can't blow up in HttpRequest later.
	 */
	static final Pattern HOST =
		Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*");
	/** A 1024-bit RSA modulus is exactly 256 hex digits, and the server prints it lowercase. */
	private static final Pattern MODULUS = Pattern.compile("[0-9a-f]{256}");
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	/** One path segment: no separators, so no "..", no leading "/", no drive letter, nothing outside the folder. */
	private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");

	public final String version;
	public final String minBootstrapVersion;
	public final Server server;
	public final List<FileEntry> files;
	/** {@code keys.primary}, the base64 of a new primary key's SubjectPublicKeyInfo, or null when there is none. */
	public final String nextPrimaryKey;
	private final Map<Integer, Pin> pins;

	/** Where the game is, and the key the client must be patched to. Plain data from this host is hints only. */
	public record Server(String host, int gamePort, int httpPort, String modulus)
	{
	}

	/** Which RuneLite release serves a revision: a static.runelite.net commit and the SHA-256 of its bootstrap. */
	public record Pin(int revision, String runelite, String commit, String bootstrapSha256)
	{
	}

	/** One file of the launcher release, hashed so a download or a cached copy can be checked before it runs. */
	public record FileEntry(String path, String sha256, long size)
	{
		/**
		 * The release asset the file is published as: its bare name, under {@code releases/download/v<version>/}.
		 * The release tool uploads each file under this name and the updater fetches it by it.
		 */
		public String assetName()
		{
			return path.substring(path.lastIndexOf('/') + 1);
		}
	}

	private Manifest(String version, String minBootstrapVersion, Server server, Map<Integer, Pin> pins,
		List<FileEntry> files, String nextPrimaryKey)
	{
		this.version = version;
		this.minBootstrapVersion = minBootstrapVersion;
		this.server = server;
		this.pins = pins;
		this.files = files;
		this.nextPrimaryKey = nextPrimaryKey;
	}

	/** The pin for a revision the server reports, or null when this manifest doesn't know the revision yet. */
	public Pin pin(int revision)
	{
		return pins.get(revision);
	}

	public Collection<Pin> pins()
	{
		return pins.values();
	}

	/**
	 * The only way to a Manifest from remote bytes: the signature (base64 of the 64-byte Ed25519 signature over
	 * exactly these bytes) must verify against one of the trusted keys before a single value is read.
	 */
	public static Manifest verified(byte[] manifestBytes, String signatureBase64, List<PublicKey> trustedKeys)
		throws ManifestException
	{
		if (!Signatures.verify(manifestBytes, signatureBase64, trustedKeys))
		{
			throw new ManifestException("the manifest's signature doesn't verify against any trusted key");
		}
		return parse(new String(manifestBytes, StandardCharsets.UTF_8));
	}

	/** Parses and validates an already-trusted manifest: one committed to this repo, or one made by a test. */
	public static Manifest parse(String json) throws ManifestException
	{
		JsonObject root = object(json);
		long format = integer(root, "format", 0, Long.MAX_VALUE);
		if (format != FORMAT)
		{
			throw new ManifestException("manifest format " + format + ", but this launcher reads format " + FORMAT);
		}
		String version = matching(root, "version", VERSION);
		String minBootstrapVersion = matching(root, "minBootstrapVersion", VERSION);

		JsonElement serverElement = member(root, "server");
		if (!serverElement.isJsonObject())
		{
			throw new ManifestException("\"server\" isn't an object");
		}
		JsonObject serverObject = serverElement.getAsJsonObject();
		Server server = new Server(
			matching(serverObject, "host", HOST),
			(int) integer(serverObject, "gamePort", 1, 65535),
			(int) integer(serverObject, "httpPort", 1, 65535),
			matching(serverObject, "modulus", MODULUS));

		Map<Integer, Pin> pins = new LinkedHashMap<>();
		for (JsonElement element : array(root, "pins"))
		{
			if (!element.isJsonObject())
			{
				throw new ManifestException("a pin isn't an object");
			}
			JsonObject pin = element.getAsJsonObject();
			int revision = (int) integer(pin, "revision", 1, 10_000);
			Pin parsed = new Pin(
				revision,
				matching(pin, "runelite", VERSION),
				matching(pin, "commit", COMMIT),
				matching(pin, "bootstrapSha256", SHA256));
			if (pins.put(revision, parsed) != null)
			{
				throw new ManifestException("two pins for revision " + revision);
			}
		}
		if (pins.isEmpty())
		{
			throw new ManifestException("\"pins\" is empty: the launcher could download no client at all");
		}

		Map<String, FileEntry> files = new LinkedHashMap<>();
		for (JsonElement element : array(root, "files"))
		{
			if (!element.isJsonObject())
			{
				throw new ManifestException("a file entry isn't an object");
			}
			JsonObject file = element.getAsJsonObject();
			FileEntry entry = new FileEntry(
				path(file),
				matching(file, "sha256", SHA256),
				integer(file, "size", 0, Long.MAX_VALUE));
			if (files.put(entry.path(), entry) != null)
			{
				throw new ManifestException("two file entries for " + entry.path());
			}
		}

		String nextPrimaryKey = null;
		if (root.has("keys") && !root.get("keys").isJsonNull())
		{
			JsonElement keys = root.get("keys");
			if (!keys.isJsonObject())
			{
				throw new ManifestException("\"keys\" isn't an object");
			}
			if (keys.getAsJsonObject().has("primary"))
			{
				nextPrimaryKey = string(keys.getAsJsonObject(), "primary").trim();
				try
				{
					Signatures.publicKey(nextPrimaryKey);
				}
				catch (IllegalArgumentException e)
				{
					throw new ManifestException("\"keys.primary\" isn't an Ed25519 public key: " + e.getMessage());
				}
			}
		}
		return new Manifest(version, minBootstrapVersion, server, pins, List.copyOf(files.values()), nextPrimaryKey);
	}

	/**
	 * The {@code format} a manifest claims, read before anything else is believed about it, so a verified manifest
	 * of a newer format can say "please download the new installer" rather than "this doesn't read"; -1 when the
	 * text isn't a JSON object with a whole-number {@code format}.
	 */
	public static long formatOf(String json)
	{
		try
		{
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("format"))
			{
				return -1;
			}
			JsonElement format = parsed.getAsJsonObject().get("format");
			if (!format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber())
			{
				return -1;
			}
			double value = format.getAsDouble();
			return value == Math.rint(value) && value >= 0 ? (long) value : -1;
		}
		catch (JsonSyntaxException | IllegalStateException e)
		{
			return -1;
		}
	}

	private static JsonObject object(String json) throws ManifestException
	{
		JsonElement root;
		try
		{
			root = JsonParser.parseString(json);
		}
		catch (JsonSyntaxException e)
		{
			throw new ManifestException("the manifest isn't JSON: " + e.getMessage());
		}
		if (!root.isJsonObject())
		{
			throw new ManifestException("the manifest isn't a JSON object");
		}
		return root.getAsJsonObject();
	}

	private static JsonElement member(JsonObject object, String name) throws ManifestException
	{
		JsonElement element = object.get(name);
		if (element == null || element.isJsonNull())
		{
			throw new ManifestException("the manifest has no \"" + name + "\"");
		}
		return element;
	}

	private static String string(JsonObject object, String name) throws ManifestException
	{
		JsonElement element = member(object, name);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
		{
			throw new ManifestException("\"" + name + "\" isn't a string");
		}
		return element.getAsString();
	}

	private static String matching(JsonObject object, String name, Pattern pattern) throws ManifestException
	{
		String value = string(object, name);
		if (!pattern.matcher(value).matches())
		{
			throw new ManifestException("\"" + name + "\" doesn't match " + pattern + ": \"" + value + "\"");
		}
		return value;
	}

	private static long integer(JsonObject object, String name, long min, long max) throws ManifestException
	{
		JsonElement element = member(object, name);
		long value;
		try
		{
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
			{
				throw new NumberFormatException();
			}
			// By the literal's own text: Gson's getAsLong would quietly truncate 43594.5 to 43594.
			value = Long.parseLong(element.getAsString());
		}
		catch (NumberFormatException e)
		{
			throw new ManifestException("\"" + name + "\" isn't a whole number");
		}
		if (value < min || value > max)
		{
			throw new ManifestException("\"" + name + "\" is out of range: " + value);
		}
		return value;
	}

	private static Iterable<JsonElement> array(JsonObject object, String name) throws ManifestException
	{
		JsonElement element = member(object, name);
		if (!element.isJsonArray())
		{
			throw new ManifestException("\"" + name + "\" isn't a list");
		}
		return element.getAsJsonArray();
	}

	/** A file path that stays inside the launcher's folder wherever it is joined: plain segments, '/' between. */
	private static String path(JsonObject file) throws ManifestException
	{
		String value = string(file, "path");
		for (String segment : value.split("/", -1))
		{
			if (!PATH_SEGMENT.matcher(segment).matches())
			{
				throw new ManifestException("unsafe file path: \"" + value + "\"");
			}
		}
		return value;
	}
}
