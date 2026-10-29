package net.odinsgate.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the launcher checks before Play, and while the game runs. Everything read here is plain, unencrypted HTTP
 * from our server, so it is hints only: it may stop a launch or word a message, never decide what code runs or
 * which key is used - those come from the signed manifest. One check opens at most three connections (status, the
 * game port, the served key) with short timeouts, far under the relay's 20 new connections a minute, and only a
 * failure spends a fourth on the internet probe, to tell "the server is offline" from "you are offline".
 */
public final class ServerCheck
{
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
	/** Updates come from GitHub, so its reachability is the one that matters when our server doesn't answer. */
	public static final URI DEFAULT_INTERNET_PROBE = URI.create("https://github.com/");

	private static final Pattern YAML_MODULUS = Pattern.compile("^\\s*modulus:\\s*([0-9a-f]+)\\s*$", Pattern.MULTILINE);

	private final String host;
	private final int httpPort;
	private final int gamePort;
	private final Duration timeout;
	private final URI internetProbe;
	private final HttpClient http;

	public ServerCheck(Manifest.Server server)
	{
		this(server.host(), server.httpPort(), server.gamePort(), DEFAULT_TIMEOUT, DEFAULT_INTERNET_PROBE);
	}

	/** Tests pass their own ports, a short timeout and a probe URI they control. */
	public ServerCheck(String host, int httpPort, int gamePort, Duration timeout, URI internetProbe)
	{
		this.host = host;
		this.httpPort = httpPort;
		this.gamePort = gamePort;
		this.timeout = timeout;
		this.internetProbe = internetProbe;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	/** One outcome per check; the window words each for the friend, so the detail here is for the log. */
	public sealed interface Result
	{
		/** Both ports answer, the served key is the signed one and the manifest pins the live revision: launch. */
		record Ready(int revision, int players) implements Result
		{
		}

		/** Our server doesn't answer (or is still starting) while the internet does: offer Retry. */
		record Offline(String detail) implements Result
		{
		}

		/** Neither our server nor the probe answers: "Check your connection." */
		record NoInternet() implements Result
		{
		}

		/** The server moved to a revision this manifest has no pin for: "The launcher update is on its way." */
		record UnknownRevision(int revision) implements Result
		{
		}

		/** The served key isn't the signed one, so logins would fail: "Please tell Sam." The launch stops. */
		record KeyChanged() implements Result
		{
		}
	}

	public Result check(Manifest manifest)
	{
		String status;
		try
		{
			status = get("/status.json");
		}
		catch (IOException e)
		{
			return offlineUnless(e);
		}
		boolean up;
		int revision;
		int players;
		try
		{
			JsonObject parsed = JsonParser.parseString(status).getAsJsonObject();
			up = parsed.get("up").getAsBoolean();
			revision = parsed.get("revision").getAsInt();
			players = parsed.get("players").getAsInt();
		}
		catch (RuntimeException e)
		{
			// Something answered on the port, but not with our status page: a proxy's error page, say.
			return new Result.Offline("the server's status page isn't readable");
		}
		if (!up)
		{
			return new Result.Offline("the server is still starting");
		}
		try (Socket game = new Socket())
		{
			game.connect(new InetSocketAddress(host, gamePort), (int) timeout.toMillis());
		}
		catch (IOException e)
		{
			return new Result.Offline("the game port doesn't answer");
		}
		// The key before the revision: a changed key stops the launch whatever else is true.
		String served = servedModulus();
		if (served != null && !served.equals(manifest.server.modulus()))
		{
			return new Result.KeyChanged();
		}
		if (manifest.pin(revision) == null)
		{
			return new Result.UnknownRevision(revision);
		}
		return new Result.Ready(revision, players);
	}

	/**
	 * The modulus the server hands RSProx friends in proxy-targets.yaml, which is the key it actually runs with. A
	 * page that is missing or unreadable is null and skips the comparison: the page is a hint, and a mismatch shows
	 * up at login anyway. Only a page that plainly names another key stops the launch early with a plain answer.
	 */
	private String servedModulus()
	{
		try
		{
			Matcher modulus = YAML_MODULUS.matcher(get("/proxy-targets.yaml"));
			return modulus.find() ? modulus.group(1) : null;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/** The server failed; whether the friend sees "server offline" or "no internet" hangs on the probe. */
	private Result offlineUnless(IOException cause)
	{
		try
		{
			HttpRequest request = HttpRequest.newBuilder(internetProbe).timeout(timeout)
				.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
			http.send(request, HttpResponse.BodyHandlers.discarding());
			// Any answer at all, even an error page, proves the internet is there.
			return new Result.Offline("the server doesn't answer: " + cause.getMessage());
		}
		catch (IOException e)
		{
			return new Result.NoInternet();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return new Result.NoInternet();
		}
	}

	private String get(String path) throws IOException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + httpPort + path))
			.timeout(timeout).GET().build();
		try
		{
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
			{
				throw new IOException(path + " answered " + response.statusCode());
			}
			return response.body();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while fetching " + path, e);
		}
	}
}
