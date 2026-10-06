package ru.ravel.rcrifprocessviewer.dto

import ru.ravel.rcrifprocessviewer.model.activity.ReferredDocument
import java.io.File

/**
 * Процедура, доступная в процессе: MainFlow либо папка из Procedures,
 * в которой лежит Layout.xml.
 */
data class ProcedureRef(
	val name: String,
	val dir: File,
	val isMainFlow: Boolean,
) {
	override fun toString(): String = name
}


/**
 * Активность процедуры: геометрия из Layout.xml + метаданные из Properties.xml.
 *
 * [x] и [y] изменяемые — блок можно двигать мышью (п.1 ТЗ).
 * Width/Height из Layout.xml при отрисовке не используются: ширина фиксирована,
 * а render-height вычисляется из количества выходов тем же алгоритмом, что и в
 * r-crif-layout-merger. Исходные значения сохраняются для возможной записи обратно.
 */
data class ProcessActivity(
	val uid: String,
	val reference: String,
	val type: ActivityType,
	var x: Double,
	var y: Double,
	val width: Double,
	val height: Double,
	/** Полный набор выходов, восстановленный из Properties.xml как в r-crif-layout-merger. */
	val availableExits: List<String>,
	/** Имя вызываемой процедуры для ProcedureCall, прочитанное из Properties.xml. */
	val procedureToCall: String?,
	/** Папка активности внутри процедуры, если её удалось сопоставить. */
	val dir: File?,
	/** Дата-документы, перечисленные в свойствах активности. */
	val dataDocuments: List<ReferredDocument>,
	/** Идентификатор активности в runtime-трейсе, если выбранный layout использует другое имя. */
	val traceReference: String? = null,
)


/**
 * Связь между двумя активностями со списком промежуточных точек излома.
 */
data class ProcessConnection(
	val uid: String?,
	val fromUid: String?,
	val toUid: String?,
	val exitName: String?,
	val waypoints: MutableList<Waypoint>,
)


data class Waypoint(
	var x: Double,
	var y: Double,
)


data class ProcedureModel(
	val name: String,
	val dir: File,
	val activities: MutableList<ProcessActivity>,
	val connections: MutableList<ProcessConnection>,
	val start: Waypoint?,
	val startUid: String?,
)
