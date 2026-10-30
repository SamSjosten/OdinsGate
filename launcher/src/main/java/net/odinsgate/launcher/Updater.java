package net.odinsgate.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The self-update bootstrap: what the installed launcher (the thin shell under the installer's runtime) does before
 * anything else, to decide which launcher version runs and with which manifest.
 *
 * <p>It fetches the latest release's manifest and signature from GitHub (the server isn't involved, so updates
 * reach friends while it is down), takes the manifest only when its Ed25519 signature verifies against the trusted
 * keys (the built-in primary and offline backup, or a primary a backup-signed manifest named since), refuses a
 * remote manifest older than the last one it accepted (an emergency rollback ships as a newer version), and keeps
 * the accepted one under the data folder for a start with no internet. A manifest of a newer format, or one whose
 * {@code minBootstrapVersion} is above this shell's version, means "please download the new installer".
 *
 * <p>A version the manifest describes that isn't here yet is downloaded whole to a temporary folder, each file
 * checked against its SHA-256 and size, moved into {@code versions/<v>/} (moves tried again while antivirus holds
 * a file), and only then does the {@code current} pointer switch. Before each launch the chosen version's files are
 * hashed again against the signed manifest kept beside them; a broken pointer or file falls back to the newest
 * version that verifies, and when none does, to the installed shell itself.
 *
 * <p>A started version writes a healthy marker once its window is up (or its first check done); a version that was
 * started and never became healthy (a crash), or that hasn't within the watchdog's minute (a hang), is listed as
 * bad: never run again and never downloaded again, until a newer version appears. The started marker names the
 * process, so a second launcher opened while the first is still coming up sees a start under way, not a crash.
 * Nothing here depends on the network being there: with nothing reachable the last good version starts.
 */
public final class Updater
{
	/** Where the release assets live; {@code latest/download/<asset>} follows GitHub's redirect to the newest. */
	static final String RELEASES = "https://github.com/SamSjosten/OSRSPS/releases";
	/**
	 * The page a friend is sent to for a new installer: the download page (docs/ in the repo, served by GitHub
	 * Pages, one button per system), built in, never taken from the network.
	 */
	static final URI DOWNLOAD_PAGE = URI.create("https://samsjosten.github.io/OSRSPS/");
	static final String MANIFEST_NAME = "manifest.json";
	static final String SIGNATURE_SUFFIX = ".sig";
	/** A manifest and signature on their way into place, written whole before either moves. */
	static final String NEW_SUFFIX = ".new";
	static final String VERSIONS_DIR = "versions";
	static final String DOWNLOADING_SUFFIX = ".downloading";
	static final String POINTER = "current";
	static final String BAD_LIST = "bad-versions.txt";
	static final String KEYS_FILE = "keys.json";
	static final String STARTED_MARKER = ".started";
	static final String HEALTHY_MARKER = ".healthy";
	/** How long a started version has to become healthy before the watchdog lists it as bad. */
	static final Duration HEALTHY_WITHIN = Duration.ofSeconds(60);
	static final int MOVE_TRIES = 5;
	static final Duration MOVE_RETRY = Duration.ofMillis(500);
	static final Duration TIMEOUT = Duration.ofSeconds(20);
	/** Jars are a few megabytes; nothing a manifest names is bigger, and a redirect to something else is refused. */
	static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
	private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");

	/** One GET: the body for a 200, an {@link IOException} for anything else. */
	interface Fetcher
	{
		byte[] fetch(URI uri) throws IOException;
	}

	/** One move; the real one is {@link Files#move}, a test's can fail like a file antivirus holds. */
	interface Mover
	{
		void move(Path from, Path to) throws IOException;
	}

	interface Sleeper
	{
		void sleep(Duration duration) throws InterruptedException;
	}

	/** What the shell does next. */
	public sealed interface Outcome permits Run, NeedsInstaller, NoManifest
	{
	}

	/**
	 * Run {@code version}: the installed shell itself when {@code versionDir} is null, else that downloaded version
	 * through {@link VersionLoader} from {@code jars}, the jars its own signed manifest names (and so the ones hashed
	 * before this; a jar dropped into its folder is never loaded), with {@code manifest} (kept at
	 * {@code manifestFile}) as the signed truth.
	 */
	public record Run(String version, Path versionDir, List<Path> jars, Manifest manifest, Path manifestFile)
		implements Outcome
	{
		public boolean installed()
		{
			return versionDir == null;
		}
	}

	/** The release needs a newer installer than this shell: show {@code why} and the download page, start nothing. */
	public record NeedsInstaller(String why) implements Outcome
	{
	}

	/** No manifest anywhere, so nothing to run: a first start with no internet, or a build with no keys yet. */
	public record NoManifest(String why) implements Outcome
	{
	}

	/** A manifest with the bytes and signature it was verified from, and the file it is kept in. */
	record Loaded(byte[] bytes, String signature, Manifest manifest, Path file)
	{
	}

	private final Path dataDir;
	private final String shellVersion;
	private final Path appDir;
	private final List<PublicKey> builtInKeys;
	private final PublicKey backupKey;
	private final String releases;
	private final Fetcher fetcher;
	private final Mover mover;
	private final Sleeper sleeper;
	private final Clock clock;
	private final PrintStream log;

	/**
	 * @param dataDir the launcher's data folder, where versions, the accepted manifest and the markers live
	 * @param shellVersion this installed launcher's own version (its jar's), {@code dev} from classes
	 * @param appDir the installed layout's folder (holding {@code lib/}), which may carry a bundled manifest and its
	 * signature for a first start with no internet; null when unknown
	 * @param builtInKeys the trusted keys built in, primary first and the offline backup last
	 * @param releases the releases URL, with no trailing slash
	 */
	Updater(Path dataDir, String shellVersion, Path appDir, List<PublicKey> builtInKeys, String releases,
		Fetcher fetcher, Mover mover, Sleeper sleeper, Clock clock, PrintStream log)
	{
		this.dataDir = dataDir;
		this.shellVersion = shellVersion;
		this.appDir = appDir;
		this.builtInKeys = List.copyOf(builtInKeys);
		this.backupKey = builtInKeys.isEmpty() ? null : builtInKeys.get(builtInKeys.size() - 1);
		this.releases = releases;
		this.fetcher = fetcher;
		this.mover = mover;
		this.sleeper = sleeper;
		this.clock = clock;
		this.log = log;
	}

	/** The real one: GitHub over HTTPS, the file system, and {@link TrustedKeys}. */
	public static Updater real(Path dataDir, String shellVersion, Path appDir, PrintStream log)
	{
		HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		return new Updater(dataDir, shellVersion, appDir, TrustedKeys.trusted(), RELEASES,
			fetcher(http, TIMEOUT, MAX_FILE_BYTES),
			(from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE), Thread::sleep,
			Clock.systemUTC(), log);
	}

	/**
	 * One GET over {@code http}, the whole answer within {@code timeout} (the client's own request timeout covers
	 * the headers only; a body that stalls after them would otherwise hold the launcher before its window opens),
	 * and at most {@code maxBytes} of it: a body whose Content-Length is already past that is dropped unread, and
	 * one without a length is counted as it comes and cut off the moment it passes the limit, so no more than that
	 * is ever held.
	 */
	static Fetcher fetcher(HttpClient http, Duration timeout, long maxBytes)
	{
		return uri ->
		{
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
			HttpResponse.BodyHandler<byte[]> bounded = info ->
				info.headers().firstValueAsLong("content-length").orElse(0) > maxBytes
					? HttpResponse.BodySubscribers.replacing(null)
					: new Bounded(maxBytes);
			CompletableFuture<HttpResponse<byte[]>> sent = http.sendAsync(request, bounded);
			HttpResponse<byte[]> response;
			try
			{
				response = sent.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException e)
			{
				sent.cancel(true);
				throw new IOException(uri + " didn't answer whole within " + timeout.toMillis() + " ms");
			}
			catch (ExecutionException e)
			{
				Throwable cause = e.getCause() == null ? e : e.getCause();
				throw new IOException(uri + ": " + cause.getMessage(), cause);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				sent.cancel(true);
				throw new IOException("interrupted while fetching " + uri, e);
			}
			if (response.statusCode() != 200)
			{
				throw new IOException(uri + " answered " + response.statusCode());
			}
			if (response.body() == null || response.body().length > maxBytes)
			{
				throw new IOException(uri + " is more than " + maxBytes + " bytes, more than a release file");
			}
			return response.body();
		};
	}

	/**
	 * Collects a body up to {@code maxBytes}; the first byte past that cancels the download and the body is null,
	 * which the fetcher reports as more than a release file. Nothing bigger than the limit is ever held.
	 */
	private static final class Bounded implements HttpResponse.BodySubscriber<byte[]>
	{
		private final HttpResponse.BodySubscriber<byte[]> whole = HttpResponse.BodySubscribers.ofByteArray();
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final long maxBytes;
		private Flow.Subscription subscription;
		private long received;

		Bounded(long maxBytes)
		{
			this.maxBytes = maxBytes;
		}

		@Override
		public CompletionStage<byte[]> getBody()
		{
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription)
		{
			this.subscription = subscription;
			whole.onSubscribe(subscription);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers)
		{
			for (ByteBuffer buffer : buffers)
			{
				received += buffer.remaining();
			}
			if (received > maxBytes)
			{
				subscription.cancel();
				body.complete(null);
				return;
			}
			whole.onNext(buffers);
		}

		@Override
		public void onError(Throwable throwable)
		{
			whole.onError(throwable);
			body.completeExceptionally(throwable);
		}

		@Override
		public void onComplete()
		{
			whole.onComplete();
			whole.getBody().whenComplete((bytes, throwable) ->
			{
				if (throwable == null)
				{
					body.complete(bytes);
				}
				else
				{
					body.completeExceptionally(throwable);
				}
			});
		}
	}

	/** The whole decision: see the class comment. Never throws; what went wrong is in the log. */
	public Outcome update()
	{
		listCrashedVersions();
		List<PublicKey> trusted = trustedKeys();
		repairKept(dataDir, trusted);
		if (trusted.isEmpty())
		{
			log.println("[updater] no release keys are built into this launcher: updates are off");
		}
		Loaded onDisk = load(dataDir.resolve(MANIFEST_NAME), "the accepted manifest", trusted);
		Loaded stored = onDisk != null ? onDisk : keptByNewestVersion(trusted);
		Loaded remote = null;
		byte[] bytes = null;
		String signature = null;
		if (!trusted.isEmpty())
		{
			try
			{
				bytes = fetcher.fetch(URI.create(releases + "/latest/download/" + MANIFEST_NAME));
				signature = new String(fetcher.fetch(URI.create(releases + "/latest/download/" + MANIFEST_NAME
					+ SIGNATURE_SUFFIX)), StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				log.println("[updater] no release manifest from GitHub: " + e.getMessage());
				bytes = null;
			}
		}
		if (bytes != null)
		{
			if (!Signatures.verify(bytes, signature, trusted))
			{
				log.println("[updater] the release manifest's signature doesn't verify against any trusted key: "
					+ "ignored");
			}
			else
			{
				String json = new String(bytes, StandardCharsets.UTF_8);
				long format = Manifest.formatOf(json);
				if (format > Manifest.FORMAT)
				{
					return new NeedsInstaller("the release manifest is format " + format + " and this launcher reads "
						+ "format " + Manifest.FORMAT);
				}
				try
				{
					remote = new Loaded(bytes, signature, Manifest.parse(json), dataDir.resolve(MANIFEST_NAME));
				}
				catch (ManifestException e)
				{
					log.println("[updater] the release manifest doesn't read: " + e.getMessage() + ": ignored");
				}
			}
		}
		if (remote != null && stored != null
			&& Versions.compare(remote.manifest().version, stored.manifest().version) < 0)
		{
			// An old manifest replayed from a cache or a mirror: it names nothing, not even a key.
			log.println("[updater] the release manifest is version " + remote.manifest().version
				+ ", older than the accepted " + stored.manifest().version + ": ignored");
			remote = null;
		}
		if (remote != null)
		{
			rotatePrimaryKeyIfNamed(remote, trusted);
		}
		if (remote != null && (onDisk == null || !Arrays.equals(onDisk.bytes(), remote.bytes())))
		{
			try
			{
				keep(remote, dataDir);
				log.println("[updater] accepted the release manifest for version " + remote.manifest().version);
			}
			catch (IOException e)
			{
				// What a downloaded version is handed is the kept file, so what can't be kept isn't followed.
				log.println("[updater] the release manifest could not be kept: " + e.getMessage()
					+ (stored == null ? "" : ": the accepted " + stored.manifest().version + " stands"));
				remote = stored == null ? remote : null;
			}
		}
		Loaded accepted = remote;
		if (accepted == null)
		{
			accepted = stored;
		}
		if (accepted == null && appDir != null)
		{
			accepted = load(appDir.resolve(MANIFEST_NAME), "the bundled manifest", trusted);
		}
		if (accepted == null)
		{
			return new NoManifest(trusted.isEmpty()
				? "no release keys are built into this launcher yet, so no manifest can be trusted"
				: "no release manifest could be read: " + Names.APP_NAME + " needs the internet once to set up");
		}
		Manifest manifest = accepted.manifest();
		if (Versions.isNewer(manifest.minBootstrapVersion, shellVersion))
		{
			return new NeedsInstaller("this launcher is version " + shellVersion + " and the release needs "
				+ manifest.minBootstrapVersion + " or newer");
		}
		String target = manifest.version;
		if (Versions.isNewer(target, shellVersion) && !isBad(target))
		{
			// In place already (a pointer write that failed last time is tried again), or downloaded now.
			boolean inPlace = verifiedVersion(target, trusted).isPresent() || download(target, accepted);
			if (inPlace && !target.equals(readPointer()))
			{
				try
				{
					Atomically.write(dataDir.resolve(POINTER), target + "\n");
					log.println("[updater] version " + target + " is in place and current");
				}
				catch (IOException e)
				{
					log.println("[updater] the current pointer could not be written: " + e.getMessage());
				}
			}
		}
		return choose(accepted, trusted);
	}

	/** The run: the pointer's version when it verifies, else the newest that does, else the installed shell. */
	private Outcome choose(Loaded accepted, List<PublicKey> trusted)
	{
		Map<String, Manifest> verified = verifiedVersions(trusted);
		String pointer = readPointer();
		String chosen = null;
		if (pointer != null && verified.containsKey(pointer))
		{
			chosen = pointer;
		}
		else if (!verified.isEmpty())
		{
			chosen = verified.keySet().iterator().next();
			log.println("[updater] the current pointer " + (pointer == null ? "is missing or unreadable" : "names "
				+ pointer + ", which doesn't verify") + ": falling back to " + chosen);
		}
		if (chosen == null || Versions.compare(shellVersion, chosen) >= 0)
		{
			log.println("[updater] running the installed launcher " + shellVersion
				+ (chosen == null ? "" : " (newer than " + chosen + ")") + " with manifest "
				+ accepted.manifest().version);
			return new Run(shellVersion, null, List.of(), accepted.manifest(), accepted.file());
		}
		log.println("[updater] running version " + chosen + " with manifest " + accepted.manifest().version);
		return new Run(chosen, versionDir(chosen), jarsOf(chosen, verified.get(chosen)), accepted.manifest(),
			accepted.file());
	}

	/** The jars {@code version}'s own signed manifest {@code kept} names, under its folder: the class path. */
	private List<Path> jarsOf(String version, Manifest kept)
	{
		List<Path> jars = new ArrayList<>();
		for (Manifest.FileEntry entry : kept.files)
		{
			if (entry.path().endsWith(".jar"))
			{
				jars.add(versionDir(version).resolve(entry.path()));
			}
		}
		return jars;
	}

	/** The keys a manifest may be signed with now: a primary the backup key named since, else the built-in ones. */
	List<PublicKey> trustedKeys()
	{
		Path file = dataDir.resolve(KEYS_FILE);
		if (Files.isRegularFile(file) && backupKey != null)
		{
			try
			{
				String json = Files.readString(file, StandardCharsets.UTF_8);
				String primary = com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("primary")
					.getAsString();
				return List.of(Signatures.publicKey(primary), backupKey);
			}
			catch (IOException | RuntimeException e)
			{
				log.println("[updater] " + file + " doesn't read, so the built-in keys stand: " + e.getMessage());
			}
		}
		return builtInKeys;
	}

	/**
	 * A manifest signed by the backup key that names a new primary rotates the trust: from then on that key and the
	 * backup are the trusted ones, and the old primary (lost, or leaked) signs nothing a friend takes.
	 */
	private void rotatePrimaryKeyIfNamed(Loaded remote, List<PublicKey> trusted)
	{
		String named = remote.manifest().nextPrimaryKey;
		if (named == null || backupKey == null)
		{
			return;
		}
		if (!Signatures.verify(remote.bytes(), remote.signature(), List.of(backupKey)))
		{
			log.println("[updater] the manifest names a new primary key but isn't signed by the backup key: "
				+ "not rotated");
			return;
		}
		String current = Base64.getEncoder().encodeToString(trusted.get(0).getEncoded());
		if (current.equals(named))
		{
			return;
		}
		try
		{
			Atomically.write(dataDir.resolve(KEYS_FILE), "{ \"primary\": \"" + named + "\" }\n");
			log.println("[updater] the backup key named a new primary key: rotated");
		}
		catch (IOException e)
		{
			log.println("[updater] the new primary key could not be kept: " + e.getMessage());
		}
	}

	/** A manifest and its signature from {@code file} and {@code file.sig}, verified and parsed; null otherwise. */
	private Loaded load(Path file, String what, List<PublicKey> trusted)
	{
		Path signatureFile = file.resolveSibling(file.getFileName() + SIGNATURE_SUFFIX);
		if (!Files.isRegularFile(file) || !Files.isRegularFile(signatureFile))
		{
			return null;
		}
		try
		{
			byte[] bytes = Files.readAllBytes(file);
			String signature = Files.readString(signatureFile, StandardCharsets.UTF_8);
			Manifest manifest = Manifest.verified(bytes, signature, trusted);
			return new Loaded(bytes, signature, manifest, file);
		}
		catch (IOException | ManifestException e)
		{
			log.println("[updater] " + what + " at " + file + " is no use: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Keeps the manifest and its signature under {@code dir} so that a stop at any point (a crash, a disk that
	 * fails) leaves the old pair whole, the new pair whole, or a state {@link #repairKept} finishes: both are written
	 * whole under {@code .new} names first, then the signature moves into place, then the manifest.
	 */
	private static void keep(Loaded loaded, Path dir) throws IOException
	{
		Files.createDirectories(dir);
		Path newManifest = dir.resolve(MANIFEST_NAME + NEW_SUFFIX);
		Path newSignature = dir.resolve(MANIFEST_NAME + NEW_SUFFIX + SIGNATURE_SUFFIX);
		Files.write(newManifest, loaded.bytes());
		Atomically.write(newSignature, loaded.signature());
		Files.move(newSignature, dir.resolve(MANIFEST_NAME + SIGNATURE_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
		Files.move(newManifest, dir.resolve(MANIFEST_NAME), StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Finishes or undoes a {@link #keep} that stopped half way. Both {@code .new} files there: nothing had moved, so
	 * the old pair stands and they go (the release is fetched again). The {@code .new} manifest alone: either the
	 * signature had moved and the manifest hadn't, or the stop came while the manifest was being written, before
	 * the signature was even staged. The signature in place tells which: the manifest is moved only when it
	 * verifies against that signature, and goes otherwise, the old pair standing.
	 */
	private void repairKept(Path dir, List<PublicKey> trusted)
	{
		Path newManifest = dir.resolve(MANIFEST_NAME + NEW_SUFFIX);
		Path newSignature = dir.resolve(MANIFEST_NAME + NEW_SUFFIX + SIGNATURE_SUFFIX);
		Path signature = dir.resolve(MANIFEST_NAME + SIGNATURE_SUFFIX);
		try
		{
			if (Files.exists(newSignature))
			{
				Files.deleteIfExists(newSignature);
				Files.deleteIfExists(newManifest);
			}
			else if (Files.exists(newManifest))
			{
				if (Files.isRegularFile(signature) && Signatures.verify(Files.readAllBytes(newManifest),
					Files.readString(signature, StandardCharsets.UTF_8), trusted))
				{
					Files.move(newManifest, dir.resolve(MANIFEST_NAME), StandardCopyOption.REPLACE_EXISTING);
					log.println("[updater] finished keeping the accepted manifest, which a stop had left half moved");
				}
				else
				{
					Files.delete(newManifest);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.println("[updater] what a stop left of the accepted manifest could not be cleared: " + e.getMessage());
		}
	}

	/**
	 * When the accepted pair is no use (torn by a stop that {@link #repairKept} couldn't finish, or a file that
	 * changed under us): the manifest kept beside the newest downloaded version that verifies, a signed one that was
	 * accepted once, so a start with no internet still has the versions it downloaded. The accepted pair is written
	 * again from the next release manifest fetched.
	 */
	private Loaded keptByNewestVersion(List<PublicKey> trusted)
	{
		for (String version : verifiedVersions(trusted).keySet())
		{
			Loaded kept = load(versionDir(version).resolve(MANIFEST_NAME), "version " + version + "'s manifest",
				trusted);
			if (kept != null)
			{
				log.println("[updater] the manifest kept with version " + version + " stands in for the accepted one");
				return kept;
			}
		}
		return null;
	}

	/**
	 * Every file of {@code target} into a temporary folder, each checked, then the manifest beside them, then one
	 * move into place; true when the version is in place. Anything wrong leaves nothing behind.
	 */
	private boolean download(String target, Loaded accepted)
	{
		Path temp = dataDir.resolve(VERSIONS_DIR).resolve(target + DOWNLOADING_SUFFIX);
		Path dir = versionDir(target);
		try
		{
			if (Files.exists(temp))
			{
				RuneLiteCache.deleteTree(temp);
			}
			Files.createDirectories(temp);
			for (Manifest.FileEntry entry : accepted.manifest().files)
			{
				String asset = entry.assetName();
				URI uri = URI.create(releases + "/download/v" + target + "/" + asset);
				byte[] bytes = fetcher.fetch(uri);
				if (bytes.length != entry.size() || !RuneLiteCache.sha256(bytes).equals(entry.sha256()))
				{
					throw new IOException(asset + " from " + uri + " isn't the file the manifest names (size "
						+ bytes.length + ", wanted " + entry.size() + ")");
				}
				Path file = temp.resolve(entry.path());
				Files.createDirectories(file.getParent());
				Files.write(file, bytes);
			}
			keep(accepted, temp);
			if (Files.exists(dir))
			{
				RuneLiteCache.deleteTree(dir);
			}
			moveWithRetries(temp, dir);
			log.println("[updater] downloaded version " + target + " (" + accepted.manifest().files.size() + " files)");
			return true;
		}
		catch (IOException e)
		{
			log.println("[updater] version " + target + " could not be downloaded: " + e.getMessage());
			try
			{
				if (Files.exists(temp))
				{
					RuneLiteCache.deleteTree(temp);
				}
			}
			catch (IOException cleanup)
			{
				log.println("[updater] and " + temp + " could not be removed: " + cleanup.getMessage());
			}
			return false;
		}
	}

	/** A move tried {@link #MOVE_TRIES} times, {@link #MOVE_RETRY} apart: antivirus holds a fresh file for a moment. */
	private void moveWithRetries(Path from, Path to) throws IOException
	{
		IOException last = null;
		for (int attempt = 1; attempt <= MOVE_TRIES; attempt++)
		{
			try
			{
				mover.move(from, to);
				return;
			}
			catch (IOException e)
			{
				last = e;
				log.println("[updater] move " + attempt + "/" + MOVE_TRIES + " of " + from.getFileName()
					+ " failed: " + e.getMessage());
				if (attempt < MOVE_TRIES)
				{
					try
					{
						sleeper.sleep(MOVE_RETRY);
					}
					catch (InterruptedException interrupted)
					{
						Thread.currentThread().interrupt();
						throw new IOException("interrupted while waiting to move " + from, interrupted);
					}
				}
			}
		}
		throw last;
	}

	/** The downloaded versions that verify and aren't bad, newest first, each with the manifest kept beside it. */
	private Map<String, Manifest> verifiedVersions(List<PublicKey> trusted)
	{
		// Newest first; names that compare equal (0.2 and 0.2.0) are still two folders, so the name breaks the tie.
		Map<String, Manifest> versions = new TreeMap<>((a, b) ->
		{
			int byVersion = Versions.compare(b, a);
			return byVersion != 0 ? byVersion : a.compareTo(b);
		});
		Path root = dataDir.resolve(VERSIONS_DIR);
		if (!Files.isDirectory(root))
		{
			return versions;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root))
		{
			for (Path dir : dirs)
			{
				String name = dir.getFileName().toString();
				if (VERSION.matcher(name).matches() && !isBad(name))
				{
					verifiedVersion(name, trusted).ifPresent(kept -> versions.put(name, kept));
				}
			}
		}
		catch (IOException e)
		{
			log.println("[updater] " + root + " could not be listed: " + e.getMessage());
		}
		return versions;
	}

	/**
	 * The manifest kept beside {@code version}'s files when it verifies, it describes that version, and every file
	 * it names is there with its size and SHA-256: the hashing before every launch.
	 */
	Optional<Manifest> verifiedVersion(String version, List<PublicKey> trusted)
	{
		Path dir = versionDir(version);
		Loaded kept = load(dir.resolve(MANIFEST_NAME), "version " + version + "'s manifest", trusted);
		if (kept == null || !kept.manifest().version.equals(version))
		{
			return Optional.empty();
		}
		for (Manifest.FileEntry entry : kept.manifest().files)
		{
			Path file = dir.resolve(entry.path());
			try
			{
				if (!Files.isRegularFile(file) || Files.size(file) != entry.size()
					|| !RuneLiteCache.sha256(Files.readAllBytes(file)).equals(entry.sha256()))
				{
					log.println("[updater] version " + version + " doesn't verify: " + entry.path()
						+ " is missing or isn't the file the manifest names");
					return Optional.empty();
				}
			}
			catch (IOException e)
			{
				log.println("[updater] version " + version + " doesn't verify: " + entry.path() + ": "
					+ e.getMessage());
				return Optional.empty();
			}
		}
		return Optional.of(kept.manifest());
	}

	Path versionDir(String version)
	{
		return dataDir.resolve(VERSIONS_DIR).resolve(version);
	}

	private String readPointer()
	{
		Path file = dataDir.resolve(POINTER);
		if (!Files.isRegularFile(file))
		{
			return null;
		}
		try
		{
			String version = Files.readString(file, StandardCharsets.UTF_8).trim();
			return VERSION.matcher(version).matches() ? version : null;
		}
		catch (IOException e)
		{
			log.println("[updater] the current pointer doesn't read: " + e.getMessage());
			return null;
		}
	}

	/** The versions listed as bad, one per line. */
	List<String> badVersions()
	{
		Path file = dataDir.resolve(BAD_LIST);
		if (!Files.isRegularFile(file))
		{
			return List.of();
		}
		try
		{
			return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::trim)
				.filter(line -> !line.isEmpty()).toList();
		}
		catch (IOException e)
		{
			log.println("[updater] " + file + " doesn't read: " + e.getMessage());
			return List.of();
		}
	}

	boolean isBad(String version)
	{
		return badVersions().contains(version);
	}

	/** Lists {@code version} as bad: never run and never downloaded again, until a newer version appears. */
	void markBad(String version, String why)
	{
		if (isBad(version))
		{
			return;
		}
		log.println("[updater] version " + version + " is bad (" + why + "): never again");
		try
		{
			Files.createDirectories(dataDir);
			Files.writeString(dataDir.resolve(BAD_LIST), version + "\n", StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			log.println("[updater] the bad-version list could not be written: " + e.getMessage());
		}
	}

	/** Before {@code version} is handed off to: the last run's healthy marker goes, the started one is written. */
	void markStarted(String version) throws IOException
	{
		Path dir = versionDir(version);
		Files.deleteIfExists(dir.resolve(HEALTHY_MARKER));
		ProcessHandle me = ProcessHandle.current();
		long since = me.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
		Files.writeString(dir.resolve(STARTED_MARKER), me.pid() + " " + since + " " + clock.instant() + "\n",
			StandardCharsets.UTF_8);
	}

	/**
	 * Whether the process a started marker names is still running (its pid, and its start time where the system
	 * tells it, so a pid given to some new process since doesn't count): a second launcher opened while the first
	 * was still coming up, which is no crash.
	 */
	private static boolean stillStarting(Path marker)
	{
		try
		{
			String[] parts = Files.readString(marker, StandardCharsets.UTF_8).trim().split(" ");
			long pid = Long.parseLong(parts[0]);
			long since = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
			Optional<ProcessHandle> process = ProcessHandle.of(pid);
			if (process.isEmpty() || !process.get().isAlive())
			{
				return false;
			}
			Optional<Instant> started = process.get().info().startInstant();
			return since == 0 || started.isEmpty() || started.get().toEpochMilli() == since;
		}
		catch (IOException | RuntimeException e)
		{
			// A marker that doesn't read names no running process.
			return false;
		}
	}

	/** The file the running version touches once it is up; {@code --healthy} on its command line. */
	Path healthyMarker(String version)
	{
		return versionDir(version).resolve(HEALTHY_MARKER);
	}

	boolean isHealthy(String version)
	{
		return Files.exists(healthyMarker(version));
	}

	/** The watchdog's verdict after its time: a version started and still not healthy is bad. */
	void markBadUnlessHealthy(String version)
	{
		if (!isHealthy(version))
		{
			markBad(version, "it hadn't become healthy within " + HEALTHY_WITHIN.toSeconds() + " seconds");
		}
	}

	/** A version that was started and never became healthy crashed (or hung and was closed): bad. */
	private void listCrashedVersions()
	{
		Path root = dataDir.resolve(VERSIONS_DIR);
		if (!Files.isDirectory(root))
		{
			return;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root))
		{
			for (Path dir : dirs)
			{
				String name = dir.getFileName().toString();
				if (VERSION.matcher(name).matches() && Files.exists(dir.resolve(STARTED_MARKER))
					&& !Files.exists(dir.resolve(HEALTHY_MARKER)) && !stillStarting(dir.resolve(STARTED_MARKER)))
				{
					markBad(name, "its last start never became healthy");
				}
			}
		}
		catch (IOException e)
		{
			log.println("[updater] " + root + " could not be listed: " + e.getMessage());
		}
	}

	/** A daemon thread that lists {@code version} as bad unless it is healthy after {@link #HEALTHY_WITHIN}. */
	Thread watchdog(String version)
	{
		Thread thread = new Thread(() ->
		{
			try
			{
				sleeper.sleep(HEALTHY_WITHIN);
			}
			catch (InterruptedException e)
			{
				return;
			}
			markBadUnlessHealthy(version);
		}, "launcher-watchdog");
		thread.setDaemon(true);
		return thread;
	}
}
