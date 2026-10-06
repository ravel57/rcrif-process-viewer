package ru.ravel.rcrifprocessviewer.service

import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Версия процесса, которую можно открыть в viewer'е.
 *
 * [gitRef] == null означает текущее рабочее дерево: несохранённые/незакоммиченные
 * локальные изменения видны ровно такими, какими они лежат на диске.
 */
data class ProcessVersionOption(
	val title: String,
	val gitRef: String?,
) {
	val isLocal: Boolean get() = gitRef == null

	override fun toString(): String = title

	companion object {
		val LOCAL = ProcessVersionOption("Локальная версия", null)

		fun branch(name: String): ProcessVersionOption = ProcessVersionOption(name, name)
	}
}

/**
 * Доступ к локальному .git выбранного процесса без checkout рабочей директории.
 *
 * Список веток читается только из локальных refs/heads. Для просмотра конкретной
 * ветки делается read-only snapshot через `git archive`, поэтому переключение
 * версии в viewer'е никогда не меняет HEAD и не трогает пользовательские файлы.
 */
class GitProcessRepository private constructor(
	private val repositoryRoot: File,
	private val processRoot: File,
) : AutoCloseable {

	private val processRelativePath: String = repositoryRoot.toPath()
		.relativize(processRoot.toPath())
		.toString()
		.replace(File.separatorChar, '/')
		.trim('/')

	private val snapshots = linkedMapOf<String, File>()

	fun localBranches(): List<String> = runGit(
		"for-each-ref",
		"--format=%(refname:short)",
		"refs/heads",
	)
		.lineSequence()
		.map(String::trim)
		.filter(String::isNotEmpty)
		.distinct()
		.sortedWith(String.CASE_INSENSITIVE_ORDER)
		.toList()

	/**
	 * Возвращает корень выбранного процесса в снимке ветки.
	 * Один и тот же commit повторно не распаковывается.
	 */
	@Synchronized
	fun snapshot(branch: String): File {
		val commit = runGit("rev-parse", "--verify", "$branch^{commit}").trim()
		if (commit.isEmpty()) error("Не удалось определить commit ветки $branch")

		val cacheKey = "$commit|$processRelativePath"
		snapshots[cacheKey]?.takeIf(File::isDirectory)?.let { return it }

		val snapshotRoot = Files.createTempDirectory("r-crif-process-${commit.take(8)}-").toFile()
		val archive = File.createTempFile("r-crif-process-", ".zip")
		try {
			val args = mutableListOf(
				"archive",
				"--format=zip",
				"--output=${archive.absolutePath}",
				commit,
			)
			if (processRelativePath.isNotEmpty()) {
				args += "--"
				args += processRelativePath
			}
			runGit(*args.toTypedArray())
			unzip(archive, snapshotRoot)
		} catch (error: Throwable) {
			snapshotRoot.deleteRecursively()
			throw error
		} finally {
			archive.delete()
		}

		val branchProcessRoot = if (processRelativePath.isEmpty()) {
			snapshotRoot
		} else {
			File(snapshotRoot, processRelativePath.replace('/', File.separatorChar))
		}
		if (!branchProcessRoot.isDirectory) {
			snapshotRoot.deleteRecursively()
			error("В ветке $branch отсутствует папка процесса $processRelativePath")
		}

		snapshots[cacheKey] = branchProcessRoot
		return branchProcessRoot
	}

	private fun runGit(vararg args: String): String {
		val command = buildList {
			add("git")
			add("-C")
			add(repositoryRoot.absolutePath)
			addAll(args)
		}
		val process = ProcessBuilder(command)
			.redirectErrorStream(true)
			.start()
		val output = process.inputStream.bufferedReader().use { it.readText() }
		val exitCode = process.waitFor()
		if (exitCode != 0) {
			error(
				"git ${args.joinToString(" ")} завершился с кодом $exitCode" +
					output.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty(),
			)
		}
		return output
	}

	private fun unzip(archive: File, targetRoot: File) {
		val canonicalRoot = targetRoot.canonicalFile
		ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				val target = File(targetRoot, entry.name).canonicalFile
				val rootPrefix = canonicalRoot.path + File.separator
				if (target != canonicalRoot && !target.path.startsWith(rootPrefix)) {
					error("Некорректный путь в git archive: ${entry.name}")
				}
				if (entry.isDirectory) {
					target.mkdirs()
				} else {
					target.parentFile?.mkdirs()
					target.outputStream().use { output -> zip.copyTo(output) }
				}
				zip.closeEntry()
			}
		}
	}

	override fun close() {
		val snapshotRoots = snapshots.values
			.map { branchRoot ->
				var current = branchRoot
				repeat(processRelativePath.split('/').count { it.isNotEmpty() }) {
					current = current.parentFile ?: return@repeat
				}
				current
			}
			.distinctBy { it.absolutePath }
		snapshotRoots.forEach(File::deleteRecursively)
		snapshots.clear()
	}

	companion object {
		/** Ищет ближайший .git вверх от выбранной папки процесса. */
		fun find(processRoot: File): GitProcessRepository? {
			val canonicalProcessRoot = runCatching { processRoot.canonicalFile }.getOrNull() ?: return null
			var current: File? = canonicalProcessRoot
			while (current != null) {
				if (File(current, ".git").exists()) {
					return GitProcessRepository(current, canonicalProcessRoot)
				}
				current = current.parentFile
			}
			return null
		}
	}
}
