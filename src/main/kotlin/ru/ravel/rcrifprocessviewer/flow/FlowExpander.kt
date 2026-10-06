package ru.ravel.rcrifprocessviewer.flow

/**
 * Блок развёрнутого потока. [id] уникален во всём потоке: у блока корневой процедуры это его uid,
 * у блока вызываемой процедуры — `<id вызова>><uid>`, поэтому одна процедура, вызванная из двух
 * мест, даёт два независимых экземпляра.
 */
data class FlowNode(
	val id: String,
	val procedure: String,
	val uid: String,
	/** id блока вызова, из которого развёрнут этот экземпляр; null — корневая процедура. */
	val callSiteId: String?,
) {
	val depth: Int get() = id.count { it == '>' }
}

/** Граф корневой процедуры, в который подставлены тела вызываемых процедур. */
class ExpandedFlow(
	val graph: FlowGraph,
	val nodes: Map<String, FlowNode>,
	/** true — часть вызовов не развёрнута из-за лимита глубины или размера. */
	val truncated: Boolean,
) {
	private val byActivity: Map<Pair<String, String>, List<String>> =
		nodes.values.groupBy({ it.procedure.lowercase() to it.uid }, { it.id })

	/** Все экземпляры блока [uid] процедуры [procedure]. */
	fun instances(procedure: String, uid: String): List<String> =
		byActivity[procedureNameOf(procedure).lowercase() to uid].orEmpty()
}

/**
 * Разворачивает вызовы процедур, чтобы по потоку можно было искать пути через границы процедур.
 *
 * Вход в вызов — стрелка «Вызов» из блока ProcedureCall в первые блоки вызываемой процедуры.
 * Выход — стрелка из каждого её ProcedureReturn в то, что вызывающий блок соединяет с выходом,
 * совпадающим с ConnectionID возврата. Выходы развёрнутого вызова обходят тело процедуры только
 * через возвраты, поэтому путь не может войти в одну копию процедуры, а выйти в другую.
 *
 * Разворачиваются лишь вызовы, ведущие к процедурам из [interesting]: остальные остаются
 * обычными блоками. Вложенность ограничена: цепочка вызовов до нужной процедуры может быть
 * длиннее самой короткой не более чем на [extraCallDepth] вызовов (Int.MAX_VALUE — без
 * ограничения). Без этого обработчики ошибок и прочие процедуры, которые вызываются отовсюду,
 * раздували бы граф в десятки тысяч блоков. Рекурсивный вызов процедуры, которая уже развёрнута
 * выше по цепочке, остаётся блоком.
 */
object FlowExpander {

	const val CALL_EXIT = "Вызов"
	const val MAX_DEPTH = 12
	const val MAX_NODES = 50_000

	fun expand(
		catalog: ProcedureCatalog,
		rootName: String,
		interesting: Collection<String>,
		extraCallDepth: Int = 0,
	): ExpandedFlow? {
		val root = catalog.find(rootName) ?: return null
		val rootKey = root.name.lowercase()
		val depthsToTargets = interesting.map(catalog::callDepthsTo)

		// Вызов на глубине [depth] во вложенную процедуру [calleeKey] нужен, если по нему ещё можно
		// дойти до одной из целевых процедур, не превысив кратчайшую цепочку больше чем на запас.
		fun leadsToTarget(calleeKey: String, depth: Int): Boolean = depthsToTargets.any { depths ->
			val rootDepth = depths[rootKey] ?: return@any false
			val calleeDepth = depths[calleeKey] ?: return@any false
			depth.toLong() + 1 + calleeDepth <= rootDepth.toLong() + extraCallDepth
		}

		val nodes = LinkedHashMap<String, FlowNode>()
		val edges = ArrayList<FlowEdge>()
		var truncated = false

		fun instantiate(flow: ProcedureFlow, prefix: String, callSiteId: String?, stack: Set<String>) {
			val isRoot = callSiteId == null
			flow.activities.keys.forEach { uid -> nodes[prefix + uid] = FlowNode(prefix + uid, flow.name, uid, callSiteId) }
			if (isRoot) flow.startUid?.let { nodes[prefix + it] = FlowNode(prefix + it, flow.name, it, null) }

			val expanded = LinkedHashMap<String, ProcedureFlow>()
			for (uid in flow.activities.keys) {
				val callee = flow.calleeOf(uid)?.let(catalog::find) ?: continue
				val key = callee.name.lowercase()
				if (key in stack || callee.startUid == null || !leadsToTarget(key, stack.size - 1)) continue
				if (stack.size >= MAX_DEPTH || nodes.size + callee.activities.size > MAX_NODES) {
					truncated = true
					continue
				}
				expanded[uid] = callee
			}

			for (edge in flow.graph.edges) {
				// Старт вызываемой процедуры заменён стрелкой «Вызов», а выходы развёрнутого
				// вызова заменены возвратами из тела процедуры.
				if (!isRoot && edge.fromUid == flow.startUid) continue
				if (edge.fromUid in expanded) continue
				edges += FlowEdge(edge.uid?.let { prefix + it }, prefix + edge.fromUid, prefix + edge.toUid, edge.exitName)
			}

			for ((callUid, callee) in expanded) {
				val calleePrefix = "$prefix$callUid>"
				val callId = prefix + callUid
				instantiate(callee, calleePrefix, callId, stack + callee.name.lowercase())

				callee.graph.outgoing(callee.startUid!!).forEach { entry ->
					edges += FlowEdge(null, callId, calleePrefix + entry.toUid, CALL_EXIT)
				}
				for (returnUid in callee.activities.keys) {
					val exit = callee.returnExitOf(returnUid) ?: continue
					flow.graph.outgoing(callUid)
						.filter { exitKey(it.exitName) == exitKey(exit) }
						.forEach { continuation ->
							edges += FlowEdge(null, calleePrefix + returnUid, prefix + continuation.toUid, exit)
						}
				}
			}
		}

		instantiate(root, prefix = "", callSiteId = null, stack = setOf(root.name.lowercase()))
		return ExpandedFlow(FlowGraph(edges), nodes, truncated)
	}
}
