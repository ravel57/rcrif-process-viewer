package ru.ravel.rcrifprocessviewer.db

import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import kotlin.math.abs

/**
 * Заглушка вместо БД.
 *
 * Числа детерминированы: для одной и той же пары (номер заявки, активность)
 * всегда возвращается одно и то же значение, поэтому картинка не «прыгает»
 * при перерисовке, но выглядит правдоподобно.
 *
 * Заменяется на [JdbcActivityPassRepository], как только будет известна схема БД.
 */
class StubActivityPassRepository : ActivityPassRepository {

	override val sourceDescription: String = "заглушка (БД не подключена)"

	override fun loadPassCounts(
		requestNumber: String,
		procedureName: String,
		activityReferences: List<String>,
	): Map<String, Int> {
		if (requestNumber.isBlank()) {
			return emptyMap()
		}
		return activityReferences.associateWith { reference ->
			passCount(requestNumber, procedureName, reference)
		}
	}

	override fun loadActivityCalls(
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
	): List<ActivityCall> {
		val count = passCount(requestNumber, procedureName, activity.reference)
		return (1..count).map { index ->
			ActivityCall(
				index = index,
				startedAt = timestamp(requestNumber, activity.reference, index, "start"),
				finishedAt = timestamp(requestNumber, activity.reference, index, "exit"),
				exitName = activity.type.exits.firstOrNull() ?: "Completed",
				documentsOnStart = activity.dataDocuments.map { document ->
					DataDocumentValue(
						name = document.referenceName,
						access = document.access,
						value = "вход #$index: <${document.referenceName}> … заглушка",
						updatedAt = timestamp(requestNumber, document.referenceName, index, "start"),
					)
				},
				documentsOnExit = activity.dataDocuments.map { document ->
					DataDocumentValue(
						name = document.referenceName,
						access = document.access,
						value = if (document.access.equals("In", ignoreCase = true)) {
							"вход #$index: <${document.referenceName}> … заглушка"
						} else {
							"выход #$index: <${document.referenceName}> … заглушка"
						},
						updatedAt = timestamp(requestNumber, document.referenceName, index, "exit"),
					)
				},
			)
		}
	}

	private fun passCount(requestNumber: String, procedureName: String, reference: String): Int {
		if (requestNumber.isBlank()) {
			return 0
		}
		val seed = hash("$requestNumber|$procedureName|$reference")
		return when {
			seed % 10 < 4 -> 0          // ~40% активностей процесс не проходил
			seed % 10 < 8 -> 1
			seed % 10 == 8L -> 2
			else -> 3
		}
	}

	private fun timestamp(requestNumber: String, key: String, index: Int, phase: String): String {
		val seed = hash("$requestNumber|$key|$index|$phase")
		return String.format(
			"2026-%02d-%02d %02d:%02d:%02d",
			(seed % 12) + 1,
			(seed / 12 % 28) + 1,
			(seed / 400 % 24),
			(seed / 9600 % 60),
			(seed / 600000 % 60),
		)
	}

	private fun hash(key: String): Long = abs(key.hashCode().toLong())
}
