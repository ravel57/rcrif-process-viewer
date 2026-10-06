package ru.ravel.rcrifprocessviewer.db

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.model.activity.ReferredDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JdbcActivityPassRepositoryTest {

	@Test
	fun readsRuFlowTraceAndGroupsConnectorPayloadsIntoCalls() {
		val url = System.getenv("TEST_RUFLOW_DB_URL")
		val instanceId = System.getenv("TEST_RUFLOW_INSTANCE_ID")
		assumeTrue(!url.isNullOrBlank() && !instanceId.isNullOrBlank())

		val repository = JdbcActivityPassRepository(
			url!!,
			System.getenv("TEST_RUFLOW_DB_USER"),
			System.getenv("TEST_RUFLOW_DB_PASSWORD"),
		)
		val activity = ProcessActivity(
			uid = "test",
			reference = "DS_0_00_GetNSValues",
			type = ActivityType.DATA_SOURCE,
			x = 0.0,
			y = 0.0,
			width = 120.0,
			height = 60.0,
			availableExits = emptyList(),
			procedureToCall = null,
			dir = File("."),
			dataDocuments = listOf(ReferredDocument("ApplicationData", "Input")),
		)

		val counts = repository.loadPassCounts(instanceId!!, "MainFlow", listOf(activity.reference))
		assertTrue((counts[activity.reference] ?: 0) > 0)
		val trace = repository.loadTraceEvents(instanceId, "MainFlow")
		assertTrue(trace.any { it.activityReference == activity.reference })
		val calls = repository.loadActivityCalls(instanceId, "MainFlow", activity)
		assertTrue(calls.isNotEmpty())
		assertNotNull(calls.first().connectorInput)
		assertNotNull(calls.first().connectorOutput)
		assertTrue(calls.first().documentsOnStart.any { it.name == "ApplicationData" })
		val runtimeOnlyCalls = repository.loadActivityCalls(
			instanceId,
			"MainFlow",
			activity.copy(dataDocuments = emptyList(), traceReference = activity.reference),
		)
		assertTrue(runtimeOnlyCalls.first().documentsOnStart.isNotEmpty())
		assertNotNull(repository.loadCurrentProcessPosition(instanceId))
		assertTrue(repository.loadRequestTrace(instanceId).any { it.activityReference == activity.reference })
	}
}
