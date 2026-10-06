package ru.ravel.rcrifprocessviewer.service

import ru.ravel.rcrifprocessviewer.config.AppConfig
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Открывает значение дата-документа как отдельный файл (или сравнение двух значений) в IntelliJ IDEA.
 *
 * Одиночное значение открывается ключом `-e`/`--edit` (LightEdit, без полноценного проекта).
 * Сравнение — встроенной CLI-командой IDEA `diff <файл1> <файл2>`. Оба файла лежат в системной
 * temp-директории, а не внутри выбранного процесса.
 */
object IdeaLightEdit {

	private val tempDirectory: Path = Path.of(
		System.getProperty("java.io.tmpdir"),
		"r-crif-process-viewer",
		"lightedit",
	)

	fun open(documentName: String, content: String): Path {
		val file = writeTempFile(documentName, content, suffix = null)
		startIdea(listOf("-e", file.toAbsolutePath().toString()))
		return file
	}

	/** [before] и [after] — то, что было на старте вызова, и то, что стало на выходе. */
	fun openDiff(documentName: String, before: String, after: String): Pair<Path, Path> {
		val beforeFile = writeTempFile(documentName, before, suffix = "before")
		val afterFile = writeTempFile(documentName, after, suffix = "after")
		startIdea(listOf("diff", beforeFile.toAbsolutePath().toString(), afterFile.toAbsolutePath().toString()))
		return beforeFile to afterFile
	}

	private fun writeTempFile(documentName: String, content: String, suffix: String?): Path {
		Files.createDirectories(tempDirectory)

		val extension = detectExtension(documentName, content)
		val baseName = sanitizeBaseName(documentName)
		val contentKey = sha256("$documentName\u0000$content").take(12)
		val suffixPart = if (suffix == null) "" else "_$suffix"
		val file = tempDirectory.resolve("${baseName}_$contentKey$suffixPart$extension")

		Files.writeString(
			file,
			content,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE,
		)

		return file
	}

	private fun startIdea(ideaArgs: List<String>) {
		val os = System.getProperty("os.name").lowercase(Locale.ROOT)
		val configuredDirectory = AppConfig.loadIdeaPath()
			?.trim()
			?.takeIf(String::isNotEmpty)
			?.let(::File)

		val commands = buildList {
			if (configuredDirectory != null) {
				configuredIdeaLaunchers(configuredDirectory, os).forEach { launcher ->
					add(listOf(launcher.absolutePath) + ideaArgs)
				}
			}

			when {
				os.contains("win") -> {
					add(listOf("idea64.exe") + ideaArgs)
					add(listOf("idea.exe") + ideaArgs)
					add(listOf("idea.bat") + ideaArgs)
				}

				os.contains("mac") -> {
					add(listOf("idea") + ideaArgs)
					// IDEA is normally already running, and "open -a" only delivers --args to a
					// freshly *launched* process — macOS just re-activates an already-running app
					// without forwarding them at all, so the CLI arguments silently go nowhere
					// (confirmed: no CommandLineProcessor entry at all in idea.log for that path).
					// The bundled binary talks straight to the running instance and works either way,
					// so try it directly in the common install locations before falling back.
					defaultMacInstallDirectories().forEach { directory ->
						configuredIdeaLaunchers(directory, os).forEach { launcher ->
							add(listOf(launcher.absolutePath) + ideaArgs)
						}
					}
					add(listOf("open", "-a", "IntelliJ IDEA", "--args") + ideaArgs)
				}

				else -> {
					add(listOf("idea") + ideaArgs)
					add(listOf("idea.sh") + ideaArgs)
				}
			}
		}

		var lastError: IOException? = null
		for (command in commands) {
			try {
				ProcessBuilder(command)
					.redirectErrorStream(true)
					.start()
				return
			} catch (error: IOException) {
				lastError = error
			}
		}

		throw IOException(
			"Не найден launcher IntelliJ IDEA. Добавьте команду idea в PATH " +
					"(на Windows также поддерживаются idea64.exe/idea.exe).",
			lastError,
		)
	}

	/** Стандартные места установки IDEA на macOS — пробуем их без ручной настройки пути в Settings. */
	private fun defaultMacInstallDirectories(): List<File> {
		val home = System.getProperty("user.home")
		val editions = listOf("IntelliJ IDEA.app", "IntelliJ IDEA CE.app", "IntelliJ IDEA Ultimate.app")
		return listOf("/Applications", "$home/Applications").flatMap { base ->
			editions.map { edition -> File(base, edition) }
		}
	}

	private fun configuredIdeaLaunchers(directory: File, os: String): List<File> {
		if (!directory.isDirectory) return emptyList()

		val relativePaths = when {
			os.contains("win") -> listOf(
				"bin/idea64.exe",
				"bin/idea.exe",
				"bin/idea.bat",
				"idea64.exe",
				"idea.exe",
				"idea.bat",
			)

			os.contains("mac") -> listOf(
				"Contents/MacOS/idea",
				"MacOS/idea",
				"bin/idea",
				"idea",
			)

			else -> listOf(
				"bin/idea.sh",
				"bin/idea",
				"idea.sh",
				"idea",
			)
		}

		return relativePaths
			.map { relative -> File(directory, relative.replace('/', File.separatorChar)) }
			.filter(File::isFile)
	}

	private fun sanitizeBaseName(documentName: String): String {
		val withoutExtension = documentName
			.substringBeforeLast('.', documentName)
			.ifBlank { "document" }
		val safe = withoutExtension
			.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
			.trim('_', '.', '-')
			.take(80)
		return safe.ifBlank { "document" }
	}

	private fun detectExtension(documentName: String, content: String): String {
		val nameExtension = documentName
			.substringAfterLast('.', "")
			.takeIf { it.length in 1..12 && it.all { char -> char.isLetterOrDigit() } }
		if (nameExtension != null) return ".${nameExtension.lowercase(Locale.ROOT)}"

		val trimmed = content.trimStart()
		return when {
			trimmed.startsWith("<?xml") || trimmed.startsWith("<") -> ".xml"
			trimmed.startsWith("{") || trimmed.startsWith("[") -> ".json"
			else -> ".txt"
		}
	}

	private fun sha256(value: String): String {
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(value.toByteArray(StandardCharsets.UTF_8))

		return digest.joinToString(separator = "") { byte ->
			"%02x".format(byte.toInt() and 0xff)
		}
	}

}