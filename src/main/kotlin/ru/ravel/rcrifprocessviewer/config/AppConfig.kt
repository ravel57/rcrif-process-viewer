package ru.ravel.rcrifprocessviewer.config

import java.io.File
import java.util.Properties

/**
 * Запоминает выбранную папку процесса, её версию (git-ветку) и последний номер заявки.
 */
object AppConfig {

	private const val FILE_NAME = "config.properties"
	private const val KEY_FOLDER = "selectedDirectory"
	private const val KEY_REQUEST = "requestNumber"
	private const val KEY_PROCESS_VERSION = "processVersion"
	private const val KEY_IDEA_PATH = "ideaPath"
	private const val KEY_XSLT_SANDBOX_PATH = "xsltSandboxPath"
	private const val KEY_TRACE_DB_URL = "traceDatabaseUrl"
	private const val KEY_TRACE_DB_USER = "traceDatabaseUser"
	private const val KEY_TRACE_DB_PASSWORD = "traceDatabasePassword"
	private const val DEFAULT_TRACE_DB_URL = "jdbc:postgresql://localhost:5432/ru_flow"

	fun loadFolder(): String? = read(KEY_FOLDER)

	fun loadRequestNumber(): String? = read(KEY_REQUEST)

	fun loadIdeaPath(): String? = read(KEY_IDEA_PATH)

	fun loadXsltSandboxPath(): String? = read(KEY_XSLT_SANDBOX_PATH)

	fun saveXsltSandboxPath(path: String?) {
		val properties = readAll()
		if (path.isNullOrBlank()) {
			properties.remove(KEY_XSLT_SANDBOX_PATH)
		} else {
			properties[KEY_XSLT_SANDBOX_PATH] = path.trim()
		}
		store(properties)
	}

	/** Папка для временных XML с данными, которые передаются во внешние инструменты. */
	fun dataDocsDirectory(): File = File(configDirectory(), "data-docs").apply { mkdirs() }

	/** Имя git-ветки выбранной версии; null — локальная версия. */
	fun loadProcessVersion(): String? = read(KEY_PROCESS_VERSION)

	fun saveProcessVersion(branch: String?) {
		val properties = readAll()
		if (branch.isNullOrBlank()) {
			properties.remove(KEY_PROCESS_VERSION)
		} else {
			properties[KEY_PROCESS_VERSION] = branch
		}
		store(properties)
	}

	fun loadTraceDatabaseSettings(): TraceDatabaseSettings = TraceDatabaseSettings(
		url = environment("RUFLOW_TRACE_DB_URL", "DB_URL")
			?: read(KEY_TRACE_DB_URL)
			?: DEFAULT_TRACE_DB_URL,
		user = environment("RUFLOW_TRACE_DB_USER", "DB_USER")
			?: read(KEY_TRACE_DB_USER)
			?: "postgres",
		password = environment("RUFLOW_TRACE_DB_PASSWORD", "DB_PASSWORD")
			?: read(KEY_TRACE_DB_PASSWORD)
			?: "postgres",
	)

	fun saveIdeaPath(path: String?) {
		val properties = readAll()
		if (path.isNullOrBlank()) {
			properties.remove(KEY_IDEA_PATH)
		} else {
			properties[KEY_IDEA_PATH] = path.trim()
		}
		store(properties)
	}

	fun saveTraceDatabaseSettings(settings: TraceDatabaseSettings) {
		val properties = readAll()
		properties[KEY_TRACE_DB_URL] = settings.url.trim()
		properties[KEY_TRACE_DB_USER] = settings.user.trim()
		properties[KEY_TRACE_DB_PASSWORD] = settings.password
		store(properties)
	}

	fun save(folder: String?, requestNumber: String?) {
		val properties = readAll()
		folder?.let { properties[KEY_FOLDER] = it }
		requestNumber?.let { properties[KEY_REQUEST] = it }
		store(properties)
	}

	private fun store(properties: Properties) {
		runCatching {
			configFile().outputStream().use { properties.store(it, null) }
		}
	}

	private fun read(key: String): String? = readAll().getProperty(key)?.takeIf { it.isNotBlank() }

	private fun environment(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
		System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
	}

	private fun readAll(): Properties {
		val properties = Properties()
		val file = configFile()
		if (file.exists()) {
			runCatching { file.inputStream().use { properties.load(it) } }
		}
		return properties
	}

	private fun configFile(): File = File(configDirectory(), FILE_NAME)

	private fun configDirectory(): File {
		val os = System.getProperty("os.name").lowercase()
		val configDir = when {
			os.contains("win") -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "rcrif-process-viewer")
			os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support/rcrif-process-viewer")
			else -> File(System.getProperty("user.home"), ".config/rcrif-process-viewer")
		}
		if (!configDir.exists()) {
			configDir.mkdirs()
		}
		return configDir
	}
}

data class TraceDatabaseSettings(
	val url: String,
	val user: String,
	val password: String,
)
