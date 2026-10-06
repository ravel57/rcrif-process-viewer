package ru.ravel.rcrifprocessviewer.service

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcessConnection
import ru.ravel.rcrifprocessviewer.dto.Waypoint
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.roundToInt

/**
 * Сохраняет изменяемую часть ProcedureModel обратно в Layout.xml.
 *
 * Исходный XML используется как шаблон, поэтому UID layout'а, Exits, комментарии
 * и неизвестные текущей версии viewer'а элементы не теряются. Координаты всегда
 * сериализуются целыми числами.
 */
object LayoutSaver {

	private const val LAYOUT_FILE = "Layout.xml"
	private const val START_EXIT = "Start"
	private const val ENTER_EXIT = "Enter"

	fun save(
		model: ProcedureModel,
		renderedWaypoints: Map<ProcessConnection, List<Waypoint>> = emptyMap(),
		renderedActivitySizes: Map<String, Pair<Int, Int>> = emptyMap(),
	) {
		val layoutFile = File(model.dir, LAYOUT_FILE)
		require(layoutFile.isFile) { "Не найден ${layoutFile.absolutePath}" }

		val document = newDocumentBuilderFactory().newDocumentBuilder().parse(layoutFile)
		document.documentElement.normalize()

		syncElements(document, model, renderedActivitySizes)
		syncConnections(document, model, renderedWaypoints)
		syncStart(document, model)
		writeAtomically(document, layoutFile)
	}

	private fun syncElements(
		document: Document,
		model: ProcedureModel,
		renderedActivitySizes: Map<String, Pair<Int, Int>>,
	) {
		val container = directChild(document.documentElement, "Elements") ?: return
		val byUid = model.activities.associateBy { it.uid }
		val existing = directChildren(container, "DiagramElement").toList()
		for (element in existing) {
			val uid = element.getAttribute("UID")
			val activity = byUid[uid]
			if (activity == null) {
				container.removeChild(element)
				continue
			}
			setChildText(document, element, "X", activity.x.roundToInt().toString())
			setChildText(document, element, "Y", activity.y.roundToInt().toString())
			renderedActivitySizes[uid]?.let { (width, height) ->
				setChildText(document, element, "Width", width.toString())
				setChildText(document, element, "Height", height.toString())
			}
		}

		// Если блок был сохранён как удалённый, а потом возвращён через Undo,
		// его XML-узла в файле уже нет. Восстанавливаем минимальный DiagramElement
		// из модели, чтобы последующее сохранение корректно вернуло блок на диск.
		val existingUids = directChildren(container, "DiagramElement")
			.map { it.getAttribute("UID") }
			.toHashSet()
		for (activity in model.activities) {
			if (activity.uid in existingUids) continue
			val (width, height) = renderedActivitySizes[activity.uid]
				?: (activity.width.roundToInt() to activity.height.roundToInt())
			val element = document.createElement("DiagramElement").apply {
				setAttribute("UID", activity.uid)
			}
			appendTextElement(document, element, "X", activity.x.roundToInt().toString())
			appendTextElement(document, element, "Y", activity.y.roundToInt().toString())
			appendTextElement(document, element, "Width", width.toString())
			appendTextElement(document, element, "Height", height.toString())
			appendTextElement(document, element, "Reference", activity.reference)
			element.appendChild(document.createElement("OutConnectionRefs"))
			element.appendChild(document.createElement("InConnectionRefs"))
			container.appendChild(element)
		}
	}

	private fun syncConnections(
		document: Document,
		model: ProcedureModel,
		renderedWaypoints: Map<ProcessConnection, List<Waypoint>>,
	) {
		val root = document.documentElement
		val container = directChild(root, "Connections") ?: document.createElement("Connections").also {
			val start = directChild(root, "StartElement")
			if (start == null) root.appendChild(it) else root.insertBefore(it, start)
		}

		directChildren(container, "DiagramConnection").toList().forEach(container::removeChild)

		for (connection in model.connections) {
			val fromUid = connection.fromUid ?: continue
			val toUid = connection.toUid ?: continue
			val connectionElement = document.createElement("DiagramConnection").apply {
				setAttribute("UID", connection.uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
			}

			val splits = document.createElement("Splits")
			for (waypoint in renderedWaypoints[connection] ?: connection.waypoints) {
				val split = document.createElement("DiagramSplit")
				appendTextElement(document, split, "X", waypoint.x.roundToInt().toString())
				appendTextElement(document, split, "Y", waypoint.y.roundToInt().toString())
				splits.appendChild(split)
			}
			connectionElement.appendChild(splits)

			val endPoints = document.createElement("EndPoints")
			val source = document.createElement("DiagramEndPoint")
			if (fromUid == model.startUid) {
				source.setAttribute("ExitPointRef", START_EXIT)
			} else {
				source.setAttribute("ElementRef", fromUid)
				connection.exitName?.takeIf { it.isNotBlank() }?.let { source.setAttribute("ExitPointRef", it) }
			}
			endPoints.appendChild(source)

			val target = document.createElement("DiagramEndPoint").apply {
				setAttribute("ElementRef", toUid)
				setAttribute("ExitPointRef", ENTER_EXIT)
			}
			endPoints.appendChild(target)
			connectionElement.appendChild(endPoints)
			container.appendChild(connectionElement)
		}
	}

	private fun syncStart(document: Document, model: ProcedureModel) {
		val start = model.start ?: return
		val element = directChild(document.documentElement, "StartElement") ?: return
		setChildText(document, element, "X", start.x.roundToInt().toString())
		setChildText(document, element, "Y", start.y.roundToInt().toString())
	}

	private fun setChildText(document: Document, parent: Element, name: String, value: String) {
		val child = directChild(parent, name) ?: document.createElement(name).also(parent::appendChild)
		child.textContent = value
	}

	private fun appendTextElement(document: Document, parent: Element, name: String, value: String) {
		parent.appendChild(document.createElement(name).apply { textContent = value })
	}

	private fun directChild(parent: Element, name: String): Element? =
		directChildren(parent, name).firstOrNull()

	private fun directChildren(parent: Element, name: String): Sequence<Element> = sequence {
		var node: Node? = parent.firstChild
		while (node != null) {
			if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) {
				yield(node as Element)
			}
			node = node.nextSibling
		}
	}

	private fun writeAtomically(document: Document, target: File) {
		val temp = File(target.parentFile, ".${target.name}.tmp")
		val transformerFactory = TransformerFactory.newInstance().apply {
			setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
		}
		val transformer = transformerFactory.newTransformer().apply {
			setOutputProperty(OutputKeys.ENCODING, "UTF-16")
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
		}
		transformer.transform(DOMSource(document), StreamResult(temp))
		try {
			Files.move(
				temp.toPath(),
				target.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE,
			)
		} catch (_: Exception) {
			Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
		DocumentBuilderFactory.newInstance().apply {
			isNamespaceAware = false
			setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			setFeature("http://xml.org/sax/features/external-general-entities", false)
			setFeature("http://xml.org/sax/features/external-parameter-entities", false)
			setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
			setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
			setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
		}
}
