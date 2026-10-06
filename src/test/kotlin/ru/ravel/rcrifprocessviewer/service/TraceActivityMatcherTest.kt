package ru.ravel.rcrifprocessviewer.service

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TraceActivityMatcherTest {

	@TempDir
	lateinit var root: Path

	@Test
	fun matchesRenamedDataSourceByConnectorMetadata() {
		val renamed = activity("DS_0_0009_GetNSKeys", "GetNSValuesConnector")
		val unrelated = activity("DS_0_1010_GetAgentsList", "AgentSearchConnector")

		val match = TraceActivityMatcher.find("DS_0_00_GetNSValues", listOf(unrelated, renamed))!!

		assertEquals(renamed.reference, match.activity.reference)
		assertFalse(match.exact)
	}

	@Test
	fun doesNotGuessWhenTwoCandidatesAreEquallyPlausible() {
		val first = activity("DS_0_01_GetValues", "GetValuesConnector")
		val second = activity("DS_0_02_GetValues", "GetValuesConnector")

		assertNull(TraceActivityMatcher.find("DS_0_00_GetValues", listOf(first, second)))
	}

	@Test
	fun matchesTheRenamedInitialActivityInTheProvidedDesignerProject() {
		val processDir = System.getenv("TEST_CRIF_PROCESS_DIR")?.let(::File)
		assumeTrue(processDir?.isDirectory == true)
		val loader = ProcessLoader(processDir!!)
		val mainFlow = loader.procedures().first { it.isMainFlow }
		val model = loader.load(mainFlow)

		val match = TraceActivityMatcher.find("DS_0_00_GetNSValues", model.activities)

		assertEquals("DS_0_0009_GetNSKeys", match?.activity?.reference)
	}

	private fun activity(reference: String, connector: String): ProcessActivity {
		val directory = root.resolve(reference).toFile().apply { mkdirs() }
		val xml = """
			<DataSourceActivityDefinition ReferenceName="$reference">
			  <ConnectorName>$connector</ConnectorName>
			</DataSourceActivityDefinition>
		""".trimIndent()
		directory.resolve("Properties.xml").writeBytes(
			byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.toByteArray(StandardCharsets.UTF_16LE),
		)
		return ProcessActivity(
			uid = reference,
			reference = reference,
			type = ActivityType.DATA_SOURCE,
			x = 0.0,
			y = 0.0,
			width = 120.0,
			height = 60.0,
			availableExits = emptyList(),
			procedureToCall = null,
			dir = directory,
			dataDocuments = emptyList(),
		)
	}
}
