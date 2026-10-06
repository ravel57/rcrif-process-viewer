package ru.ravel.rcrifprocessviewer.debug

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import ru.ravel.rcrifprocessviewer.db.ActivityCall
import ru.ravel.rcrifprocessviewer.db.ActivityPassRepository
import ru.ravel.rcrifprocessviewer.db.DataDocumentValue
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Репозиторий, который к настоящим вызовам из БД добавляет вызовы, пришедшие из отладчика
 * xslt-sandbox, — окно дата-документов показывает их отдельными вкладками «Отладка N».
 */
class DebugCallsRepository(
	private val delegate: ActivityPassRepository,
	private val steps: DebugSteps,
) : ActivityPassRepository by delegate {

	override fun loadActivityCalls(
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
	): List<ActivityCall> {
		val real = runCatching { delegate.loadActivityCalls(requestNumber, procedureName, activity) }
			.getOrDefault(emptyList())
		val debug = steps.stepsFor(procedureName, activity.reference)
		return real + debug.mapIndexed { number, step ->
			ActivityCall(
				index = real.size + number + 1,
				startedAt = step.receivedAt.toString(),
				finishedAt = step.receivedAt.toString(),
				exitName = step.exit,
				documentsOnStart = DebugDocuments.parse(step.docsIn, "Вход"),
				documentsOnExit = DebugDocuments.parse(step.docsOut, "Выход"),
				label = "Отладка ${number + 1}",
			)
		}
	}
}

/** Разбор XML с дата-документами из sandbox в строки таблицы окна дата-документов. */
object DebugDocuments {

	/** Каждый дочерний элемент `<Data>` (или сам корень, если обёртки нет) — отдельный дата-документ. */
	fun parse(xml: String?, access: String): List<DataDocumentValue> {
		val text = xml?.trim().orEmpty()
		if (text.isEmpty()) return emptyList()
		val root = runCatching { documentElement(text) }.getOrNull()
			?: return listOf(DataDocumentValue("Data", access, text, null))

		val documents = if (root.tagName.equals("Data", ignoreCase = true)) elementChildren(root) else listOf(root)
		return documents.map { DataDocumentValue(it.tagName, access, serialize(it), null) }
	}

	private fun documentElement(xml: String): Element {
		val factory = DocumentBuilderFactory.newInstance().apply {
			setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
			setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
		}
		return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
	}

	private fun elementChildren(parent: Element): List<Element> {
		val result = ArrayList<Element>()
		var child: Node? = parent.firstChild
		while (child != null) {
			if (child is Element) result += child
			child = child.nextSibling
		}
		return result
	}

	private fun serialize(element: Element): String {
		val transformer = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
			setOutputProperty(OutputKeys.INDENT, "yes")
		}
		return StringWriter().also { transformer.transform(DOMSource(element), StreamResult(it)) }.toString().trim()
	}
}
