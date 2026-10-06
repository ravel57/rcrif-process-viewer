package ru.ravel.rcrifprocessviewer.flow

import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.dto.ProcessConnection
import ru.ravel.rcrifprocessviewer.dto.Waypoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NestedPathFinderTest {

	private fun activity(
		uid: String,
		type: ActivityType = ActivityType.DATA_MAPPING,
		callee: String? = null,
		returnExit: String? = null,
	) = ProcessActivity(
		uid = uid, reference = uid, type = type, x = 0.0, y = 0.0, width = 120.0, height = 60.0,
		availableExits = emptyList(), procedureToCall = callee, dir = null, dataDocuments = emptyList(),
		returnExit = returnExit,
	)

	private fun call(uid: String, callee: String) = activity(uid, ActivityType.PROCEDURE_CALL, callee = callee)

	private fun ret(uid: String, exit: String = "") = activity(uid, ActivityType.PROCEDURE_RETURN, returnExit = exit)

	private fun edge(from: String, to: String, exit: String = "Completed") =
		ProcessConnection("$from-$exit-$to", from, to, exit, mutableListOf())

	private fun procedure(name: String, start: String, activities: List<ProcessActivity>, vararg edges: ProcessConnection) =
		ProcedureModel(name, File("."), activities.toMutableList(), edges.toMutableList(), Waypoint(0.0, 0.0), start)

	/** Вложенные узлы в тестах читаются как "вызов>блок", поэтому удобнее сравнивать по uid блоков. */
	private fun NestedPathGraph.routes(): Set<String> =
		paths.enumeratePaths().paths.map { path ->
			path.nodes.joinToString(" > ") { flow.nodes.getValue(it).uid }
		}.toSet()

	// A: sA -> pc(B) -Completed-> x ; B: sB -> b1 -> b2 -> rB(Completed)
	private val simple = ProcedureCatalog(
		listOf(
			procedure(
				"A", "sA", listOf(call("pc", "B"), activity("x")),
				edge("sA", "pc", "Start"), edge("pc", "x"),
			),
			procedure(
				"B", "sB", listOf(activity("b1"), activity("b2"), ret("rB")),
				edge("sB", "b1", "Start"), edge("b1", "b2"), edge("b2", "rB"),
			),
		),
	)

	@Test
	fun `path to an activity inside a called procedure goes through the call`() {
		val result = assertNotNull(NestedPathFinder.find(simple, "A", ActivityRef("A", "sA"), ActivityRef("B", "b2")))

		assertEquals(setOf("sA > pc > b1 > b2"), result.routes())
		assertEquals(setOf("A", "B"), result.procedures)
	}

	@Test
	fun `path can continue after the return into the caller`() {
		val result = assertNotNull(NestedPathFinder.find(simple, "A", ActivityRef("B", "b1"), ActivityRef("A", "x")))

		assertEquals(setOf("b1 > b2 > rB > x"), result.routes())
	}

	@Test
	fun `procedure that is not involved stays a single block`() {
		val result = assertNotNull(NestedPathFinder.find(simple, "A", ActivityRef("A", "sA"), ActivityRef("A", "x")))

		assertEquals(setOf("sA > pc > x"), result.routes())
		assertEquals(setOf("A"), result.procedures)
		assertEquals(3, result.flow.nodes.size)
	}

	@Test
	fun `procedure called from two branches gives two instances and two paths`() {
		val catalog = ProcedureCatalog(
			listOf(
				procedure(
					"A", "sA", listOf(call("first", "B"), call("second", "B")),
					edge("sA", "first", "Start"), edge("sA", "second", "Other"),
				),
				procedure("B", "sB", listOf(activity("b1"), ret("rB")), edge("sB", "b1", "Start"), edge("b1", "rB")),
			),
		)

		val result = assertNotNull(NestedPathFinder.find(catalog, "A", ActivityRef("A", "sA"), ActivityRef("B", "b1")))

		assertEquals(2, result.flow.instances("B", "b1").size)
		assertEquals(2, result.paths.ends.size)
		assertEquals(setOf("sA > first > b1", "sA > second > b1"), result.routes())
	}

	@Test
	fun `path ends at the first arrival and does not run through another copy of the target`() {
		val catalog = ProcedureCatalog(
			listOf(
				procedure(
					"A", "sA", listOf(call("first", "B"), call("second", "B")),
					edge("sA", "first", "Start"), edge("first", "second"),
				),
				procedure("B", "sB", listOf(activity("b1"), ret("rB")), edge("sB", "b1", "Start"), edge("b1", "rB")),
			),
		)

		val result = assertNotNull(NestedPathFinder.find(catalog, "A", ActivityRef("A", "sA"), ActivityRef("B", "b1")))

		assertEquals(setOf("sA > first > b1"), result.routes())
	}

	@Test
	fun `return goes only into the call it came from`() {
		val catalog = ProcedureCatalog(
			listOf(
				procedure(
					"A", "sA", listOf(call("first", "B"), call("second", "B"), activity("afterFirst"), activity("afterSecond")),
					edge("sA", "first", "Start"), edge("sA", "second", "Other"),
					edge("first", "afterFirst"), edge("second", "afterSecond"),
				),
				procedure("B", "sB", listOf(activity("b1"), ret("rB")), edge("sB", "b1", "Start"), edge("b1", "rB")),
			),
		)

		val result = assertNotNull(
			NestedPathFinder.find(catalog, "A", ActivityRef("A", "sA"), ActivityRef("A", "afterFirst")),
		)
		// B не содержит ни начала, ни конца, поэтому вызовы остаются блоками, и ничего лишнего нет.
		assertEquals(setOf("sA > first > afterFirst"), result.routes())

		val viaB = assertNotNull(NestedPathFinder.find(catalog, "A", ActivityRef("B", "b1"), ActivityRef("A", "afterFirst")))
		assertEquals(setOf("b1 > rB > afterFirst"), viaB.routes())
		assertFalse(viaB.routes().any { it.contains("afterSecond") })
	}

	@Test
	fun `returns are matched to caller exits by ConnectionID`() {
		val catalog = ProcedureCatalog(
			listOf(
				procedure(
					"A", "sA", listOf(call("pc", "B"), activity("ok"), activity("retry")),
					edge("sA", "pc", "Start"), edge("pc", "ok", "Completed"), edge("pc", "retry", "Retry"),
				),
				procedure(
					"B", "sB", listOf(activity("b1"), ret("rDone", ""), ret("rRetry", "Retry")),
					edge("sB", "b1", "Start"), edge("b1", "rDone", "Completed"), edge("b1", "rRetry", "Failed"),
				),
			),
		)

		val toRetry = assertNotNull(NestedPathFinder.find(catalog, "A", ActivityRef("B", "b1"), ActivityRef("A", "retry")))
		val toOk = assertNotNull(NestedPathFinder.find(catalog, "A", ActivityRef("B", "b1"), ActivityRef("A", "ok")))

		assertEquals(setOf("b1 > rRetry > retry"), toRetry.routes())
		assertEquals(setOf("b1 > rDone > ok"), toOk.routes())
	}

	@Test
	fun `call chain of several levels`() {
		val catalog = ProcedureCatalog(
			listOf(
				procedure("A", "sA", listOf(call("pcB", "B")), edge("sA", "pcB", "Start")),
				procedure("B", "sB", listOf(call("pcC", "Procedures/C"), ret("rB")), edge("sB", "pcC", "Start"), edge("pcC", "rB")),
				procedure("C", "sC", listOf(activity("deep"), ret("rC")), edge("sC", "deep", "Start"), edge("deep", "rC")),
			),
		)

		val result = assertNotNull(NestedPathFinder.find(catalog, "A", ActivityRef("A", "sA"), ActivityRef("C", "deep")))

		assertEquals(setOf("sA > pcB > pcC > deep"), result.routes())
		assertEquals(setOf("A", "B", "C"), result.procedures)
		assertEquals(2, result.flow.nodes.getValue(result.paths.ends.single()).depth)
	}

	@Test
	fun `longer call chains are expanded only when allowed`() {
		val catalog = ProcedureCatalog(
			listOf(
				procedure(
					"A", "sA", listOf(call("direct", "B"), call("viaC", "C")),
					edge("sA", "direct", "Start"), edge("sA", "viaC", "Other"),
				),
				procedure("C", "sC", listOf(call("inner", "B"), ret("rC")), edge("sC", "inner", "Start"), edge("inner", "rC")),
				procedure("B", "sB", listOf(activity("target"), ret("rB")), edge("sB", "target", "Start"), edge("target", "rB")),
			),
		)
		val start = ActivityRef("A", "sA")
		val end = ActivityRef("B", "target")

		val shortest = assertNotNull(NestedPathFinder.find(catalog, "A", start, end))
		val withSlack = assertNotNull(NestedPathFinder.find(catalog, "A", start, end, extraCallDepth = 1))

		assertEquals(setOf("sA > direct > target"), shortest.routes())
		assertEquals(setOf("A", "B"), shortest.procedures)
		assertEquals(setOf("sA > direct > target", "sA > viaC > inner > target"), withSlack.routes())
		assertEquals(setOf("A", "B", "C"), withSlack.procedures)
	}

	@Test
	fun `recursive procedures do not expand forever`() {
		val catalog = ProcedureCatalog(
			listOf(
				procedure("A", "sA", listOf(call("pc", "B")), edge("sA", "pc", "Start")),
				procedure(
					"B", "sB", listOf(call("pcA", "A"), call("pcSelf", "B"), activity("target"), ret("rB")),
					edge("sB", "pcA", "Start"), edge("pcA", "pcSelf"), edge("pcSelf", "target"), edge("target", "rB"),
				),
			),
		)

		val result = assertNotNull(NestedPathFinder.find(catalog, "A", ActivityRef("A", "sA"), ActivityRef("B", "target")))

		assertEquals(setOf("sA > pc > pcA > pcSelf > target"), result.routes())
		assertFalse(result.flow.truncated)
	}

	@Test
	fun `unknown or unreachable blocks give no graph`() {
		assertNull(NestedPathFinder.find(simple, "A", ActivityRef("A", "x"), ActivityRef("B", "b1")))
		assertNull(NestedPathFinder.find(simple, "A", ActivityRef("A", "sA"), ActivityRef("Missing", "b1")))
		assertNull(NestedPathFinder.find(simple, "Missing", ActivityRef("A", "sA"), ActivityRef("A", "x")))
	}

	@Test
	fun `catalog finds procedures reachable by calls`() {
		assertEquals(listOf("A", "B"), simple.reachableFrom("A").map { it.name })
		assertEquals(listOf("B"), simple.reachableFrom("B").map { it.name })
		assertTrue(simple.reachableFrom("Missing").isEmpty())
	}
}
