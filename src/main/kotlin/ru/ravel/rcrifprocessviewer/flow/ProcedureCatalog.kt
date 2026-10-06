package ru.ravel.rcrifprocessviewer.flow

import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity

internal const val COMPLETED_EXIT = "Completed"

/** `Procedures/PR_03_S1Call`, `PR_03_S1Call\` и `PR_03_S1Call` — это одна и та же процедура. */
internal fun procedureNameOf(raw: String): String =
	raw.trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

internal fun exitKey(exitName: String?): String =
	exitName.orEmpty().trim().ifEmpty { COMPLETED_EXIT }.lowercase()

/** Процедура с точки зрения движка: её граф переходов и то, что блоки значат для потока управления. */
class ProcedureFlow(val model: ProcedureModel) {

	val name: String get() = model.name
	val startUid: String? get() = model.startUid
	val graph: FlowGraph = FlowGraph.of(model)
	val activities: Map<String, ProcessActivity> = model.activities.associateBy { it.uid }

	/** Вызываемая процедура, если блок — ProcedureCall. */
	fun calleeOf(uid: String): String? =
		activities[uid]
			?.takeIf { it.type == ActivityType.PROCEDURE_CALL }
			?.procedureToCall
			?.let(::procedureNameOf)
			?.takeIf(String::isNotEmpty)

	/** Выход вызывающего блока, в который возвращает ProcedureReturn; null — блок не возврат. */
	fun returnExitOf(uid: String): String? =
		activities[uid]
			?.takeIf { it.type == ActivityType.PROCEDURE_RETURN }
			?.let { it.returnExit.orEmpty().trim().ifEmpty { COMPLETED_EXIT } }

	/** Имена (в нижнем регистре) процедур, которые вызываются из этой. */
	fun calleeKeys(): Set<String> =
		activities.keys.mapNotNullTo(LinkedHashSet()) { calleeOf(it)?.lowercase() }
}

/** Все процедуры процесса, доступные по имени. */
class ProcedureCatalog(models: Collection<ProcedureModel>) {

	val flows: List<ProcedureFlow> = models.map(::ProcedureFlow)
	private val byName: Map<String, ProcedureFlow> = flows.associateBy { it.name.lowercase() }

	fun find(name: String): ProcedureFlow? = byName[procedureNameOf(name).lowercase()]

	/** [root] и все процедуры, до которых от него можно дойти цепочкой вызовов. */
	fun reachableFrom(root: String): List<ProcedureFlow> {
		val start = find(root) ?: return emptyList()
		val seen = linkedMapOf(start.name.lowercase() to start)
		val queue = ArrayDeque<ProcedureFlow>().apply { add(start) }
		while (queue.isNotEmpty()) {
			for (key in queue.removeFirst().calleeKeys()) {
				val callee = byName[key] ?: continue
				if (seen.putIfAbsent(key, callee) == null) queue.addLast(callee)
			}
		}
		return seen.values.toList()
	}

	/**
	 * Для каждой процедуры (ключ — имя в нижнем регистре) — наименьшее число вложенных вызовов,
	 * которыми из неё можно дойти до [target]; у самой [target] это 0. Недостижимые не входят.
	 */
	fun callDepthsTo(target: String): Map<String, Int> {
		val goal = find(target)?.name?.lowercase() ?: return emptyMap()
		val callers = HashMap<String, MutableList<String>>()
		flows.forEach { flow ->
			flow.calleeKeys().forEach { callee -> callers.getOrPut(callee) { mutableListOf() } += flow.name.lowercase() }
		}
		val depth = linkedMapOf(goal to 0)
		val queue = ArrayDeque<String>().apply { add(goal) }
		while (queue.isNotEmpty()) {
			val current = queue.removeFirst()
			for (caller in callers[current].orEmpty()) {
				if (caller !in depth) {
					depth[caller] = depth.getValue(current) + 1
					queue.addLast(caller)
				}
			}
		}
		return depth
	}
}
