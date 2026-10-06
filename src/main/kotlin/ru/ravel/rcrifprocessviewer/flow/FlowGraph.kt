package ru.ravel.rcrifprocessviewer.flow

import ru.ravel.rcrifprocessviewer.dto.ProcedureModel

/** Стрелка схемы: из выхода [exitName] блока [fromUid] в блок [toUid]. */
data class FlowEdge(
	val uid: String?,
	val fromUid: String,
	val toUid: String,
	val exitName: String?,
)

/** Один простой (без повторов блоков) путь от начала до конца. */
data class FlowPath(
	val nodes: List<String>,
	val edges: List<FlowEdge>,
)

/**
 * Полный граф переходов процедуры: все стрелки, без учёта того, что где-то нужно «дойти».
 * Узлы — это uid блоков; start-блок процедуры тоже узел (у него есть исходящие стрелки).
 */
class FlowGraph(val edges: List<FlowEdge>) {

	private val outgoingByUid: Map<String, List<FlowEdge>> = edges.groupBy { it.fromUid }
	private val incomingByUid: Map<String, List<FlowEdge>> = edges.groupBy { it.toUid }

	val nodes: Set<String> = buildSet {
		edges.forEach {
			add(it.fromUid)
			add(it.toUid)
		}
	}

	fun outgoing(uid: String): List<FlowEdge> = outgoingByUid[uid].orEmpty()

	fun incoming(uid: String): List<FlowEdge> = incomingByUid[uid].orEmpty()

	companion object {
		/** Стрелки без одного из концов (оборванные в Layout.xml) в граф не попадают. */
		fun of(model: ProcedureModel): FlowGraph = FlowGraph(
			model.connections.mapNotNull { connection ->
				val from = connection.fromUid ?: return@mapNotNull null
				val to = connection.toUid ?: return@mapNotNull null
				FlowEdge(connection.uid, from, to, connection.exitName)
			},
		)
	}
}
