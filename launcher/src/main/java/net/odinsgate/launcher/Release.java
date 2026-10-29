package net.odinsgate.launcher;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The release workflow's tool ({@code .github/workflows/release.yml}, "Releases" in the README): writes a release's
 * manifest from the installed layout's jars, the server's pin table and its committed modulus; signs it with the
 * release key; and checks a signed pair against the keys an installed launcher trusts, so a release signed with a key
 * no launcher would accept is caught before anything is published. Nothing here is a secret: the key is a file the
 * workflow writes from its Environment for the one command and deletes, and a dry run signs with a throwaway key.
 *
 * <pre>
 * Release manifest --version 0.2.0 --min-bootstrap 0.1.0 --lib dist --pins runelite-pins.json
 *     --modulus rsa-modulus.txt --out manifest.json [--host odinsgate.net] [--game-port 43594]
 * Release sign --key release.pem --manifest manifest.json --out manifest.json.sig
 * Release verify --manifest manifest.json --signature manifest.json.sig [--key &lt;base64 SubjectPublicKeyInfo&gt;]
 * </pre>
 *
 * The manifest names every {@code .jar} in the lib folder as {@code lib/&lt;name&gt;}, which is what the installed
 * layout and {@link Updater} both use; the release asset for each is its bare name
 * ({@link Manifest.FileEntry#assetName}), under {@code releases/download/v&lt;version&gt;/}. {@code verify} without
 * {@code --key} uses {@link TrustedKeys}, the built-in ones. Exit 0 when all is well, 1 when a check fails, 2 for a
 * bad call.
 */
public final class Release
{
	/** The server friends' launchers dial: the relay's name, which the server answers for. */
	static final String DEFAULT_HOST = "odinsgate.net";
	static final int DEFAULT_GAME_PORT = 43594;
	static final String LIB_DIR = "lib";
	static final String LAUNCHER_JAR_PREFIX = "odinsgate-launcher-";

	private Release()
	{
	}

	public static void main(String[] args)
	{
		System.exit(run(args, TrustedKeys.trusted(), System.out));
	}

	/** The command line, with {@code trusted} standing in for the built-in keys; tests pass throwaway ones. */
	static int run(String[] args, List<PublicKey> trusted, PrintStream out)
	{
		if (args.length == 0)
		{
			out.println("usage: Release manifest|sign|verify ... (see the class comment)");
			return 2;
		}
		try
		{
			Options options = Options.parse(args, 1);
			return switch (args[0])
			{
				case "manifest" -> manifest(options, out);
				case "sign" -> sign(options, out);
				case "verify" -> verify(options, trusted, out);
				default -> throw new IllegalArgumentException("unknown command " + args[0]);
			};
		}
		catch (IllegalArgumentException e)
		{
			out.println("[release] " + e.getMessage());
			return 2;
		}
		catch (IOException | ManifestException e)
		{
			out.println("[release] " + e.getMessage());
			return 1;
		}
	}

	/**
	 * Writes the manifest for a release: format 1, the version and the lowest shell version that may run it, the
	 * server, the pin table as the server's file has it, and one entry per jar in the lib folder with its SHA-256 and
	 * size. The launcher's own jar must be there under the version's name, so a lib folder built from another version
	 * than the one named is caught. The result is parsed back the way a launcher would, so a bad modulus or pin
	 * fails here and never reaches a release.
	 */
	static int manifest(Options options, PrintStream out) throws IOException, ManifestException
	{
		String version = options.required("--version");
		String minBootstrap = options.required("--min-bootstrap");
		Path lib = Path.of(options.required("--lib"));
		Path pins = Path.of(options.required("--pins"));
		Path modulus = Path.of(options.required("--modulus"));
		Path outFile = Path.of(options.required("--out"));
		String host = options.optional("--host", DEFAULT_HOST);
		int gamePort = Integer.parseInt(options.optional("--game-port", Integer.toString(DEFAULT_GAME_PORT)));
		if (Versions.compare(minBootstrap, version) > 0)
		{
			throw new ManifestException("minBootstrapVersion " + minBootstrap + " is above the version " + version
				+ ": no installed launcher could run the release");
		}

		List<Path> jars;
		try (Stream<Path> files = Files.list(lib))
		{
			jars = files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar"))
				.sorted().toList();
		}
		if (jars.isEmpty())
		{
			throw new ManifestException("no jars in " + lib);
		}
		String launcherJar = LAUNCHER_JAR_PREFIX + version + ".jar";
		if (jars.stream().noneMatch(jar -> jar.getFileName().toString().equals(launcherJar)))
		{
			throw new ManifestException(lib + " has no " + launcherJar
				+ ": is the build's version the one in launcher/version.txt?");
		}

		JsonObject root = new JsonObject();
		root.addProperty("format", Manifest.FORMAT);
		root.addProperty("version", version);
		root.addProperty("minBootstrapVersion", minBootstrap);
		JsonObject server = new JsonObject();
		server.addProperty("host", host);
		server.addProperty("gamePort", gamePort);
		server.addProperty("httpPort", gamePort + 1);
		server.addProperty("modulus", Files.readString(modulus, StandardCharsets.UTF_8).trim().toLowerCase());
		root.add("server", server);
		root.add("pins", pinTable(pins));
		JsonArray files = new JsonArray();
		for (Path jar : jars)
		{
			byte[] bytes = Files.readAllBytes(jar);
			JsonObject entry = new JsonObject();
			entry.addProperty("path", LIB_DIR + "/" + jar.getFileName());
			entry.addProperty("sha256", RuneLiteCache.sha256(bytes));
			entry.addProperty("size", bytes.length);
			files.add(entry);
		}
		root.add("files", files);

		String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
		Manifest parsed = Manifest.parse(json);
		Set<String> assets = new HashSet<>();
		for (Manifest.FileEntry entry : parsed.files)
		{
			if (!assets.add(entry.assetName()))
			{
				throw new ManifestException("two files would be the release asset " + entry.assetName());
			}
		}
		Files.writeString(outFile, json, StandardCharsets.UTF_8);
		out.println("[release] wrote " + outFile + ": version " + version + " (shells from " + minBootstrap + "), "
			+ parsed.files.size() + " files, " + parsed.pins().size() + " pin(s), server " + host + ":" + gamePort);
		for (Manifest.FileEntry entry : parsed.files)
		{
			out.println("[release]   " + entry.assetName() + " " + entry.size() + " bytes " + entry.sha256());
		}
		return 0;
	}

	/** The server's pin table ({@code runelite-pins.json}: revision, runelite, commit, bootstrapSha256 per pin). */
	private static JsonArray pinTable(Path file) throws IOException, ManifestException
	{
		JsonElement root;
		try
		{
			root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (JsonSyntaxException e)
		{
			throw new ManifestException(file + " isn't JSON: " + e.getMessage());
		}
		if (!root.isJsonObject() || !root.getAsJsonObject().has("pins")
			|| !root.getAsJsonObject().get("pins").isJsonArray())
		{
			throw new ManifestException(file + " has no \"pins\" array");
		}
		JsonArray pins = new JsonArray();
		for (JsonElement element : root.getAsJsonObject().getAsJsonArray("pins"))
		{
			if (!element.isJsonObject())
			{
				throw new ManifestException(file + ": a pin isn't an object");
			}
			JsonObject from = element.getAsJsonObject();
			JsonObject pin = new JsonObject();
			for (String member : List.of("revision", "runelite", "commit", "bootstrapSha256"))
			{
				if (!from.has(member))
				{
					throw new ManifestException(file + ": a pin has no \"" + member + "\"");
				}
				pin.add(member, from.get(member));
			}
			pins.add(pin);
		}
		return pins;
	}

	/**
	 * Signs the manifest's exact bytes with the Ed25519 key in a PKCS#8 PEM file ({@code openssl genpkey -algorithm
	 * ed25519}) and writes the base64 signature as the one line of the signature file.
	 */
	static int sign(Options options, PrintStream out) throws IOException, ManifestException
	{
		Path keyFile = Path.of(options.required("--key"));
		Path manifest = Path.of(options.required("--manifest"));
		Path outFile = Path.of(options.required("--out"));
		byte[] bytes = Files.readAllBytes(manifest);
		try
		{
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(privateKey(Files.readString(keyFile, StandardCharsets.UTF_8)));
			signer.update(bytes);
			String line = Base64.getEncoder().encodeToString(signer.sign()) + "\n";
			Files.writeString(outFile, line, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException e)
		{
			throw new ManifestException("signing with " + keyFile + " failed: " + e.getMessage());
		}
		out.println("[release] signed " + manifest + " (" + bytes.length + " bytes) into " + outFile);
		return 0;
	}

	/** The private key in a PKCS#8 PEM ("BEGIN PRIVATE KEY"); anything else is refused. */
	static PrivateKey privateKey(String pem) throws ManifestException
	{
		StringBuilder base64 = new StringBuilder();
		boolean inside = false;
		for (String line : pem.split("\\R"))
		{
			String trimmed = line.trim();
			if (trimmed.equals("-----BEGIN PRIVATE KEY-----"))
			{
				inside = true;
			}
			else if (trimmed.equals("-----END PRIVATE KEY-----"))
			{
				break;
			}
			else if (inside)
			{
				base64.append(trimmed);
			}
		}
		if (!inside || base64.isEmpty())
		{
			throw new ManifestException("the key file isn't a PKCS#8 PEM (\"-----BEGIN PRIVATE KEY-----\")");
		}
		try
		{
			byte[] der = Base64.getDecoder().decode(base64.toString());
			return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (IllegalArgumentException | GeneralSecurityException e)
		{
			throw new ManifestException("the key file doesn't hold an Ed25519 key: " + e.getMessage());
		}
	}

	/**
	 * Checks a manifest and its signature the way a launcher would ({@link Manifest#verified}): against the keys
	 * given with {@code --key}, or the built-in ones without. Fails with a word when none is built in yet, so no
	 * release is published that no installed launcher could take.
	 */
	static int verify(Options options, List<PublicKey> trusted, PrintStream out) throws IOException
	{
		Path manifest = Path.of(options.required("--manifest"));
		Path signature = Path.of(options.required("--signature"));
		List<PublicKey> keys = new ArrayList<>();
		for (String spki : options.all("--key"))
		{
			keys.add(Signatures.publicKey(spki));
		}
		String against = "the " + keys.size() + " key(s) given";
		if (keys.isEmpty())
		{
			if (trusted.isEmpty())
			{
				out.println("[release] no release keys are built into this launcher yet (TrustedKeys), so no installed "
					+ "launcher could take this release; put the public keys in before releasing");
				return 1;
			}
			keys = trusted;
			against = "the " + keys.size() + " built-in key(s)";
		}
		try
		{
			Manifest parsed = Manifest.verified(Files.readAllBytes(manifest),
				Files.readString(signature, StandardCharsets.UTF_8), keys);
			out.println("[release] " + manifest + " verifies against " + against + ": version " + parsed.version
				+ ", " + parsed.files.size() + " files");
			return 0;
		}
		catch (ManifestException e)
		{
			out.println("[release] " + manifest + " does NOT verify against " + against + ": " + e.getMessage());
			return 1;
		}
	}

	/** {@code --name value} pairs from {@code args[from..]}; a name may repeat. */
	static final class Options
	{
		private final List<String> names = new ArrayList<>();
		private final List<String> values = new ArrayList<>();

		static Options parse(String[] args, int from)
		{
			Options options = new Options();
			for (int i = from; i < args.length; i += 2)
			{
				if (!args[i].startsWith("--") || i + 1 >= args.length)
				{
					throw new IllegalArgumentException("expected --name value, got \"" + args[i] + "\"");
				}
				options.names.add(args[i]);
				options.values.add(args[i + 1]);
			}
			return options;
		}

		String required(String name)
		{
			int at = names.indexOf(name);
			if (at < 0)
			{
				throw new IllegalArgumentException(name + " <value> is needed");
			}
			return values.get(at);
		}

		String optional(String name, String fallback)
		{
			int at = names.indexOf(name);
			return at < 0 ? fallback : values.get(at);
		}

		List<String> all(String name)
		{
			List<String> found = new ArrayList<>();
			for (int i = 0; i < names.size(); i++)
			{
				if (names.get(i).equals(name))
				{
					found.add(values.get(i));
				}
			}
			return found;
		}
	}
}
