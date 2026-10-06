package ru.ravel.rcrifprocessviewer.flow

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathGraphBuilderTest {

	private fun graph(vararg edges: Triple<String, String, String?>) =
		FlowGraph(edges.mapIndexed { index, (from, to, exit) -> FlowEdge("c$index", from, to, exit) })

	private fun route(path: FlowPath) = path.nodes.joinToString(">")

	@Test
	fun `diamond has both branches and nothing else`() {
		val g = graph(
			Triple("S", "A", "Start"), Triple("A", "B", "True"), Triple("A", "C", "False"),
			Triple("B", "E", "Completed"), Triple("C", "E", "Completed"),
			Triple("A", "DeadEnd", "Failed"), Triple("S", "Other", "Next"),
		)
		val result = assertNotNull(PathGraphBuilder.build(g, "S", "E"))

		assertEquals(setOf("S", "A", "B", "C", "E"), result.nodes)
		assertEquals(5, result.edges.size)
		assertFalse(result.hasCycles)
		assertEquals(setOf("S>A>B>E", "S>A>C>E"), result.enumeratePaths().paths.map(::route).toSet())
		assertEquals(PathCount(BigInteger.TWO, exact = true), result.countPaths())
	}

	@Test
	fun `finds all paths and not only the shortest ones`() {
		val g = graph(
			Triple("S", "E", "Fast"),
			Triple("S", "A", "Slow"), Triple("A", "B", "Completed"), Triple("B", "E", "Completed"),
			Triple("A", "E", "Skip"),
		)
		val result = assertNotNull(PathGraphBuilder.build(g, "S", "E"))

		assertEquals(
			setOf("S>E", "S>A>E", "S>A>B>E"),
			result.enumeratePaths().paths.map(::route).toSet(),
		)
	}

	@Test
	fun `parallel exits between the same blocks are different paths`() {
		val g = graph(Triple("S", "A", "Start"), Triple("A", "E", "Completed"), Triple("A", "E", "Failed"))
		val result = assertNotNull(PathGraphBuilder.build(g, "S", "E"))

		val paths = result.enumeratePaths().paths
		assertEquals(2, paths.size)
		assertEquals(setOf("Completed", "Failed"), paths.map { it.edges.last().exitName }.toSet())
		assertEquals(BigInteger.TWO, result.countPaths().value)
	}

	@Test
	fun `cycle is kept in the graph and every path is still simple`() {
		val g = graph(
			Triple("S", "A", "Start"), Triple("A", "B", "Completed"), Triple("B", "A", "Retry"),
			Triple("B", "E", "Completed"),
		)
		val result = assertNotNull(PathGraphBuilder.build(g, "S", "E"))

		assertTrue(result.hasCycles)
		assertEquals(setOf("A", "B"), result.cycleEdges.flatMap { listOf(it.fromUid, it.toUid) }.toSet())
		assertEquals(1, result.backEdges.size)
		assertEquals("Retry", result.backEdges.single().exitName)
		assertEquals(listOf("S>A>B>E"), result.enumeratePaths().paths.map(::route))
	}

	@Test
	fun `edge back to start and edges out of the end are ignored`() {
		val g = graph(
			Triple("S", "A", "Start"), Triple("A", "E", "Completed"),
			Triple("A", "S", "Back"), Triple("E", "A", "Again"),
		)
		val result = assertNotNull(PathGraphBuilder.build(g, "S", "E"))

		assertEquals(2, result.edges.size)
		assertFalse(result.hasCycles)
	}

	@Test
	fun `enumeration finds the short path first even with loops in the way`() {
		val g = graph(
			Triple("S", "A", "Start"), Triple("A", "B", "Next"), Triple("B", "C", "Next"), Triple("C", "A", "Loop"),
			Triple("C", "D", "Next"), Triple("D", "B", "Back"), Triple("A", "E", "Done"),
		)
		val result = assertNotNull(PathGraphBuilder.build(g, "S", "E"))

		val first = result.enumeratePaths(maxPaths = 1)

		assertEquals(listOf("S>A>E"), first.paths.map(::route))
	}

	@Test
	fun `several starts and ends are handled together`() {
		val g = graph(
			Triple("s1", "m", "Next"), Triple("s2", "m", "Next"), Triple("m", "e1", "True"), Triple("m", "e2", "False"),
			Triple("e1", "m", "Back"),
		)
		val result = assertNotNull(PathGraphBuilder.build(g, setOf("s1", "s2"), setOf("e1", "e2")))

		assertEquals(setOf("s1>m>e1", "s1>m>e2", "s2>m>e1", "s2>m>e2"), result.enumeratePaths().paths.map(::route).toSet())
		assertEquals(BigInteger.valueOf(4), result.countPaths().value)
	}

	@Test
	fun `shortest only keeps just the shortest paths`() {
		val g = graph(
			Triple("S", "A", "Start"), Triple("A", "E", "Skip"),
			Triple("S", "B", "Other"), Triple("B", "C", "Next"), Triple("C", "E", "Next"),
			Triple("A", "B", "Detour"),
		)

		val result = assertNotNull(PathGraphBuilder.build(g, setOf("S"), setOf("E"), shortestOnly = true))

		assertEquals(setOf("S", "A", "E"), result.nodes)
		assertEquals(listOf("S>A>E"), result.enumeratePaths().paths.map(::route))
		assertFalse(result.hasCycles)
	}

	@Test
	fun `shortest only keeps every path of the minimal length`() {
		val g = graph(
			Triple("S", "A", "True"), Triple("S", "B", "False"), Triple("A", "E", "Next"), Triple("B", "E", "Next"),
			Triple("S", "L1", "Long"), Triple("L1", "L2", "Next"), Triple("L2", "E", "Next"),
		)

		val result = assertNotNull(PathGraphBuilder.build(g, setOf("S"), setOf("E"), shortestOnly = true))

		assertEquals(setOf("S>A>E", "S>B>E"), result.enumeratePaths().paths.map(::route).toSet())
	}

	@Test
	fun `shortest only picks the globally nearest start and end pair`() {
		val g = graph(
			Triple("s1", "m", "Next"), Triple("m", "e1", "Next"),
			Triple("s2", "x", "Next"), Triple("x", "y", "Next"), Triple("y", "e2", "Next"),
		)

		val result = assertNotNull(PathGraphBuilder.build(g, setOf("s1", "s2"), setOf("e1", "e2"), shortestOnly = true))

		assertEquals(listOf("s1>m>e1"), result.enumeratePaths().paths.map(::route))
		assertEquals(setOf("s1"), result.starts)
		assertEquals(setOf("e1"), result.ends)
	}

	@Test
	fun `unreachable end gives no graph`() {
		val g = graph(Triple("S", "A", "Start"), Triple("X", "E", "Completed"))

		assertNull(PathGraphBuilder.build(g, "S", "E"))
	}

	@Test
	fun `start equal to end is a single trivial path`() {
		val result = assertNotNull(PathGraphBuilder.build(graph(Triple("S", "A", null)), "S", "S"))

		assertEquals(listOf("S"), result.enumeratePaths().paths.single().nodes)
		assertEquals(BigInteger.ONE, result.countPaths().value)
	}

	@Test
	fun `acyclic count is exact without enumeration`() {
		// 25 ромбов подряд: 2^25 путей — перебором не взять, динамикой считается мгновенно.
		val edges = buildList {
			for (i in 0 until 25) {
				add(Triple("n$i", "a$i", "True"))
				add(Triple("n$i", "b$i", "False"))
				add(Triple("a$i", "n${i + 1}", "Completed"))
				add(Triple("b$i", "n${i + 1}", "Completed"))
			}
		}
		val result = assertNotNull(PathGraphBuilder.build(graph(*edges.toTypedArray()), "n0", "n25"))

		assertEquals(PathCount(BigInteger.TWO.pow(25), exact = true), result.countPaths())
		val limited = result.enumeratePaths(maxPaths = 100)
		assertEquals(100, limited.paths.size)
		assertFalse(limited.complete)
	}

	@Test
	fun `step limit stops a huge search`() {
		val edges = buildList {
			for (i in 0 until 25) {
				add(Triple("n$i", "a$i", "True"))
				add(Triple("n$i", "b$i", "False"))
				add(Triple("a$i", "n${i + 1}", "Completed"))
				add(Triple("b$i", "n${i + 1}", "Completed"))
			}
		}
		val result = assertNotNull(PathGraphBuilder.build(graph(*edges.toTypedArray()), "n0", "n25"))

		val limited = result.enumeratePaths(maxPaths = Int.MAX_VALUE, maxSteps = 1_000)
		assertFalse(limited.complete)
	}

	@Test
	fun `long chain does not overflow the stack`() {
		val edges = (0 until 20_000).map { Triple("n$it", "n${it + 1}", "Completed") } +
			Triple("n19999", "n5", "Loop")
		val result = assertNotNull(PathGraphBuilder.build(graph(*edges.toTypedArray()), "n0", "n20000"))

		assertTrue(result.hasCycles)
		assertEquals(1, result.enumeratePaths().paths.size)
	}
}
