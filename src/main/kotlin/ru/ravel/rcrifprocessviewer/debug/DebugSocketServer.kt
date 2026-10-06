package ru.ravel.rcrifprocessviewer.debug

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Принимает шаги отладчика xslt-sandbox: по одной JSON-строке на шаг, соединение держит
 * сам sandbox. Слушает только loopback, порт выбирается случайно из динамического диапазона
 * и отдаётся sandbox аргументом запуска `--debug-port`. [onStep] вызывается из потока
 * соединения — переключаться в поток JavaFX должен вызывающий.
 */
class DebugSocketServer(private val onStep: (DebugStep) -> Unit) : AutoCloseable {

	private val mapper = ObjectMapper()
	private var server: ServerSocket? = null

	/** Поднимает сервер при первом вызове и возвращает его порт; дальше порт тот же. */
	@Synchronized
	fun ensureStarted(): Int {
		server?.takeUnless { it.isClosed }?.let { return it.localPort }
		val bound = bindRandomPort()
		server = bound
		thread(name = "debug-accept", isDaemon = true) { acceptLoop(bound) }
		return bound.localPort
	}

	private fun bindRandomPort(): ServerSocket {
		val loopback = InetAddress.getByName(LOOPBACK)
		repeat(BIND_ATTEMPTS) {
			try {
				return ServerSocket(Random.nextInt(FIRST_PORT, LAST_PORT + 1), BACKLOG, loopback)
			} catch (_: IOException) {
				// порт занят — пробуем другой
			}
		}
		return ServerSocket(0, BACKLOG, loopback)
	}

	private fun acceptLoop(bound: ServerSocket) {
		while (!bound.isClosed) {
			val client = try {
				bound.accept()
			} catch (_: IOException) {
				return
			}
			thread(name = "debug-client", isDaemon = true) { readClient(client) }
		}
	}

	private fun readClient(client: Socket) {
		client.use {
			val reader = InputStreamReader(it.getInputStream(), Charsets.UTF_8)
			while (true) {
				val line = readLine(reader) ?: return
				parse(line)?.let(onStep)
			}
		}
	}

	/** Строка до '\n'; null — соединение закрыто или строка длиннее [MAX_LINE_CHARS]. */
	private fun readLine(reader: Reader): String? {
		val line = StringBuilder()
		while (true) {
			val code = try {
				reader.read()
			} catch (_: IOException) {
				return null
			}
			if (code < 0) return null
			if (code == '\n'.code) return line.toString()
			if (line.length >= MAX_LINE_CHARS) return null
			line.append(code.toChar())
		}
	}

	internal fun parse(line: String): DebugStep? {
		val node = runCatching { mapper.readTree(line) }.getOrNull() ?: return null
		if (node.path("type").asText() != "step") return null
		val procedure = node.text("procedure")?.takeIf(String::isNotBlank) ?: return null
		val activity = node.text("activity")?.takeIf(String::isNotBlank) ?: return null
		return DebugStep(
			procedure = procedure,
			activity = activity,
			mode = node.text("mode").orEmpty(),
			exit = node.text("exit"),
			next = node.text("next"),
			docsIn = node.text("docsIn"),
			docsOut = node.text("docsOut"),
		)
	}

	private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

	@Synchronized
	override fun close() {
		runCatching { server?.close() }
		server = null
	}

	companion object {
		const val FIRST_PORT = 49152
		const val LAST_PORT = 65535
		private const val LOOPBACK = "127.0.0.1"
		private const val BACKLOG = 16
		private const val BIND_ATTEMPTS = 50
		private const val MAX_LINE_CHARS = 64_000_000
	}
}
