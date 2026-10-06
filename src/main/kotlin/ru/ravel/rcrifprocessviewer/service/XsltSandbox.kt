package ru.ravel.rcrifprocessviewer.service

import ru.ravel.rcrifprocessviewer.config.AppConfig
import ru.ravel.rcrifprocessviewer.db.ActivityCall
import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import java.io.File

/** Какие данные вызова подставляются в XSLT-sandbox как входной XML. */
enum class XsltSandboxInput {
	/** Дата-документы на старте вызова, обёрнутые в <Data>. */
	DOCUMENTS_ON_START,

	/** Ответ коннектора, как он записан в трейсе. */
	CONNECTOR_OUTPUT,
}

/** Один вариант "что открыть": файл в папке активности + откуда брать входные данные. */
data class XsltSandboxTarget(
	val fileName: String,
	val input: XsltSandboxInput,
) {
	fun inputData(call: ActivityCall): String? = when (input) {
		XsltSandboxInput.DOCUMENTS_ON_START -> XsltSandbox.wrapAsData(call)
		XsltSandboxInput.CONNECTOR_OUTPUT -> call.connectorOutput?.takeIf { it.isNotBlank() }
	}
}

object XsltSandbox {

	fun targetsFor(type: ActivityType): List<XsltSandboxTarget> = when (type) {
		ActivityType.DATA_MAPPING -> listOf(
			XsltSandboxTarget("Mapping.xslt", XsltSandboxInput.DOCUMENTS_ON_START),
		)
		ActivityType.DATA_SOURCE -> listOf(
			XsltSandboxTarget("MappingInput.xslt", XsltSandboxInput.DOCUMENTS_ON_START),
			XsltSandboxTarget("MappingOutput.xslt", XsltSandboxInput.CONNECTOR_OUTPUT),
		)
		ActivityType.BIZ_RULE -> listOf(
			XsltSandboxTarget("Properties.xml", XsltSandboxInput.DOCUMENTS_ON_START),
		)
		else -> emptyList()
	}

	fun wrapAsData(call: ActivityCall): String =
		call.documentsOnStart
			.mapNotNull { it.value?.trim()?.takeIf(String::isNotEmpty) }
			.joinToString("\n", prefix = "<Data>\n", postfix = "\n</Data>") { value ->
				value.removePrefix("<Data>").removeSuffix("</Data>")
			}

	/** Путь к XSLTSandbox из config.properties, если он указан и файл существует. */
	fun configuredExecutable(): File? =
		AppConfig.loadXsltSandboxPath()?.let(::File)?.takeIf(File::isFile)

	/**
	 * Пишет входной XML во временный файл и запускает sandbox с XSLT активности.
	 * Файл с данными не удаляется: sandbox читает его уже после старта и может перечитывать.
	 */
	fun launch(
		executable: File,
		activity: ProcessActivity,
		call: ActivityCall,
		target: XsltSandboxTarget,
	) {
		val xslt = File(activity.dir ?: error("Для ${activity.reference} не найдена папка активности"), target.fileName)
		require(xslt.isFile) { "Нет файла ${xslt.absolutePath}" }
		val data = target.inputData(call)
			?: error("В вызове ${call.index} нет данных для ${target.fileName}")

		val stem = target.fileName.substringBeforeLast('.')
		val dataFile = File(AppConfig.dataDocsDirectory(), "${activity.reference}_call${call.index}_$stem.xml")
		dataFile.writeText(data)

		ProcessBuilder(
			executable.absolutePath,
			"--input-xslt-path", xslt.absolutePath,
			"--input-data-path", dataFile.absolutePath,
		)
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.start()
	}
}
