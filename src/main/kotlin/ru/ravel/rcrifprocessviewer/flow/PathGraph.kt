package ru.ravel.rcrifprocessviewer.flow

import java.math.BigInteger

/** Результат перечисления путей; [complete] = false, если остановились по лимиту. */
data class PathEnumeration(val paths: List<FlowPath>, val complete: Boolean)

/** Число путей; [exact] = false означает «не меньше [value]». */
data class PathCount(val value: BigInteger, val exact: Boolean)

/**
 * Граф всех возможных путей от любого из [starts] до любого из [ends]: ровно те блоки и стрелки,
 * которые достижимы из начала и из которых можно дойти до конца. Остальное (тупики, ветки мимо
 * цели) в него не входит.
 *
 * Несколько начал/концов бывает, когда блок вложенной процедуры вызывается из нескольких мест:
 * у каждого вызова свой экземпляр блока.
 *
 * Путь заканчивается при первом приходе в любой из [ends]; при единственном начале он в него
 * не возвращается, поэтому такие стрелки не учитываются. Циклы внутри графа сохранены:
 * [cycleEdges] — все стрелки, лежащие на циклах, [backEdges] — минимальный набор стрелок,
 * замыкающих циклы при обходе в глубину от начал (убрав их, получаем ациклический граф
 * для раскладки).
 */
class PathGraph internal constructor(
	val starts: Set<String>,
	val ends: Set<String>,
	/** Блоки в порядке обхода в ширину от начал. */
	val nodes: Set<String>,
	val edges: List<FlowEdge>,
	val cycleEdges: Set<FlowEdge>,
	val backEdges: Set<FlowEdge>,
) {

	val hasCycles: Boolean get() = cycleEdges.isNotEmpty()

	private val outgoingByUid: Map<String, List<FlowEdge>> = edges.groupBy { it.fromUid }
	private val incomingByUid: Map<String, List<FlowEdge>> = edges.groupBy { it.toUid }

	fun outgoing(uid: String): List<FlowEdge> = outgoingByUid[uid].orEmpty()

	/**
	 * Стрелки из блока, ближайшие к концу — первыми. В графе с циклами обход в глубину иначе
	 * надолго застревает в петлях и не доходит до конца; так сначала находятся короткие пути.
	 */
	private val outgoingNearestFirst: Map<String, List<FlowEdge>> by lazy {
		val distance = HashMap<String, Int>()
		val queue = ArrayDeque<String>()
		ends.forEach {
			distance[it] = 0
			queue.addLast(it)
		}
		while (queue.isNotEmpty()) {
			val uid = queue.removeFirst()
			for (edge in incoming(uid)) {
				if (edge.fromUid !in distance) {
					distance[edge.fromUid] = distance.getValue(uid) + 1
					queue.addLast(edge.fromUid)
				}
			}
		}
		outgoingByUid.mapValues { (_, list) -> list.sortedBy { distance[it.toUid] ?: Int.MAX_VALUE } }
	}

	fun incoming(uid: String): List<FlowEdge> = incomingByUid[uid].orEmpty()

	/**
	 * Все простые пути от начал до концов. Их число может расти экспоненциально, поэтому
	 * перебор ограничен: [maxPaths] найденных путей и [maxSteps] просмотренных стрелок.
	 * Стрелки с разными выходами между одной и той же парой блоков дают разные пути.
	 */
	fun enumeratePaths(
		maxPaths: Int = DEFAULT_MAX_PATHS,
		maxSteps: Long = DEFAULT_MAX_STEPS,
	): PathEnumeration {
		val found = ArrayList<FlowPath>()
		var steps = 0L

		for (start in starts) {
			if (start in ends) {
				found += FlowPath(listOf(start), emptyList())
				if (found.size >= maxPaths) return PathEnumeration(found, complete = false)
				continue
			}

			val pathNodes = ArrayList<String>().apply { add(start) }
			val pathEdges = ArrayList<FlowEdge>()
			val onPath = HashSet<String>().apply { add(start) }
			val iterators = ArrayList<Iterator<FlowEdge>>().apply { add(nearestFirst(start).iterator()) }

			while (iterators.isNotEmpty()) {
				val iterator = iterators.last()
				if (!iterator.hasNext()) {
					iterators.removeAt(iterators.lastIndex)
					onPath.remove(pathNodes.removeAt(pathNodes.lastIndex))
					if (pathEdges.isNotEmpty()) pathEdges.removeAt(pathEdges.lastIndex)
					continue
				}
				if (++steps > maxSteps) return PathEnumeration(found, complete = false)

				val edge = iterator.next()
				if (edge.toUid in onPath) continue

				pathEdges += edge
				pathNodes += edge.toUid
				if (edge.toUid in ends) {
					found += FlowPath(pathNodes.toList(), pathEdges.toList())
					pathNodes.removeAt(pathNodes.lastIndex)
					pathEdges.removeAt(pathEdges.lastIndex)
					if (found.size >= maxPaths) return PathEnumeration(found, complete = false)
					continue
				}
				onPath += edge.toUid
				iterators += nearestFirst(edge.toUid).iterator()
			}
		}
		return PathEnumeration(found, complete = true)
	}

	/**
	 * Число путей. Для графа без циклов считается точно динамическим программированием по
	 * топологическому порядку (без перебора); при циклах — перебором с теми же лимитами.
	 */
	fun countPaths(maxPaths: Int = DEFAULT_MAX_PATHS, maxSteps: Long = DEFAULT_MAX_STEPS): PathCount {
		if (hasCycles) {
			val enumeration = enumeratePaths(maxPaths, maxSteps)
			return PathCount(BigInteger.valueOf(enumeration.paths.size.toLong()), enumeration.complete)
		}

		val inDegree = HashMap<String, Int>()
		nodes.forEach { inDegree[it] = incoming(it).size }
		val ways = HashMap<String, BigInteger>()
		starts.forEach { ways[it] = BigInteger.ONE }
		val queue = ArrayDeque<String>().apply { addAll(nodes.filter { inDegree[it] == 0 }) }
		while (queue.isNotEmpty()) {
			val uid = queue.removeFirst()
			val current = ways[uid] ?: BigInteger.ZERO
			for (edge in outgoing(uid)) {
				ways[edge.toUid] = (ways[edge.toUid] ?: BigInteger.ZERO) + current
				val left = (inDegree[edge.toUid] ?: 0) - 1
				inDegree[edge.toUid] = left
				if (left == 0) queue.addLast(edge.toUid)
			}
		}
		return PathCount(ends.fold(BigInteger.ZERO) { sum, end -> sum + (ways[end] ?: BigInteger.ZERO) }, exact = true)
	}

	private fun nearestFirst(uid: String): List<FlowEdge> = outgoingNearestFirst[uid].orEmpty()

	companion object {
		const val DEFAULT_MAX_PATHS = 10_000
		const val DEFAULT_MAX_STEPS = 5_000_000L
	}
}
