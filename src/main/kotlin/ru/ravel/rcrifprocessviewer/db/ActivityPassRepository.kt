package ru.ravel.rcrifprocessviewer.db

import ru.ravel.rcrifprocessviewer.dto.ProcessActivity

/**
 * Источник данных о фактическом прохождении процесса по активностям.
 *
 * П.6 исходного ТЗ: факт прохождения определяется по БД, поэтому вся логика
 * вынесена за интерфейс — UI ничего не знает о том, откуда берутся числа.
 *
 * Реализации:
 *  - [SqliteActivityPassRepository] — временная SQLite с демонстрационной историей;
 *  - [StubActivityPassRepository]   — старая in-memory заглушка;
 *  - [JdbcActivityPassRepository]  — реальные события RU Flow из PostgreSQL `flow_trace`.
 */
interface ActivityPassRepository {

	/**
	 * Сколько раз процесс по заявке [requestNumber] прошёл по каждой активности
	 * процедуры [procedureName].
	 *
	 * Ключ результата — ReferenceName активности. Активности, которых нет в мапе
	 * (или у которых значение 0), считаются непройденными.
	 */
	fun loadPassCounts(
		requestNumber: String,
		procedureName: String,
		activityReferences: List<String>,
	): Map<String, Int>

	/**
	 * П.9 ТЗ: данные разложены по вызовам активности. Для каждого вызова
	 * отдельно возвращается срез дата-документов на входе и на выходе.
	 *
	 * Порядок — хронологический: первый элемент списка это первый вызов.
	 */
	fun loadActivityCalls(
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
	): List<ActivityCall>

	/**
	 * Хронология фактических прохождений активностей процедуры.
	 *
	 * Список должен быть отсортирован от старых событий к новым. UI использует
	 * этот порядок для окраски пройденных стрелок: синий -> зелёный -> красный.
	 */
	fun loadTraceEvents(
		requestNumber: String,
		procedureName: String,
	): List<ProcessTraceEvent> = emptyList()

	/**
	 * Последняя строка глобального трейса заявки — независимо от открытой процедуры.
	 * Используется кнопкой перехода к текущему месту процесса.
	 */
	fun loadCurrentProcessPosition(requestNumber: String): CurrentProcessPosition? = null

	/**
	 * Полный трейс заявки по всем процедурам, от старых вызовов к новым.
	 * Показывается таблицей под списком процедур.
	 */
	fun loadRequestTrace(requestNumber: String): List<RequestTraceEntry> = emptyList()

	/** Человекочитаемое описание источника — показывается в статусной строке. */
	val sourceDescription: String
}


/**
 * Один проход процесса через активность.
 */
data class ActivityCall(
	/** Порядковый номер вызова, начиная с 1. */
	val index: Int,
	val startedAt: String?,
	val finishedAt: String?,
	/** Выход, по которому активность была покинута. */
	val exitName: String?,
	/** Данные на старте этого вызова. */
	val documentsOnStart: List<DataDocumentValue>,
	/** Данные на выходе из этого вызова. */
	val documentsOnExit: List<DataDocumentValue>,
	/** Точная XML-строка, переданная DS-коннектору. */
	val connectorInput: String? = null,
	/** Точная XML-строка, полученная от DS-коннектора. */
	val connectorOutput: String? = null,
	/** Ошибка активности, если вызов завершился фазой failed. */
	val error: String? = null,
)


data class DataDocumentValue(
	val name: String,
	val access: String,
	val value: String?,
	val updatedAt: String?,
)


/** Одно событие трассировки процесса, уже расположенное в хронологическом порядке. */
data class ProcessTraceEvent(
	val activityReference: String,
	val startedAt: String?,
	val finishedAt: String?,
	val exitName: String?,
	/** Исходный id из flow_trace до сопоставления с выбранной версией layout. */
	val traceActivityReference: String = activityReference,
)


/** Текущая позиция процесса по самой новой строке tracer'а заявки. */
data class CurrentProcessPosition(
	val procedureName: String,
	val activityReference: String,
	val startedAt: String?,
	val finishedAt: String?,
)


/** Один вызов активности в глобальном трейсе заявки. */
data class RequestTraceEntry(
	val procedureName: String,
	val activityReference: String,
	val startedAt: String?,
	val finishedAt: String?,
	val exitName: String?,
	/** Вызов завершился фазой failed. */
	val failed: Boolean = false,
)
