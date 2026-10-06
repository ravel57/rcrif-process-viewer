package ru.ravel.rcrifprocessviewer.db

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Read-only adapter for RU Flow's PostgreSQL `flow_trace` table.
 *
 * The engine writes one `before` snapshot, optional connector payload events and one terminal
 * `after`/`failed` event for an activity execution. The viewer exposes that group as one
 * [ActivityCall]. `activity_sequence` alone identifies that group: RU Flow increments it once per
 * activity for the whole lifetime of the instance, so it's already unique per (instance, process,
 * activity) occurrence — `attempt_id` must NOT be added to that join. A FORM/WAIT step's `before`
 * is written by the claim that first reaches it, but its `after` is written later by a *different*
 * claim (a new attempt_id) once the user actually submits the form or the wait is signalled —
 * joining on attempt_id as well made every such step look permanently "still running" even long
 * after it had genuinely completed.
 */
class JdbcActivityPassRepository(
	private val jdbcUrl: String,
	private val user: String? = null,
	private val password: String? = null,
) : ActivityPassRepository {

	private val json = ObjectMapper()

	override val sourceDescription: String = "RU Flow PostgreSQL: $jdbcUrl"

	init {
		require(jdbcUrl.startsWith("jdbc:postgresql:")) {
			"Для трейсов RU Flow ожидается jdbc:postgresql URL"
		}
		Class.forName("org.postgresql.Driver")
	}

	override fun loadPassCounts(
		requestNumber: String,
		procedureName: String,
		activityReferences: List<String>,
	): Map<String, Int> {
		if (requestNumber.isBlank() || activityReferences.isEmpty()) return emptyMap()
		return connect().use { connection ->
			val processId = resolveProcessId(connection, requestNumber, procedureName) ?: return emptyMap()
			val result = linkedMapOf<String, Int>()
			connection.prepareStatement(SQL_PASS_COUNTS).use { statement ->
				statement.setString(1, requestNumber)
				statement.setString(2, processId)
				statement.executeQuery().use { rows ->
					while (rows.next()) {
						val storedReference = rows.getString(1) ?: continue
						result[storedReference] = rows.getInt(2)
					}
				}
			}
			result
		}
	}

	override fun loadTraceEvents(
		requestNumber: String,
		procedureName: String,
	): List<ProcessTraceEvent> {
		if (requestNumber.isBlank()) return emptyList()
		return connect().use { connection ->
			val processId = resolveProcessId(connection, requestNumber, procedureName) ?: return emptyList()
			val result = mutableListOf<ProcessTraceEvent>()
			connection.prepareStatement(SQL_TRACE_EVENTS).use { statement ->
				statement.setString(1, requestNumber)
				statement.setString(2, processId)
				statement.executeQuery().use { rows ->
					while (rows.next()) {
						result += ProcessTraceEvent(
							activityReference = rows.getString("activity_id") ?: continue,
							startedAt = instant(rows, "started_at"),
							finishedAt = instant(rows, "finished_at"),
							exitName = rows.getString("exit_name"),
						)
					}
				}
			}
			result
		}
	}

	override fun loadCurrentProcessPosition(requestNumber: String): CurrentProcessPosition? {
		if (requestNumber.isBlank()) return null
		return connect().use { connection ->
			connection.prepareStatement(SQL_CURRENT_PROCESS_POSITION).use { statement ->
				statement.setString(1, requestNumber)
				statement.executeQuery().use { rows ->
					if (!rows.next()) return null
					CurrentProcessPosition(
						procedureName = rows.getString("process_id") ?: return null,
						activityReference = rows.getString("activity_id") ?: return null,
						startedAt = instant(rows, "started_at"),
						finishedAt = instant(rows, "finished_at"),
					)
				}
			}
		}
	}

	override fun loadRequestTrace(requestNumber: String): List<RequestTraceEntry> {
		if (requestNumber.isBlank()) return emptyList()
		return connect().use { connection ->
			val result = mutableListOf<RequestTraceEntry>()
			connection.prepareStatement(SQL_REQUEST_TRACE).use { statement ->
				statement.setString(1, requestNumber)
				statement.setInt(2, REQUEST_TRACE_LIMIT)
				statement.executeQuery().use { rows ->
					while (rows.next()) {
						result += RequestTraceEntry(
							procedureName = rows.getString("process_id") ?: continue,
							activityReference = rows.getString("activity_id") ?: continue,
							startedAt = instant(rows, "started_at"),
							finishedAt = instant(rows, "finished_at"),
							exitName = rows.getString("exit_name"),
							failed = rows.getString("terminal_phase") == "failed",
						)
					}
				}
			}
			// Запрос берёт самые новые строки, а таблице нужен хронологический порядок.
			result.asReversed()
		}
	}

	override fun loadActivityCalls(
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
	): List<ActivityCall> {
		if (requestNumber.isBlank()) return emptyList()
		return connect().use { connection ->
			val processId = resolveProcessId(connection, requestNumber, procedureName) ?: return emptyList()
			val result = mutableListOf<ActivityCall>()
			connection.prepareStatement(SQL_ACTIVITY_CALLS).use { statement ->
				statement.setString(1, requestNumber)
				statement.setString(2, processId)
				statement.setString(3, activity.traceReference ?: activity.reference)
				statement.executeQuery().use { rows ->
					var callIndex = 0
					while (rows.next()) {
						callIndex++
						val startedAt = instant(rows, "started_at")
						val finishedAt = instant(rows, "finished_at")
						val terminalPhase = rows.getString("terminal_phase")
						result += ActivityCall(
							index = callIndex,
							startedAt = startedAt,
							finishedAt = finishedAt,
							exitName = rows.getString("exit_name")
								?: if (terminalPhase == "failed") "Error" else null,
							documentsOnStart = documents(
								activity, rows.getString("documents_before"), startedAt,
							),
							documentsOnExit = documents(
								activity, rows.getString("documents_after"), finishedAt,
							),
							connectorInput = rows.getString("connector_input"),
							connectorOutput = rows.getString("connector_output"),
							error = rows.getString("error"),
						)
					}
				}
			}
			result
		}
	}

	private fun resolveProcessId(
		connection: Connection,
		requestNumber: String,
		requestedName: String,
	): String? {
		val normalized = requestedName.trim()
		val withoutPrefix = if (normalized.startsWith("PR_", ignoreCase = true)) normalized.substring(3) else normalized
		connection.prepareStatement(SQL_RESOLVE_PROCESS).use { statement ->
			statement.setString(1, requestNumber)
			statement.setString(2, normalized)
			statement.setString(3, withoutPrefix)
			statement.setBoolean(4, normalized.equals("MainFlow", ignoreCase = true))
			statement.setString(5, normalized)
			statement.setString(6, withoutPrefix)
			statement.executeQuery().use { rows ->
				return if (rows.next()) rows.getString(1) else null
			}
		}
	}

	private fun documents(
		activity: ProcessActivity,
		jsonValue: String?,
		updatedAt: String?,
	): List<DataDocumentValue> {
		val values: Map<String, String> = if (jsonValue.isNullOrBlank()) {
			emptyMap()
		} else {
			json.readValue(jsonValue, STRING_MAP)
		}
		val declared = activity.dataDocuments.associateBy { it.referenceName }
		val names = if (declared.isEmpty()) values.keys else declared.keys + (values.keys - declared.keys)
		return names.map { name ->
			val document = declared[name]
			DataDocumentValue(
				name = name,
				access = document?.access ?: "Trace",
				value = values[name],
				updatedAt = values[name]?.let { updatedAt },
			)
		}
	}

	private fun connect(): Connection =
		if (user.isNullOrBlank()) DriverManager.getConnection(jdbcUrl)
		else DriverManager.getConnection(jdbcUrl, user, password)

	private fun instant(rows: ResultSet, column: String): String? =
		rows.getTimestamp(column)?.toInstant()?.toString()

	private companion object {
		val STRING_MAP = object : TypeReference<Map<String, String>>() {}

		const val SQL_RESOLVE_PROCESS = """
			select process_id
			from flow_trace
			where instance_id = ?
			  and phase = 'before'
			  and (
			    lower(process_id) = lower(?)
			    or lower(process_id) = lower(?)
			    or (? and lower(process_id) like 'pmid%')
			  )
			group by process_id
			order by
			  case
			    when lower(process_id) = lower(?) then 0
			    when lower(process_id) = lower(?) then 1
			    else 2
			  end,
			  count(*) desc
			limit 1
		"""

		const val SQL_PASS_COUNTS = """
			select activity_id, count(*)
			from flow_trace
			where instance_id = ? and process_id = ? and phase = 'before'
			group by activity_id
		"""

		const val SQL_TRACE_EVENTS = """
			select b.activity_id,
			       b.at as started_at,
			       terminal.at as finished_at,
			       terminal.exit_name
			from flow_trace b
			left join lateral (
			  select t.at, t.exit_name
			  from flow_trace t
			  where t.instance_id = b.instance_id
			    and t.activity_sequence = b.activity_sequence
			    and t.process_id = b.process_id
			    and t.activity_id = b.activity_id
			    and t.phase in ('after', 'failed')
			  order by t.id desc
			  limit 1
			) terminal on true
			where b.instance_id = ? and b.process_id = ? and b.phase = 'before'
			order by b.id
		"""

		const val SQL_CURRENT_PROCESS_POSITION = """
			select b.process_id,
			       b.activity_id,
			       b.at as started_at,
			       terminal.at as finished_at
			from flow_trace b
			left join lateral (
			  select t.at
			  from flow_trace t
			  where t.instance_id = b.instance_id
			    and t.activity_sequence = b.activity_sequence
			    and t.process_id = b.process_id
			    and t.activity_id = b.activity_id
			    and t.phase in ('after', 'failed')
			  order by t.id desc
			  limit 1
			) terminal on true
			where b.instance_id = ? and b.phase = 'before'
			order by b.id desc
			limit 1
		"""

		/** Защита от гигантских трейсов: в таблице показываются только последние вызовы. */
		const val REQUEST_TRACE_LIMIT = 5000

		const val SQL_REQUEST_TRACE = """
			select b.process_id,
			       b.activity_id,
			       b.at as started_at,
			       terminal.at as finished_at,
			       terminal.phase as terminal_phase,
			       terminal.exit_name
			from flow_trace b
			left join lateral (
			  select t.at, t.phase, t.exit_name
			  from flow_trace t
			  where t.instance_id = b.instance_id
			    and t.activity_sequence = b.activity_sequence
			    and t.process_id = b.process_id
			    and t.activity_id = b.activity_id
			    and t.phase in ('after', 'failed')
			  order by t.id desc
			  limit 1
			) terminal on true
			where b.instance_id = ? and b.phase = 'before'
			order by b.id desc
			limit ?
		"""

		const val SQL_ACTIVITY_CALLS = """
			select b.at as started_at,
			       b.documents as documents_before,
			       terminal.at as finished_at,
			       terminal.phase as terminal_phase,
			       terminal.exit_name,
			       terminal.documents as documents_after,
			       terminal.error,
			       connector_input.xml as connector_input,
			       connector_output.xml as connector_output
			from flow_trace b
			left join lateral (
			  select t.at, t.phase, t.exit_name, t.documents, t.error
			  from flow_trace t
			  where t.instance_id = b.instance_id
			    and t.activity_sequence = b.activity_sequence
			    and t.process_id = b.process_id
			    and t.activity_id = b.activity_id
			    and t.phase in ('after', 'failed')
			  order by t.id desc
			  limit 1
			) terminal on true
			left join lateral (
			  select t.xml
			  from flow_trace t
			  where t.instance_id = b.instance_id
			    and t.activity_sequence = b.activity_sequence
			    and t.process_id = b.process_id
			    and t.activity_id = b.activity_id
			    and t.phase = 'connectorInput'
			  order by t.id desc
			  limit 1
			) connector_input on true
			left join lateral (
			  select t.xml
			  from flow_trace t
			  where t.instance_id = b.instance_id
			    and t.activity_sequence = b.activity_sequence
			    and t.process_id = b.process_id
			    and t.activity_id = b.activity_id
			    and t.phase = 'connectorOutput'
			  order by t.id desc
			  limit 1
			) connector_output on true
			where b.instance_id = ?
			  and b.process_id = ?
			  and lower(b.activity_id) = lower(?)
			  and b.phase = 'before'
			order by b.id
		"""
	}
}
