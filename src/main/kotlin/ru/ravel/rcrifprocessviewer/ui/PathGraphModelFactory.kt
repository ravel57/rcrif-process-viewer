package ru.ravel.rcrifprocessviewer.ui

import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.dto.ProcessConnection
import ru.ravel.rcrifprocessviewer.dto.Waypoint
import ru.ravel.rcrifprocessviewer.flow.FlowEdge
import ru.ravel.rcrifprocessviewer.flow.NestedPathGraph
import ru.ravel.rcrifprocessviewer.flow.PathGraph
import ru.ravel.rcrifprocessviewer.flow.ProcedureCatalog

/**
 * Визуальная часть «Показать путь»: раскладывает граф путей по колонкам и собирает обычную
 * [ProcedureModel], которую рисует тот же [ProcessDiagramPane], что и обычные схемы.
 *
 * Блоки вложенных процедур входят в ту же схему отдельными экземплярами (у каждого вызова
 * свой): их uid — id экземпляра, а в [ProcessActivity.procedureName] записана их процедура.
 *
 * Колонка блока — длина самого длинного пути до него от начала (стрелки, замыкающие циклы,
 * при этом не считаются и уходят назад). Порядок блоков в колонке улучшается проходами
 * по барицентрам соседей, чтобы стрелки реже пересекались. Стрелки получаются без
 * сохранённых изломов — их трассирует сам [ProcessDiagramPane].
 */
object PathGraphModelFactory {

	const val COLUMN_STRIDE = 310.0
	private const val ROW_GAP = 50.0
	private const val SWEEPS = 4

	/** [root] — процедура, из которой строился путь; её START (если он в графе) рисуется кружком. */
	fun build(catalog: ProcedureCatalog, root: ProcedureModel, result: NestedPathGraph): ProcedureModel {
		val graph = result.paths
		val activitiesById = HashMap<String, ProcessActivity>()
		for (id in graph.nodes) {
			val node = result.flow.nodes[id] ?: continue
			val activity = catalog.find(node.procedure)?.activities?.get(node.uid) ?: continue
			activitiesById[id] = activity.copy(
				uid = id,
				procedureName = if (node.callSiteId != null) node.procedure else null,
			)
		}
		val hasStart = root.startUid != null && root.startUid in graph.nodes

		val height = blockHeights(graph, activitiesById)
		val columns = columns(graph)
		val centers = arrange(graph, columns, height)

		val activities = graph.nodes.mapNotNull { id ->
			activitiesById[id]?.copy(x = columnX(columns, id), y = centers.getValue(id))
		}.toMutableList()

		val connections = graph.edges.map { edge ->
			ProcessConnection(
				uid = edge.uid,
				fromUid = edge.fromUid,
				toUid = edge.toUid,
				exitName = edge.exitName,
				waypoints = mutableListOf(),
			)
		}.toMutableList()

		return ProcedureModel(
			name = root.name,
			dir = root.dir,
			activities = activities,
			connections = connections,
			start = if (hasStart) Waypoint(columnX(columns, root.startUid!!), centers.getValue(root.startUid!!)) else null,
			startUid = if (hasStart) root.startUid else null,
		)
	}

	private fun columnX(columns: Map<String, Int>, uid: String): Double =
		columns.getValue(uid) * COLUMN_STRIDE

	private fun blockHeights(graph: PathGraph, activitiesById: Map<String, ProcessActivity>): Map<String, Double> =
		graph.nodes.associateWith { uid ->
			val activity = activitiesById[uid] ?: return@associateWith DiagramGeometry.START_SIZE
			val exits = buildList {
				addAll(activity.availableExits)
				graph.outgoing(uid).mapNotNullTo(this) { it.exitName?.takeIf(String::isNotBlank) }
			}.distinctBy { it.lowercase() }
			DiagramGeometry.requiredBlockHeight(exits.size)
		}

	/** Самый длинный путь от начала по стрелкам без замыкающих циклы. */
	private fun columns(graph: PathGraph): Map<String, Int> {
		val forward = graph.edges.filter { it !in graph.backEdges }
		val inDegree = HashMap<String, Int>().apply { graph.nodes.forEach { put(it, 0) } }
		forward.forEach { inDegree[it.toUid] = inDegree.getValue(it.toUid) + 1 }
		val outgoing = forward.groupBy(FlowEdge::fromUid)

		val column = HashMap<String, Int>()
		val queue = ArrayDeque<String>()
		graph.nodes.filter { inDegree.getValue(it) == 0 }.forEach {
			column[it] = 0
			queue.addLast(it)
		}
		while (queue.isNotEmpty()) {
			val uid = queue.removeFirst()
			for (edge in outgoing[uid].orEmpty()) {
				column[edge.toUid] = maxOf(column[edge.toUid] ?: 0, column.getValue(uid) + 1)
				val left = inDegree.getValue(edge.toUid) - 1
				inDegree[edge.toUid] = left
				if (left == 0) queue.addLast(edge.toUid)
			}
		}
		return graph.nodes.associateWith { column[it] ?: 0 }
	}

	/** Возвращает y-центры блоков; колонки центрируются по одной горизонтали. */
	private fun arrange(
		graph: PathGraph,
		columns: Map<String, Int>,
		height: Map<String, Double>,
	): Map<String, Double> {
		val order = sortedMapOf<Int, MutableList<String>>()
		graph.nodes.forEach { order.getOrPut(columns.getValue(it)) { mutableListOf() }.add(it) }

		var centers = place(order, height)
		val neighbours = HashMap<String, MutableList<String>>()
		graph.edges.forEach { edge ->
			neighbours.getOrPut(edge.fromUid) { mutableListOf() }.add(edge.toUid)
			neighbours.getOrPut(edge.toUid) { mutableListOf() }.add(edge.fromUid)
		}

		repeat(SWEEPS) { sweep ->
			val keys = if (sweep % 2 == 0) order.keys.toList() else order.keys.toList().asReversed()
			for (key in keys) {
				val members = order.getValue(key)
				val barycentre = members.associateWith { uid ->
					val near = neighbours[uid].orEmpty().filter { columns.getValue(it) != key }
					if (near.isEmpty()) centers.getValue(uid) else near.sumOf { centers.getValue(it) } / near.size
				}
				members.sortBy { barycentre.getValue(it) }
				centers = place(order, height)
			}
		}
		return centers
	}

	private fun place(order: Map<Int, List<String>>, height: Map<String, Double>): Map<String, Double> {
		val centers = HashMap<String, Double>()
		order.values.forEach { members ->
			val total = members.sumOf { height.getValue(it) } + ROW_GAP * (members.size - 1)
			var top = -total / 2.0
			members.forEach { uid ->
				centers[uid] = top + height.getValue(uid) / 2.0
				top += height.getValue(uid) + ROW_GAP
			}
		}
		return centers
	}
}
