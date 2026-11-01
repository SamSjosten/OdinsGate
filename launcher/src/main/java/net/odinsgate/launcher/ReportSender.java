package net.odinsgate.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sends a problem report to our own server ({@code POST /report} on its HTTP port), which keeps it and files it as
 * an issue: the launcher holds no key or token for anything else, so the server is the only place it can go. The
 * server answers with the report's number ({@code R-0001}). A report the server can't take right now (it is down,
 * busy, or the friend has sent several in the hour) is kept in {@code outbox} and sent again by {@link #resend},
 * which the window calls at each start; a report the server refuses outright (too large, not a report) is not.
 */
final class ReportSender
{
	/** What became of a report. */
	sealed interface Outcome
	{
		/** The server has it, under {@code id}. */
		record Sent(String id) implements Outcome
		{
		}

		/** The server couldn't take it now; it waits in the outbox, or couldn't even be kept there. */
		record Kept(boolean saved) implements Outcome
		{
		}

		/** The server won't ever take it: {@code why} is its answer. */
		record Refused(String why) implements Outcome
		{
		}
	}

	static final String PATH = "/report";
	static final Duration TIMEOUT = Duration.ofSeconds(15);
	/** How many reports wait at most; the oldest go first. A friend who can't reach the server has the clipboard. */
	static final int OUTBOX_LIMIT = 10;
	/** The server's number for a report, the whole of a 201's body. */
	static final Pattern ID = Pattern.compile("R-\\d{1,9}");
	private static final String SUFFIX = ".txt";

	private final URI uri;
	private final Path outbox;
	private final HttpClient http;

	/**
	 * @param host the server's host
	 * @param httpPort its HTTP port
	 * @param outbox where reports that couldn't be sent wait: {@code <data>/reports}
	 */
	ReportSender(String host, int httpPort, Path outbox)
	{
		this(URI.create("http://" + host + ":" + httpPort + PATH), outbox, TIMEOUT);
	}

	ReportSender(URI uri, Path outbox, Duration timeout)
	{
		this.uri = uri;
		this.outbox = outbox;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	/** Sends {@code report}, keeping it in the outbox when the server can't take it now. Blocks; never throws. */
	Outcome send(String report, Instant now)
	{
		Outcome outcome = post(report);
		if (outcome instanceof Outcome.Kept)
		{
			return new Outcome.Kept(keep(report, now));
		}
		return outcome;
	}

	/**
	 * Sends what waits in the outbox, oldest first, until the server can't take one: a report sent or refused
	 * leaves the outbox. Blocks; never throws. Returns the numbers of the reports that went.
	 */
	List<String> resend()
	{
		List<String> sent = new ArrayList<>();
		for (Path file : waiting())
		{
			String report;
			try
			{
				report = Files.readString(file, StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				delete(file);
				continue;
			}
			Outcome outcome = post(report);
			if (outcome instanceof Outcome.Kept)
			{
				break;
			}
			if (outcome instanceof Outcome.Sent s)
			{
				sent.add(s.id());
			}
			delete(file);
		}
		return sent;
	}

	private Outcome post(String report)
	{
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.header("Content-Type", "text/plain; charset=UTF-8")
			.POST(HttpRequest.BodyPublishers.ofString(report, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response;
		try
		{
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			return new Outcome.Kept(false);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return new Outcome.Kept(false);
		}
		String body = response.body() == null ? "" : response.body().strip();
		int status = response.statusCode();
		if (status == 201 && ID.matcher(body).matches())
		{
			return new Outcome.Sent(body);
		}
		// Too many this hour, or the server is full or starting: worth trying again later. So is an answer that
		// isn't the server's (a 5xx from something in between, a 201 without a number), and a 404 from a server
		// that doesn't take reports yet (one older than this launcher).
		if (status == 429 || status == 404 || status >= 500 || status < 400)
		{
			return new Outcome.Kept(false);
		}
		return new Outcome.Refused(status + (body.isEmpty() ? "" : " " + firstLine(body)));
	}

	/** Saves {@code report} in the outbox, dropping the oldest beyond {@link #OUTBOX_LIMIT}; whether it was saved. */
	private boolean keep(String report, Instant now)
	{
		try
		{
			Files.createDirectories(outbox);
			Atomically.write(outbox.resolve(now.toEpochMilli() + "-" + Integer.toHexString(report.hashCode()) + SUFFIX),
				report);
		}
		catch (IOException e)
		{
			return false;
		}
		List<Path> all = waiting();
		for (int i = 0; i < all.size() - OUTBOX_LIMIT; i++)
		{
			delete(all.get(i));
		}
		return true;
	}

	/** The reports in the outbox, oldest first (their names start with the time they were kept). */
	List<Path> waiting()
	{
		if (!Files.isDirectory(outbox))
		{
			return List.of();
		}
		try (Stream<Path> files = Files.list(outbox))
		{
			return files.filter(f -> f.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
		}
		catch (IOException e)
		{
			return List.of();
		}
	}

	private static void delete(Path file)
	{
		try
		{
			Files.deleteIfExists(file);
		}
		catch (IOException ignored)
		{
			// Sent again next start; the server's numbers tell the two apart.
		}
	}

	private static String firstLine(String text)
	{
		String line = text.lines().findFirst().orElse("");
		return line.length() > 100 ? line.substring(0, 100) : line;
	}
}
