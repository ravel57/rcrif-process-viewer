package ru.ravel.rcrifprocessviewer.debug

import ru.ravel.rcrifprocessviewer.db.ActivityCall
import ru.ravel.rcrifprocessviewer.db.ActivityPassRepository
import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebugCallsTest {

	private val activity = ProcessActivity(
		uid = "u1", reference = "DM_0_05_SetProcessParameters", type = ActivityType.DATA_MAPPING,
		x = 0.0, y = 0.0, width = 120.0, height = 60.0, availableExits = emptyList(),
		procedureToCall = null, dir = null, dataDocuments = emptyList(),
	)

	private class FakeRepository(private val calls: List<ActivityCall>, private val fails: Boolean = false) :
		ActivityPassRepository {
		override fun loadPassCounts(requestNumber: String, procedureName: String, activityReferences: List<String>) =
			emptyMap<String, Int>()

		override fun loadActivityCalls(requestNumber: String, procedureName: String, activity: ProcessActivity): List<ActivityCall> {
			if (fails) error("БД недоступна")
			return calls
		}

		override val sourceDescription = "fake"
	}

	private fun step(procedure: String = "MainFlow", activity: String = "DM_0_05_SetProcessParameters", docsIn: String? = "<Data><A x=\"1\"/><B/></Data>") =
		DebugStep(procedure, activity, "XSLT", "Completed", null, docsIn, "<Data><C/></Data>")

	@Test
	fun `parses a Data wrapper into separate documents`() {
		val docs = DebugDocuments.parse("<Data><A x=\"1\"><b>т</b></A><B/></Data>", "Вход")

		assertEquals(listOf("A", "B"), docs.map { it.name })
		assertTrue(docs.all { it.access == "Вход" })
		assertTrue(docs.first().value!!.contains("<b>т</b>"))
	}

	@Test
	fun `a single root document is kept as is and broken or hostile xml falls back to raw text`() {
		assertEquals(listOf("ApplicationData"), DebugDocuments.parse("<ApplicationData/>", "Вход").map { it.name })

		val broken = DebugDocuments.parse("<Data><A>", "Вход").single()
		assertEquals("Data", broken.name)
		assertEquals("<Data><A>", broken.value)

		val hostile = "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Data>&x;</Data>"
		assertEquals(hostile, DebugDocuments.parse(hostile, "Вход").single().value)

		assertTrue(DebugDocuments.parse(null, "Вход").isEmpty())
		assertTrue(DebugDocuments.parse("  ", "Вход").isEmpty())
	}

	@Test
	fun `debug calls are appended after the real ones as numbered debug tabs`() {
		val real = ActivityCall(1, "t", "t", "Completed", emptyList(), emptyList())
		val steps = DebugSteps().apply {
			add(step())
			add(step(activity = "OTHER"))
			add(step(docsIn = null))
		}

		val calls = DebugCallsRepository(FakeRepository(listOf(real)), steps)
			.loadActivityCalls("i-1", "mainflow", activity)

		assertEquals(listOf(1, 2, 3), calls.map { it.index })
		assertEquals(listOf(null, "Отладка 1", "Отладка 2"), calls.map { it.label })
		assertEquals(listOf("A", "B"), calls[1].documentsOnStart.map { it.name })
		assertEquals(listOf("C"), calls[1].documentsOnExit.map { it.name })
		assertTrue(calls[2].documentsOnStart.isEmpty())
	}

	@Test
	fun `debug calls survive an unavailable database and other procedures are not mixed in`() {
		val steps = DebugSteps().apply {
			add(step())
			add(step(procedure = "PR_03_S1Call"))
		}

		val calls = DebugCallsRepository(FakeRepository(emptyList(), fails = true), steps)
			.loadActivityCalls("i-1", "MainFlow", activity)

		assertEquals(listOf("Отладка 1"), calls.map { it.label })
		assertEquals(setOf("mainflow/dm_0_05_setprocessparameters", "pr_03_s1call/dm_0_05_setprocessparameters"), steps.markKeys())
	}
}
