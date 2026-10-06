package ru.ravel.rcrifprocessviewer.flow

/** Блок конкретной процедуры; для START процедуры [uid] — её startUid. */
data class ActivityRef(val procedure: String, val uid: String)

/** Пути между блоками, которые могут лежать в разных (вложенных друг в друга) процедурах. */
class NestedPathGraph(val paths: PathGraph, val flow: ExpandedFlow) {

	/** Процедуры, блоки которых попали в граф путей. */
	val procedures: Set<String> =
		paths.nodes.mapNotNullTo(LinkedHashSet()) { flow.nodes[it]?.procedure }
}

object NestedPathFinder {

	/**
	 * Все пути от [start] до [end] внутри процедуры [root] и всех процедур, вызываемых из неё.
	 * Если блок лежит во вложенной процедуре, он ищется во всех её вызовах, а у каждой
	 * найденной копии свой экземпляр в графе. [extraCallDepth] — насколько цепочка вызовов может
	 * быть длиннее кратчайшей (см. [FlowExpander]); с [shortestOnly] граф содержит только пути
	 * наименьшей длины. null — пути нет.
	 */
	fun find(
		catalog: ProcedureCatalog,
		root: String,
		start: ActivityRef,
		end: ActivityRef,
		extraCallDepth: Int = 0,
		shortestOnly: Boolean = false,
	): NestedPathGraph? {
		val rootKey = catalog.find(root)?.name?.lowercase() ?: return null
		val interesting = listOf(start.procedure, end.procedure)
			.mapNotNull { catalog.find(it)?.name }
			.filter { it.lowercase() != rootKey }
		val flow = FlowExpander.expand(catalog, root, interesting, extraCallDepth) ?: return null

		val starts = flow.instances(start.procedure, start.uid).toSet()
		val ends = flow.instances(end.procedure, end.uid).toSet()
		val paths = PathGraphBuilder.build(flow.graph, starts, ends, shortestOnly) ?: return null
		return NestedPathGraph(paths, flow)
	}
}
