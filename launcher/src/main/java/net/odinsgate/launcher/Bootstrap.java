package net.odinsgate.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A RuneLite release's bootstrap.json: which jars make that release, each with its URL on RuneLite's repository, its
 * size and its SHA-256, plus the JVM arguments the client wants. Parsed only after {@link RuneLiteCache} has checked
 * both the pin's SHA-256 and RuneLite's own signature over the exact bytes, so everything here is as RuneLite
 * published it. Fields we don't use (the launcher's own arguments, update entries, per-artifact diffs) are ignored.
 */
public final class Bootstrap
{
	/** One jar of the release. The hash gates the download and every later launch; the name is the cache file. */
	public record Artifact(String name, String path, long size, String hash)
	{
	}

	private record PlatformEntry(String name, String arch)
	{
	}

	private record RawArtifact(Artifact artifact, List<PlatformEntry> platforms)
	{
	}

	private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	public final String version;
	private final List<String> clientJvmArguments;
	private final List<String> clientJvmMacArguments;
	private final List<RawArtifact> artifacts;

	private Bootstrap(String version, List<String> clientJvmArguments, List<String> clientJvmMacArguments,
		List<RawArtifact> artifacts)
	{
		this.version = version;
		this.clientJvmArguments = clientJvmArguments;
		this.clientJvmMacArguments = clientJvmMacArguments;
		this.artifacts = artifacts;
	}

	/**
	 * The jars this system runs, in the bootstrap's order, which is the classpath order. An artifact with no
	 * platform list is everyone's. In RuneLite's bootstrap an entry without an arch means x64: the x86 and aarch64
	 * natives name their arch, the x64 ones don't.
	 */
	public List<Artifact> artifacts(Platform platform)
	{
		String os = switch (platform.os())
		{
			case WINDOWS -> "win";
			case MAC -> "macos";
			case LINUX -> "linux";
		};
		List<Artifact> matching = new ArrayList<>();
		for (RawArtifact raw : artifacts)
		{
			boolean matches = raw.platforms().isEmpty();
			for (PlatformEntry entry : raw.platforms())
			{
				matches |= os.equals(entry.name()) && (entry.arch() == null
					? platform.arch() == Platform.Arch.X64
					: entry.arch().equals("aarch64") && platform.arch() == Platform.Arch.AARCH64);
			}
			if (matches)
			{
				matching.add(raw.artifact());
			}
		}
		return List.copyOf(matching);
	}

	/** The client JVM's arguments for this system; Macs have a list of their own (the eawt open, no DLL list). */
	public List<String> clientJvmArguments(Platform platform)
	{
		return platform.os() == Platform.Os.MAC && !clientJvmMacArguments.isEmpty()
			? clientJvmMacArguments
			: clientJvmArguments;
	}

	/** Parses bytes whose pin hash and RuneLite signature already checked out. */
	public static Bootstrap parse(String json) throws BootstrapException
	{
		JsonObject root;
		try
		{
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject())
			{
				throw new BootstrapException("the bootstrap isn't a JSON object");
			}
			root = parsed.getAsJsonObject();
		}
		catch (JsonSyntaxException e)
		{
			throw new BootstrapException("the bootstrap isn't JSON: " + e.getMessage());
		}

		String version = string(root, "version");
		List<String> jvm = strings(root, "clientJvm17Arguments");
		List<String> jvmMac = root.has("clientJvm17MacArguments") ? strings(root, "clientJvm17MacArguments") : List.of();

		JsonElement artifactsElement = root.get("artifacts");
		if (artifactsElement == null || !artifactsElement.isJsonArray())
		{
			throw new BootstrapException("the bootstrap has no \"artifacts\" list");
		}
		List<RawArtifact> artifacts = new ArrayList<>();
		for (JsonElement element : artifactsElement.getAsJsonArray())
		{
			if (!element.isJsonObject())
			{
				throw new BootstrapException("an artifact isn't an object");
			}
			JsonObject artifact = element.getAsJsonObject();
			String name = string(artifact, "name");
			if (!FILE_NAME.matcher(name).matches())
			{
				// The name becomes a file in our cache folder, so nothing that could escape it.
				throw new BootstrapException("unsafe artifact name: \"" + name + "\"");
			}
			String path = string(artifact, "path");
			if (!path.startsWith("https://") && !path.startsWith("http://127.0.0.1:") && !path.startsWith("http://localhost:"))
			{
				// https for anything real; plain http only back to this same machine (tests serve their jars on
				// loopback), and the SHA-256 against this signed bootstrap gates every byte either way.
				throw new BootstrapException(name + " isn't fetched over https: " + path);
			}
			String hash = string(artifact, "hash");
			if (!SHA256.matcher(hash).matches())
			{
				throw new BootstrapException(name + "'s hash isn't 64 lowercase hex characters");
			}
			JsonElement sizeElement = artifact.get("size");
			long size;
			try
			{
				if (sizeElement == null || !sizeElement.isJsonPrimitive() || !sizeElement.getAsJsonPrimitive().isNumber())
				{
					throw new NumberFormatException();
				}
				size = Long.parseLong(sizeElement.getAsString());
			}
			catch (NumberFormatException e)
			{
				throw new BootstrapException(name + "'s size isn't a whole number");
			}
			if (size < 0)
			{
				throw new BootstrapException(name + "'s size is negative");
			}
			List<PlatformEntry> platforms = new ArrayList<>();
			if (artifact.get("platform") instanceof JsonArray platformList)
			{
				for (JsonElement platformElement : platformList)
				{
					if (!platformElement.isJsonObject())
					{
						throw new BootstrapException(name + " has a platform entry that isn't an object");
					}
					JsonObject entry = platformElement.getAsJsonObject();
					String arch = entry.has("arch") && !entry.get("arch").isJsonNull()
						? string(entry, "arch") : null;
					platforms.add(new PlatformEntry(string(entry, "name"), arch));
				}
			}
			artifacts.add(new RawArtifact(new Artifact(name, path, size, hash), List.copyOf(platforms)));
		}
		if (artifacts.isEmpty())
		{
			throw new BootstrapException("the bootstrap lists no artifacts");
		}
		return new Bootstrap(version, jvm, jvmMac, List.copyOf(artifacts));
	}

	private static String string(JsonObject object, String name) throws BootstrapException
	{
		JsonElement element = object.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
		{
			throw new BootstrapException("the bootstrap's \"" + name + "\" is missing or isn't a string");
		}
		return element.getAsString();
	}

	private static List<String> strings(JsonObject object, String name) throws BootstrapException
	{
		JsonElement element = object.get(name);
		if (element == null || !element.isJsonArray())
		{
			throw new BootstrapException("the bootstrap's \"" + name + "\" is missing or isn't a list");
		}
		List<String> values = new ArrayList<>();
		for (JsonElement entry : element.getAsJsonArray())
		{
			if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString())
			{
				throw new BootstrapException("the bootstrap's \"" + name + "\" holds a non-string");
			}
			values.add(entry.getAsString());
		}
		return List.copyOf(values);
	}
}
