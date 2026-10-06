package ru.ravel.rcrifprocessviewer.flow

/** Движок поиска путей: строит [PathGraph] по графу переходов процедуры. */
object PathGraphBuilder {

	/** null — из [startUid] до [endUid] дойти нельзя. */
	fun build(graph: FlowGraph, startUid: String, endUid: String): PathGraph? =
		build(graph, setOf(startUid), setOf(endUid))

	/**
	 * null — ни из одного из [starts] ни до одного из [ends] дойти нельзя. С [shortestOnly] в графе
	 * остаются только блоки и стрелки, лежащие на путях наименьшей длины (по числу стрелок;
	 * при нескольких началах/концах — наименьшей среди всех пар).
	 */
	fun build(graph: FlowGraph, starts: Set<String>, ends: Set<String>, shortestOnly: Boolean = false): PathGraph? {
		if (starts.isEmpty() || ends.isEmpty()) return null

		// Простой путь не выходит из конца и не ходит по петле на месте; при единственном начале
		// он ещё и не возвращается в него (при нескольких стрелка в начало может быть частью
		// пути, начатого с другого экземпляра).
		val singleStart = starts.size == 1
		val usable = graph.edges.filter {
			it.fromUid != it.toUid && it.fromUid !in ends && !(singleStart && it.toUid in starts)
		}
		val outgoing = usable.groupBy { it.fromUid }
		val incoming = usable.groupBy { it.toUid }

		val reachableFromStart = reach(starts) { uid -> outgoing[uid].orEmpty().map { it.toUid } }
		if (ends.none { it in reachableFromStart }) return null
		val reachesEnd = reach(ends) { uid -> incoming[uid].orEmpty().map { it.fromUid } }

		var nodes: Set<String> = reachableFromStart.filterTo(LinkedHashSet()) { it in reachesEnd }
		var edges = usable.filter { it.fromUid in nodes && it.toUid in nodes }
		if (shortestOnly) {
			val shortest = shortestPart(starts.filter { it in nodes }, ends.filter { it in nodes }, nodes, edges)
			nodes = shortest.first
			edges = shortest.second
		}

		return PathGraph(
			starts = starts.filterTo(LinkedHashSet()) { it in nodes },
			ends = ends.filterTo(LinkedHashSet()) { it in nodes },
			nodes = nodes,
			edges = edges,
			cycleEdges = cycleEdges(nodes, edges),
			backEdges = backEdges(starts.filter { it in nodes }, edges),
		)
	}

	/** Блоки и стрелки, лежащие хотя бы на одном кратчайшем пути от начал до концов. */
	private fun shortestPart(
		starts: List<String>,
		ends: List<String>,
		nodes: Set<String>,
		edges: List<FlowEdge>,
	): Pair<Set<String>, List<FlowEdge>> {
		val outgoing = edges.groupBy { it.fromUid }
		val incoming = edges.groupBy { it.toUid }
		val fromStart = distances(starts) { uid -> outgoing[uid].orEmpty().map { it.toUid } }
		val toEnd = distances(ends) { uid -> incoming[uid].orEmpty().map { it.fromUid } }
		val shortest = ends.mapNotNull { fromStart[it] }.minOrNull() ?: return nodes to edges

		val keptNodes = nodes.filterTo(LinkedHashSet()) { uid ->
			val a = fromStart[uid]
			val b = toEnd[uid]
			a != null && b != null && a + b == shortest
		}
		val keptEdges = edges.filter { edge ->
			val a = fromStart[edge.fromUid]
			val b = toEnd[edge.toUid]
			a != null && b != null && a + 1 + b == shortest
		}
		return keptNodes to keptEdges
	}

	/** Расстояния (в стрелках) от ближайшей из [origins]. */
	private fun distances(origins: Collection<String>, next: (String) -> List<String>): Map<String, Int> {
		val distance = HashMap<String, Int>()
		val queue = ArrayDeque<String>()
		origins.forEach {
			distance[it] = 0
			queue.addLast(it)
		}
		while (queue.isNotEmpty()) {
			val uid = queue.removeFirst()
			for (neighbour in next(uid)) {
				if (neighbour !in distance) {
					distance[neighbour] = distance.getValue(uid) + 1
					queue.addLast(neighbour)
				}
			}
		}
		return distance
	}

	/** Обход в ширину; порядок обнаружения сохраняется. */
	private fun reach(origins: Collection<String>, next: (String) -> List<String>): LinkedHashSet<String> {
		val seen = LinkedHashSet(origins)
		val queue = ArrayDeque<String>().apply { addAll(origins) }
		while (queue.isNotEmpty()) {
			for (uid in next(queue.removeFirst())) {
				if (seen.add(uid)) queue.addLast(uid)
			}
		}
		return seen
	}

	/** Стрелки внутри нетривиальных компонент сильной связности (алгоритм Тарьяна, без рекурсии). */
	private fun cycleEdges(nodes: Set<String>, edges: List<FlowEdge>): Set<FlowEdge> {
		val adjacency = edges.groupBy { it.fromUid }.mapValues { (_, list) -> list.map { it.toUid } }
		val order = HashMap<String, Int>()
		val low = HashMap<String, Int>()
		val component = HashMap<String, Int>()
		val componentSize = HashMap<Int, Int>()
		val stack = ArrayList<String>()
		val onStack = HashSet<String>()

		class Frame(val uid: String) {
			var nextNeighbour = 0
		}

		val frames = ArrayList<Frame>()
		fun enter(uid: String) {
			order[uid] = order.size
			low[uid] = order.getValue(uid)
			stack += uid
			onStack += uid
			frames += Frame(uid)
		}

		for (root in nodes) {
			if (root in order) continue
			enter(root)
			while (frames.isNotEmpty()) {
				val frame = frames.last()
				val neighbours = adjacency[frame.uid].orEmpty()
				if (frame.nextNeighbour < neighbours.size) {
					val neighbour = neighbours[frame.nextNeighbour++]
					if (neighbour !in order) {
						enter(neighbour)
					} else if (neighbour in onStack) {
						low[frame.uid] = minOf(low.getValue(frame.uid), order.getValue(neighbour))
					}
					continue
				}

				frames.removeAt(frames.lastIndex)
				if (low[frame.uid] == order[frame.uid]) {
					val id = componentSize.size
					var size = 0
					do {
						val member = stack.removeAt(stack.lastIndex)
						onStack -= member
						component[member] = id
						size++
					} while (member != frame.uid)
					componentSize[id] = size
				}
				frames.lastOrNull()?.let { parent ->
					low[parent.uid] = minOf(low.getValue(parent.uid), low.getValue(frame.uid))
				}
			}
		}

		return edges.filterTo(LinkedHashSet()) { edge ->
			val id = component[edge.fromUid]
			id != null && id == component[edge.toUid] && (componentSize[id] ?: 0) > 1
		}
	}

	/** Стрелки, ведущие при обходе в глубину от начал в блок, который ещё «в работе». */
	private fun backEdges(starts: List<String>, edges: List<FlowEdge>): Set<FlowEdge> {
		val adjacency = edges.groupBy { it.fromUid }
		val inProgress = HashSet<String>()
		val done = HashSet<String>()
		val result = LinkedHashSet<FlowEdge>()

		for (start in starts) {
			if (start in done) continue
			val iterators = ArrayList<Pair<String, Iterator<FlowEdge>>>()
			inProgress += start
			iterators += start to adjacency[start].orEmpty().iterator()
			while (iterators.isNotEmpty()) {
				val (uid, iterator) = iterators.last()
				if (!iterator.hasNext()) {
					iterators.removeAt(iterators.lastIndex)
					inProgress -= uid
					done += uid
					continue
				}
				val edge = iterator.next()
				when {
					edge.toUid in inProgress -> result += edge
					edge.toUid !in done -> {
						inProgress += edge.toUid
						iterators += edge.toUid to adjacency[edge.toUid].orEmpty().iterator()
					}
				}
			}
		}
		return result
	}
}
