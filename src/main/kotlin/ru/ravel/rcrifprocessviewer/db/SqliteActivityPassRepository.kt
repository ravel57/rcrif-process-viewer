package ru.ravel.rcrifprocessviewer.db

import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Постоянная SQLite БД с демонстрационной историей работы по заявке.
 *
 * Важно: repository больше не генерирует случайные process_trace на лету.
 * Иначе модельное имя процедуры (например PR_03_S1Call) засоряет БД новой
 * псевдослучайной трассой и перекрывает подготовленную связную историю 03_S1Call.
 */
@Deprecated(message = "")
class SqliteActivityPassRepository : ActivityPassRepository, AutoCloseable {

	private val databaseFile: File = File("src/main/resources/r-crif-process-demo.sqlite")

	private val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

	override val sourceDescription: String = "SQLite: ${databaseFile.absolutePath}"

	init {
		require(databaseFile.isFile) {
			"Не найдена SQLite БД: ${databaseFile.absolutePath}"
		}
		Class.forName("org.sqlite.JDBC")
	}

	override fun loadPassCounts(
		requestNumber: String,
		procedureName: String,
		activityReferences: List<String>,
	): Map<String, Int> {
		if (requestNumber.isBlank() || activityReferences.isEmpty()) return emptyMap()
		connect().use { connection ->
			val storedProcedureName = resolveProcedureName(connection, requestNumber, procedureName)
				?: return emptyMap()
			val known = activityReferences.toHashSet()
			val result = linkedMapOf<String, Int>()
			connection.prepareStatement(SQL_PASS_COUNTS).use { statement ->
				statement.setString(1, requestNumber)
				statement.setString(2, storedProcedureName)
				statement.executeQuery().use { rs ->
					while (rs.next()) {
						val reference = rs.getString(1) ?: continue
						if (reference in known) result[reference] = rs.getInt(2)
					}
				}
			}
			return result
		}
	}

	override fun loadTraceEvents(
		requestNumber: String,
		procedureName: String,
	): List<ProcessTraceEvent> {
		if (requestNumber.isBlank()) return emptyList()
		connect().use { connection ->
			val storedProcedureName = resolveProcedureName(connection, requestNumber, procedureName)
				?: return emptyList()
			val result = mutableListOf<ProcessTraceEvent>()
			connection.prepareStatement(SQL_TRACE_EVENTS).use { statement ->
				statement.setString(1, requestNumber)
				statement.setString(2, storedProcedureName)
				statement.executeQuery().use { rs ->
					while (rs.next()) {
						result += ProcessTraceEvent(
							activityReference = rs.getString(1) ?: continue,
							startedAt = rs.getString(2),
							finishedAt = rs.getString(3),
							exitName = rs.getString(4),
						)
					}
				}
			}
			return result
		}
	}

	override fun loadCurrentProcessPosition(requestNumber: String): CurrentProcessPosition? {
		if (requestNumber.isBlank()) return null
		connect().use { connection ->
			connection.prepareStatement(SQL_CURRENT_PROCESS_POSITION).use { statement ->
				statement.setString(1, requestNumber)
				statement.executeQuery().use { rs ->
					if (!rs.next()) return null
					return CurrentProcessPosition(
						procedureName = rs.getString(1) ?: return null,
						activityReference = rs.getString(2) ?: return null,
						startedAt = rs.getString(3),
						finishedAt = rs.getString(4),
					)
				}
			}
		}
	}

	override fun loadRequestTrace(requestNumber: String): List<RequestTraceEntry> {
		if (requestNumber.isBlank()) return emptyList()
		connect().use { connection ->
			val result = mutableListOf<RequestTraceEntry>()
			connection.prepareStatement(SQL_REQUEST_TRACE).use { statement ->
				statement.setString(1, requestNumber)
				statement.executeQuery().use { rs ->
					while (rs.next()) {
						result += RequestTraceEntry(
							procedureName = rs.getString(1) ?: continue,
							activityReference = rs.getString(2) ?: continue,
							startedAt = rs.getString(3),
							finishedAt = rs.getString(4),
							exitName = rs.getString(5),
						)
					}
				}
			}
			return result
		}
	}

	override fun loadActivityCalls(
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
	): List<ActivityCall> {
		if (requestNumber.isBlank()) return emptyList()
		connect().use { connection ->
			val storedProcedureName = resolveProcedureName(connection, requestNumber, procedureName)
				?: return emptyList()
			val rows = mutableListOf<TraceRow>()
			connection.prepareStatement(SQL_ACTIVITY_CALLS).use { statement ->
				statement.setString(1, requestNumber)
				statement.setString(2, storedProcedureName)
				statement.setString(3, activity.reference)
				statement.executeQuery().use { rs ->
					while (rs.next()) {
						rows += TraceRow(
							index = rs.getInt(1),
							startedAt = rs.getString(2),
							finishedAt = rs.getString(3),
							exitName = rs.getString(4),
						)
					}
				}
			}

			val fallbackExit = activity.availableExits.firstOrNull()
				?: activity.type.exits.firstOrNull()
				?: "Completed"
			return rows.map { row ->
				ActivityCall(
					index = row.index,
					startedAt = row.startedAt,
					finishedAt = row.finishedAt,
					exitName = row.exitName ?: fallbackExit,
					documentsOnStart = loadDocuments(
						connection, requestNumber, storedProcedureName, activity, row.index, PHASE_START,
					),
					documentsOnExit = loadDocuments(
						connection, requestNumber, storedProcedureName, activity, row.index, PHASE_EXIT,
					),
				)
			}
		}
	}

	private fun connect(): Connection = DriverManager.getConnection(jdbcUrl)

	/**
	 * Имя вкладки/папки процедуры содержит префикс PR_, а в подготовленной
	 * демонстрационной БД процедуры хранятся без него: PR_03_S1Call -> 03_S1Call.
	 * Сначала намеренно предпочитаем имя без PR_: это также игнорирует старые
	 * псевдослучайные строки PR_* если БД уже открывалась предыдущей реализацией.
	 */
	private fun resolveProcedureName(
		connection: Connection,
		requestNumber: String,
		requestedName: String,
	): String? {
		val candidates = buildList {
			if (requestedName.startsWith("PR_", ignoreCase = true)) {
				add(requestedName.substring(3))
			}
			add(requestedName)
		}.distinct()

		connection.prepareStatement(SQL_RESOLVE_PROCEDURE).use { statement ->
			for (candidate in candidates) {
				statement.setString(1, requestNumber)
				statement.setString(2, candidate)
				statement.executeQuery().use { rs ->
					if (rs.next()) return rs.getString(1)
				}
			}
		}
		return null
	}

	private fun createSchema(connection: Connection) {
		connection.createStatement().use { statement ->
			statement.execute("PRAGMA journal_mode=WAL")
			statement.execute("PRAGMA foreign_keys=ON")
			statement.executeUpdate(
				"""
				create table if not exists process_trace (
					request_number text not null,
					procedure_name text not null,
					activity_reference text not null,
					call_index integer not null,
					started_at text,
					finished_at text,
					exit_name text,
					primary key (request_number, procedure_name, activity_reference, call_index)
				)
				""".trimIndent(),
			)
			statement.executeUpdate(
				"""
				create table if not exists process_data_document (
					request_number text not null,
					procedure_name text not null,
					activity_reference text not null,
					call_index integer not null,
					phase text not null,
					document_name text not null,
					document_value text,
					updated_at text,
					primary key (
						request_number, procedure_name, activity_reference,
						call_index, phase, document_name
					)
				)
				""".trimIndent(),
			)
		}
	}

	private fun seedTrace(
		connection: Connection,
		requestNumber: String,
		procedureName: String,
		activityReferences: List<String>,
	) {
		if (activityReferences.isEmpty()) return

		val counts = activityReferences.associateWith { reference ->
			passCount(requestNumber, procedureName, reference)
		}.toMutableMap()
		// В демонстрационной БД у непустой процедуры всегда есть хотя бы один факт работы.
		if (counts.values.none { it > 0 }) {
			activityReferences.firstOrNull()?.let { counts[it] = 1 }
		}

		connection.prepareStatement(
			"""
			insert or ignore into process_trace(
				request_number, procedure_name, activity_reference, call_index,
				started_at, finished_at, exit_name
			) values (?, ?, ?, ?, ?, ?, null)
			""".trimIndent(),
		).use { statement ->
			for ((reference, count) in counts) {
				for (index in 1..count) {
					statement.setString(1, requestNumber)
					statement.setString(2, procedureName)
					statement.setString(3, reference)
					statement.setInt(4, index)
					statement.setString(5, timestamp(requestNumber, procedureName, reference, index, 0))
					statement.setString(6, timestamp(requestNumber, procedureName, reference, index, 7))
					statement.addBatch()
				}
			}
			statement.executeBatch()
		}
	}

	private fun seedDocuments(
		connection: Connection,
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
		callIndex: Int,
	) {
		if (activity.dataDocuments.isEmpty()) return
		connection.prepareStatement(
			"""
			insert or ignore into process_data_document(
				request_number, procedure_name, activity_reference, call_index,
				phase, document_name, document_value, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
		).use { statement ->
			for (document in activity.dataDocuments) {
				for (phase in listOf(PHASE_START, PHASE_EXIT)) {
					statement.setString(1, requestNumber)
					statement.setString(2, procedureName)
					statement.setString(3, activity.reference)
					statement.setInt(4, callIndex)
					statement.setString(5, phase)
					statement.setString(6, document.referenceName)
					statement.setString(
						7,
						"${if (phase == PHASE_START) "вход" else "выход"} #$callIndex: " +
							"${document.referenceName} для заявки $requestNumber",
					)
					statement.setString(
						8,
						timestamp(
							requestNumber,
							procedureName,
							"${activity.reference}|${document.referenceName}|$phase",
							callIndex,
							if (phase == PHASE_START) 1 else 6,
						),
					)
					statement.addBatch()
				}
			}
			statement.executeBatch()
		}
	}

	private fun loadDocuments(
		connection: Connection,
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
		callIndex: Int,
		phase: String,
	): List<DataDocumentValue> {
		val declared = activity.dataDocuments.associate { it.referenceName to it.access }
		val loaded = linkedMapOf<String, DataDocumentValue>()
		connection.prepareStatement(SQL_CALL_DOCUMENTS).use { statement ->
			statement.setString(1, requestNumber)
			statement.setString(2, procedureName)
			statement.setString(3, activity.reference)
			statement.setInt(4, callIndex)
			statement.setString(5, phase)
			statement.executeQuery().use { rs ->
				while (rs.next()) {
					val name = rs.getString(1) ?: continue
					loaded[name] = DataDocumentValue(
						name = name,
						access = declared[name].orEmpty(),
						value = rs.getString(2),
						updatedAt = rs.getString(3),
					)
				}
			}
		}
		return declared.map { (name, access) ->
			loaded[name] ?: DataDocumentValue(name, access, null, null)
		}
	}

	private fun passCount(requestNumber: String, procedureName: String, reference: String): Int {
		val seed = positiveHash("$requestNumber|$procedureName|$reference") % 10
		return when {
			seed < 4 -> 0
			seed < 8 -> 1
			seed == 8L -> 2
			else -> 3
		}
	}

	private fun timestamp(
		requestNumber: String,
		procedureName: String,
		key: String,
		index: Int,
		minuteOffset: Long,
	): String {
		val seed = positiveHash("$requestNumber|$procedureName|$key|$index")
		val base = LocalDateTime.of(2026, 1, 1, 9, 0)
			.plusDays(seed % 240)
			.plusMinutes((seed / 240) % (12 * 60))
			.plusMinutes(minuteOffset)
		return base.format(TIMESTAMP_FORMAT)
	}

	private fun positiveHash(value: String): Long = abs(value.hashCode().toLong())

	override fun close() = Unit

	private data class TraceRow(
		val index: Int,
		val startedAt: String?,
		val finishedAt: String?,
		val exitName: String?,
	)

	private companion object {
		const val PHASE_START = "START"
		const val PHASE_EXIT = "EXIT"
		val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

		const val SQL_RESOLVE_PROCEDURE = """
			select procedure_name
			from process_trace
			where request_number = ? and procedure_name = ? collate nocase
			limit 1
		"""

		const val SQL_PASS_COUNTS = """
			select activity_reference, count(*)
			from process_trace
			where request_number = ? and procedure_name = ?
			group by activity_reference
		"""

		const val SQL_CURRENT_PROCESS_POSITION = """
			select procedure_name, activity_reference, started_at, finished_at
			from process_trace
			where request_number = ?
			order by coalesce(finished_at, started_at) desc, rowid desc
			limit 1
		"""

		const val SQL_TRACE_EVENTS = """
			select activity_reference, started_at, finished_at, exit_name
			from process_trace
			where request_number = ? and procedure_name = ?
			order by coalesce(finished_at, started_at), call_index
		"""

		const val SQL_REQUEST_TRACE = """
			select procedure_name, activity_reference, started_at, finished_at, exit_name
			from process_trace
			where request_number = ?
			order by coalesce(started_at, finished_at), rowid
		"""

		const val SQL_ACTIVITY_CALLS = """
			select call_index, started_at, finished_at, exit_name
			from process_trace
			where request_number = ? and procedure_name = ? and activity_reference = ?
			order by call_index
		"""

		const val SQL_CALL_DOCUMENTS = """
			select document_name, document_value, updated_at
			from process_data_document
			where request_number = ? and procedure_name = ? and activity_reference = ?
				and call_index = ? and phase = ?
			order by document_name
		"""
	}
}
