package net.odinsgate.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The launcher's own cache of RuneLite, one folder per pinned static.runelite.net commit, under our data folder and
 * never the friend's real RuneLite one - their normal launcher prunes that folder to its own bootstrap and would
 * delete these jars. Everything in it is checked on the way in and again on every read: the bootstrap against the
 * pin's SHA-256 and RuneLite's signature, each jar against the verified bootstrap's SHA-256 and size. A cached file
 * that fails its hash is thrown away and fetched again, so a filled, healthy cache starts the game with no network
 * at all, and a tampered one never starts it.
 */
public final class RuneLiteCache
{
	/** Where RuneLite keeps every release's bootstrap.json; the pin names the commit. */
	public static final String DEFAULT_STATIC_BASE = "https://raw.githubusercontent.com/runelite/static.runelite.net/";

	/** How long a pin folder no manifest names is kept after its last use, for other windows still holding it. */
	static final Duration RECENTLY_USED = Duration.ofDays(7);
	/** The slowest download a jar is waited for, in bytes a second (32 KiB). */
	static final long SLOWEST_BYTES_PER_SECOND = 32 * 1024;

	private final Path root;
	private final String staticBase;
	private final List<PublicKey> runeliteKeys;
	private final Duration timeout;
	private final Clock clock;
	private final HttpClient http;

	public RuneLiteCache(Path root)
	{
		this(root, DEFAULT_STATIC_BASE, RuneLiteSignature.trustedKeys(), ServerCheck.DEFAULT_TIMEOUT);
	}

	/** Tests pass their own bootstrap host and signing keys. */
	public RuneLiteCache(Path root, String staticBase, List<PublicKey> runeliteKeys, Duration timeout)
	{
		this(root, staticBase, runeliteKeys, timeout, Clock.systemUTC());
	}

	/** Tests also set the clock that marks a pin used and dates a prune's cut-off. */
	RuneLiteCache(Path root, String staticBase, List<PublicKey> runeliteKeys, Duration timeout, Clock clock)
	{
		this.root = root;
		this.staticBase = staticBase;
		this.runeliteKeys = runeliteKeys;
		this.timeout = timeout;
		this.clock = clock;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	/**
	 * The pin's bootstrap, from the cache when the cached copy still verifies, else fetched. Both ways it must
	 * carry the pin's SHA-256 and RuneLite's signature, so a cached copy serves an offline start and a tampered one
	 * is fetched anew instead of trusted.
	 */
	public Bootstrap bootstrap(Manifest.Pin pin) throws IOException, BootstrapException
	{
		Path dir = pinDir(pin);
		Path cached = dir.resolve("bootstrap.json");
		Path cachedSignature = dir.resolve("bootstrap.json.sha256");
		if (Files.exists(cached) && Files.exists(cachedSignature))
		{
			byte[] bytes = Files.readAllBytes(cached);
			if (sha256(bytes).equals(pin.bootstrapSha256())
				&& RuneLiteSignature.verify(bytes, Files.readAllBytes(cachedSignature), runeliteKeys))
			{
				return Bootstrap.parse(new String(bytes, StandardCharsets.UTF_8));
			}
		}

		byte[] bytes = fetch(staticBase + pin.commit() + "/bootstrap.json");
		byte[] signature = fetch(staticBase + pin.commit() + "/bootstrap.json.sha256");
		if (!sha256(bytes).equals(pin.bootstrapSha256()))
		{
			throw new BootstrapException("the bootstrap for " + pin.commit() + " doesn't carry the pinned SHA-256");
		}
		if (!RuneLiteSignature.verify(bytes, signature, runeliteKeys))
		{
			throw new BootstrapException("the bootstrap for " + pin.commit() + " doesn't carry RuneLite's signature");
		}
		Bootstrap bootstrap = Bootstrap.parse(new String(bytes, StandardCharsets.UTF_8));
		Files.createDirectories(dir);
		write(cached, bytes);
		write(cachedSignature, signature);
		return bootstrap;
	}

	/**
	 * This system's jars of the pin, each hashed against the verified bootstrap now, whether it was just downloaded
	 * or sat in the cache since last time - the hashing before every launch. The list is the bootstrap's order,
	 * which is the classpath. A cached file with the wrong bytes is deleted and fetched again; one that is wrong
	 * fresh off the network stops everything.
	 */
	public List<Path> jars(Manifest.Pin pin, Bootstrap bootstrap, Platform platform) throws IOException, BootstrapException
	{
		Path dir = pinDir(pin);
		Files.createDirectories(dir);
		List<Path> classpath = new ArrayList<>();
		for (Bootstrap.Artifact artifact : bootstrap.artifacts(platform))
		{
			Path jar = dir.resolve(artifact.name());
			if (!verifies(jar, artifact))
			{
				Files.deleteIfExists(jar);
				byte[] bytes = fetch(artifact.path(), deadlineFor(artifact.size()));
				if (bytes.length != artifact.size() || !sha256(bytes).equals(artifact.hash()))
				{
					throw new BootstrapException(artifact.name() + " from " + artifact.path()
						+ " isn't the file the bootstrap names (size " + bytes.length + ", wanted " + artifact.size() + ")");
				}
				write(jar, bytes);
			}
			classpath.add(jar);
		}
		// Marks the pin used, so a prune from another window spares it (see prune).
		Files.setLastModifiedTime(dir, FileTime.from(clock.instant()));
		return List.copyOf(classpath);
	}

	/**
	 * Deletes every pin folder but these and those used within {@link #RECENTLY_USED}: another launcher window on
	 * the same data folder, from an older or newer release, may hold a pin it fetched and is about to play.
	 */
	public void prune(Set<String> keepCommits) throws IOException
	{
		Instant usedBefore = clock.instant().minus(RECENTLY_USED);
		if (!Files.isDirectory(root))
		{
			return;
		}
		try (DirectoryStream<Path> pins = Files.newDirectoryStream(root))
		{
			for (Path pin : pins)
			{
				if (!keepCommits.contains(pin.getFileName().toString())
					&& Files.getLastModifiedTime(pin).toInstant().isBefore(usedBefore))
				{
					deleteTree(pin);
				}
			}
		}
	}

	private Path pinDir(Manifest.Pin pin)
	{
		// The commit is validated as 40 hex characters, so it is a safe folder name.
		return root.resolve(pin.commit());
	}

	private boolean verifies(Path jar, Bootstrap.Artifact artifact) throws IOException
	{
		return Files.exists(jar) && Files.size(jar) == artifact.size()
			&& sha256(Files.readAllBytes(jar)).equals(artifact.hash());
	}

	/** A small file (the bootstrap, its signature): the whole answer within the timeout. */
	private byte[] fetch(String url) throws IOException
	{
		return fetch(url, timeout);
	}

	/**
	 * One GET, the whole answer within {@code deadline}. The request's own timeout covers the headers only, so a
	 * body that stalls after them would hold the window in its download forever, with neither Play nor Retry; past
	 * the deadline the download is cancelled and fails like any other.
	 */
	private byte[] fetch(String url, Duration deadline) throws IOException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(deadline).GET().build();
		CompletableFuture<HttpResponse<byte[]>> sent =
			http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
		HttpResponse<byte[]> response;
		try
		{
			response = sent.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e)
		{
			sent.cancel(true);
			throw new IOException(url + " didn't arrive whole within " + deadline.toSeconds() + " s");
		}
		catch (ExecutionException e)
		{
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new IOException(url + ": " + cause.getMessage(), cause);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			sent.cancel(true);
			throw new IOException("interrupted while fetching " + url, e);
		}
		if (response.statusCode() != 200)
		{
			throw new IOException(url + " answered " + response.statusCode());
		}
		return response.body();
	}

	/**
	 * How long a jar of {@code size} bytes may take: the timeout, plus its time at
	 * {@value #SLOWEST_BYTES_PER_SECOND} bytes a second, a connection slow enough that the friend would rather be
	 * told and offered Retry.
	 */
	Duration deadlineFor(long size)
	{
		return timeout.plusSeconds(size / SLOWEST_BYTES_PER_SECOND);
	}

	/** Whole file or nothing: a temp file in the same folder, then an atomic-as-the-system-allows move. */
	private static void write(Path file, byte[] bytes) throws IOException
	{
		Path temp = file.resolveSibling(file.getFileName() + ".new");
		Files.write(temp, bytes);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	static void deleteTree(Path tree) throws IOException
	{
		try (var files = Files.walk(tree))
		{
			for (Path file : files.sorted(Comparator.reverseOrder()).toList())
			{
				Files.delete(file);
			}
		}
	}

	static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError(e);
		}
	}
}
