package net.odinsgate.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ReportSenderTest
{
	@TempDir
	Path dir;

	private HttpServer server;
	private final List<String> received = new ArrayList<>();
	/** What the fake server answers next: a status and a body; the last one repeats. */
	private final List<Object[]> answers = new ArrayList<>();
	private final AtomicInteger next = new AtomicInteger();
	private static final Instant NOW = Instant.parse("2026-11-01T05:00:00Z");

	private ReportSender sender()
	{
		return new ReportSender(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + ReportSender.PATH),
			dir.resolve("reports"), Duration.ofSeconds(5));
	}

	private void serve(Object[]... replies) throws Exception
	{
		answers.addAll(List.of(replies));
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(ReportSender.PATH, exchange ->
		{
			received.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Content-Type") + " "
				+ new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			Object[] answer = answers.get(Math.min(next.getAndIncrement(), answers.size() - 1));
			byte[] body = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders((int) answer[0], body.length == 0 ? -1 : body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
		}
	}

	@Test
	public void aReportTheServerTakesComesBackWithItsNumber() throws Exception
	{
		serve(new Object[]{201, "R-0042\n"});
		assertEquals(new ReportSender.Outcome.Sent("R-0042"), sender().send("Odin's Gate problem report\nlauncher 1.0.5", NOW));
		assertEquals(List.of("POST text/plain; charset=UTF-8 Odin's Gate problem report\nlauncher 1.0.5"), received);
		assertEquals(List.of(), sender().waiting(), "nothing is kept once it is sent");
	}

	@Test
	public void aBusyServerKeepsTheReportAndTheNextStartSendsIt() throws Exception
	{
		serve(new Object[]{429, "too many reports, try again in an hour\n"}, new Object[]{201, "R-0007\n"});
		ReportSender sender = sender();
		assertEquals(new ReportSender.Outcome.Kept(true), sender.send("first", NOW));
		assertEquals(1, sender.waiting().size());
		assertEquals(List.of("R-0007"), sender.resend());
		assertEquals(List.of(), sender.waiting());
		assertEquals("POST text/plain; charset=UTF-8 first", received.get(1));
	}

	@Test
	public void aReportIsInTheOutboxWhileItIsBeingSent() throws Exception
	{
		// A server that holds its answer: a launcher closed now must still find the report at its next start.
		CountDownLatch asked = new CountDownLatch(1);
		CountDownLatch answer = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(ReportSender.PATH, exchange ->
		{
			exchange.getRequestBody().readAllBytes();
			asked.countDown();
			try
			{
				answer.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			byte[] body = "R-0009\n".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(201, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		ReportSender sender = sender();
		ReportSender.Outcome[] outcome = new ReportSender.Outcome[1];
		Thread sending = new Thread(() -> outcome[0] = sender.send("report", NOW));
		sending.start();
		try
		{
			asked.await();
			assertEquals(1, sender.waiting().size(), "kept before the server answered");
		}
		finally
		{
			answer.countDown();
			sending.join();
		}
		assertEquals(new ReportSender.Outcome.Sent("R-0009"), outcome[0]);
		assertEquals(List.of(), sender.waiting(), "gone once it was sent");
	}

	@Test
	public void anUnreachableServerKeepsIt() throws Exception
	{
		int closed;
		try (ServerSocket socket = new ServerSocket(0))
		{
			closed = socket.getLocalPort();
		}
		ReportSender sender = new ReportSender(URI.create("http://127.0.0.1:" + closed + ReportSender.PATH),
			dir.resolve("reports"), Duration.ofSeconds(2));
		assertEquals(new ReportSender.Outcome.Kept(true), sender.send("report", NOW));
		assertEquals(List.of(), sender.resend(), "still down: nothing went");
		assertEquals(1, sender.waiting().size(), "and it still waits");
	}

	@Test
	public void aRefusedReportIsNotKept() throws Exception
	{
		serve(new Object[]{400, "not a problem report\n"});
		ReportSender sender = sender();
		assertEquals(new ReportSender.Outcome.Refused("400 not a problem report"), sender.send("hello", NOW));
		assertEquals(List.of(), sender.waiting());
	}

	@Test
	public void resendStopsAtTheFirstThatCantGoAndDropsRefusedOnes() throws Exception
	{
		serve(new Object[]{503, ""});
		ReportSender sender = sender();
		sender.send("a", NOW);
		sender.send("b", NOW.plusSeconds(1));
		sender.send("c", NOW.plusSeconds(2));
		answers.clear();
		next.set(0);
		received.clear();
		answers.add(new Object[]{201, "R-0001"});
		answers.add(new Object[]{413, "too large"});
		answers.add(new Object[]{503, ""});
		assertEquals(List.of("R-0001"), sender.resend());
		assertEquals(List.of("POST text/plain; charset=UTF-8 a", "POST text/plain; charset=UTF-8 b",
			"POST text/plain; charset=UTF-8 c"), received, "oldest first");
		assertEquals(1, sender.waiting().size(), "c still waits; a went and b was refused");
		assertEquals("c", Files.readString(sender.waiting().get(0)));
	}

	@Test
	public void anAnswerThatIsntTheServersIsTriedAgainLater() throws Exception
	{
		serve(new Object[]{201, "<html>a captive portal</html>"});
		assertEquals(new ReportSender.Outcome.Kept(true), sender().send("report", NOW));
	}

	@Test
	public void aServerThatDoesntTakeReportsYetKeepsThemForLater() throws Exception
	{
		serve(new Object[]{404, ""});
		ReportSender sender = sender();
		assertEquals(new ReportSender.Outcome.Kept(true), sender.send("report", NOW));
		assertEquals(1, sender.waiting().size());
	}

	@Test
	public void theOutboxKeepsOnlyTheNewestFew() throws Exception
	{
		serve(new Object[]{503, ""});
		ReportSender sender = sender();
		for (int i = 0; i < ReportSender.OUTBOX_LIMIT + 3; i++)
		{
			sender.send("report " + i, NOW.plusSeconds(i));
		}
		List<Path> waiting = sender.waiting();
		assertEquals(ReportSender.OUTBOX_LIMIT, waiting.size());
		assertEquals("report 3", Files.readString(waiting.get(0)), "the three oldest went");
		assertTrue(Files.readString(waiting.get(waiting.size() - 1)).endsWith(String.valueOf(ReportSender.OUTBOX_LIMIT + 2)));
	}
}
