package ru.ravel.rcrifprocessviewer.debug

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugSocketServerTest {

	/** Строки сняты с настоящего DebugBridge из xslt-sandbox. */
	private val xsltStepLine =
		"""{"type":"step","procedure":"MainFlow","activity":"DM_0_05_SetProcessParameters","mode":"XSLT","exit":null,"next":"DS_0_0001_GetAvgCarPrice","docsIn":"<Data>\n  <SystemData Имя=\"ё\">1</SystemData>\n</Data>","docsOut":"<Data><Out/></Data>"}"""
	private val ruleStepLine =
		"""{"type":"step","procedure":"PR_03_S1Call","activity":"BR_03_01_NeedCheckERC","mode":"BR","exit":"False","next":null,"docsIn":"<Data/>","docsOut":null}"""

	private fun server(received: MutableList<DebugStep>, latch: CountDownLatch? = null) =
		DebugSocketServer { step ->
			received += step
			latch?.countDown()
		}

	@Test
	fun `parses lines exactly as the sandbox sends them`() {
		val server = server(CopyOnWriteArrayList())

		val xslt = assertNotNull(server.parse(xsltStepLine))
		assertEquals("MainFlow", xslt.procedure)
		assertEquals("DM_0_05_SetProcessParameters", xslt.activity)
		assertNull(xslt.exit)
		assertEquals("DS_0_0001_GetAvgCarPrice", xslt.next)
		assertEquals("<Data>\n  <SystemData Имя=\"ё\">1</SystemData>\n</Data>", xslt.docsIn)
		assertEquals("<Data><Out/></Data>", xslt.docsOut)

		val rule = assertNotNull(server.parse(ruleStepLine))
		assertEquals("False", rule.exit)
		assertNull(rule.next)
		assertNull(rule.docsOut)
	}

	@Test
	fun `ignores lines that are not steps`() {
		val server = server(CopyOnWriteArrayList())

		assertNull(server.parse("not json"))
		assertNull(server.parse("""{"type":"hello"}"""))
		assertNull(server.parse("""{"type":"step","procedure":"MainFlow"}"""))
		assertNull(server.parse("""{"type":"step","procedure":" ","activity":"X"}"""))
	}

	@Test
	fun `listens on loopback in the dynamic port range and receives steps from several clients`() {
		val received = CopyOnWriteArrayList<DebugStep>()
		val latch = CountDownLatch(3)
		server(received, latch).use { server ->
			val port = server.ensureStarted()

			assertTrue(port in DebugSocketServer.FIRST_PORT..DebugSocketServer.LAST_PORT, "port $port")
			assertEquals(port, server.ensureStarted())

			val loopback = InetAddress.getByName("127.0.0.1")
			Socket(loopback, port).use { first ->
				first.getOutputStream().write("$xsltStepLine\ngarbage\n$ruleStepLine\n".toByteArray(Charsets.UTF_8))
				first.getOutputStream().flush()
				Socket(loopback, port).use { second ->
					second.getOutputStream().write("$ruleStepLine\n".toByteArray(Charsets.UTF_8))
					second.getOutputStream().flush()
					assertTrue(latch.await(5, TimeUnit.SECONDS), "got ${received.size} steps")
				}
			}
		}

		assertEquals(3, received.size)
		assertEquals(2, received.count { it.activity == "BR_03_01_NeedCheckERC" })
		assertEquals("Имя=\"ё\"", Regex("""Имя="ё"""").find(received.first { it.mode == "XSLT" }.docsIn!!)!!.value)
	}

	@Test
	fun `each start picks a free port of its own`() {
		val first = server(CopyOnWriteArrayList())
		val second = server(CopyOnWriteArrayList())
		first.use { a ->
			second.use { b ->
				assertTrue(a.ensureStarted() != b.ensureStarted())
			}
		}
	}
}
