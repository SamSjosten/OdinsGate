package net.odinsgate.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * The launcher's status file, one small JSON object rewritten whole at every change of state, so a script can wait
 * on it instead of on the server's {@code /version}: the VM's {@code start-client.ps1 -LauncherVersion} waits for
 * {@code version} to be the release it expects and {@code state} to be {@code RUNNING}. Written only when the launcher
 * was started with {@code --status-file}.
 *
 * <pre>
 * { "version": "0.1.0", "state": "RUNNING", "detail": "revision 241, pin 1.13.4",
 *   "revision": 241, "gamePid": 4321, "at": "2026-10-29T16:20:00Z" }
 * </pre>
 * {@code detail}, {@code revision} and {@code gamePid} are present only when known. Never a password, a key or a
 * friend's name: a status file may be pasted into a problem report.
 */
public final class StatusFile
{
	/** Where the launcher is, in the order a launch goes through them; FAILED and EXITED end a run. */
	public enum State
	{
		STARTING, CHECKING, UPDATING, DOWNLOADING, LAUNCHING, RUNNING, FAILED, EXITED
	}

	/**
	 * @param version the launcher's own version
	 * @param state where the launcher is
	 * @param detail a line for a person, or null
	 * @param revision the server's revision once the check has it (an unpinned one included), or null
	 * @param gamePid the game process once it is started, kept through its exit, or null
	 * @param at when this status was written
	 */
	public record Status(String version, State state, String detail, Integer revision, Long gamePid, Instant at)
	{
		public Status
		{
			Objects.requireNonNull(version);
			Objects.requireNonNull(state);
			Objects.requireNonNull(at);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private StatusFile()
	{
	}

	public static String toJson(Status status)
	{
		JsonObject object = new JsonObject();
		object.addProperty("version", status.version());
		object.addProperty("state", status.state().name());
		if (status.detail() != null)
		{
			object.addProperty("detail", status.detail());
		}
		if (status.revision() != null)
		{
			object.addProperty("revision", status.revision());
		}
		if (status.gamePid() != null)
		{
			object.addProperty("gamePid", status.gamePid());
		}
		object.addProperty("at", status.at().toString());
		return GSON.toJson(object) + "\n";
	}

	/** Writes the status whole, so a reader never sees half a file. */
	public static void write(Path file, Status status) throws IOException
	{
		Atomically.write(file, toJson(status));
	}

	/** Reads a status file back; a file that isn't one is an {@link IOException} naming what is wrong. */
	public static Status read(Path file) throws IOException
	{
		return fromJson(Files.readString(file, StandardCharsets.UTF_8));
	}

	public static Status fromJson(String json) throws IOException
	{
		JsonElement root;
		try
		{
			root = JsonParser.parseString(json);
		}
		catch (JsonSyntaxException e)
		{
			throw new IOException("the status file isn't JSON: " + e.getMessage());
		}
		if (!root.isJsonObject())
		{
			throw new IOException("the status file isn't a JSON object");
		}
		JsonObject object = root.getAsJsonObject();
		try
		{
			return new Status(
				required(object, "version").getAsString(),
				State.valueOf(required(object, "state").getAsString()),
				object.has("detail") ? object.get("detail").getAsString() : null,
				object.has("revision") ? object.get("revision").getAsInt() : null,
				object.has("gamePid") ? object.get("gamePid").getAsLong() : null,
				Instant.parse(required(object, "at").getAsString()));
		}
		catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException
			| DateTimeParseException e)
		{
			throw new IOException("the status file has a bad value: " + e.getMessage());
		}
	}

	private static JsonElement required(JsonObject object, String name) throws IOException
	{
		JsonElement element = object.get(name);
		if (element == null || element.isJsonNull())
		{
			throw new IOException("the status file has no \"" + name + "\"");
		}
		return element;
	}
}
