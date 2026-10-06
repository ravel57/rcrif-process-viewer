package ru.ravel.rcrifprocessviewer.service

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import ru.ravel.rcrifprocessviewer.util.XmlReader
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Восстанавливает полный набор выходов активности по тем же правилам,
 * которые используются в r-crif-layout-merger.
 *
 * Для локального viewer не нужен git: Properties.xml читаются напрямую из
 * выбранной процедуры, а выходы ProcedureCall — из Layout.xml вызываемой
 * процедуры в каталоге Procedures.
 */
object ActivityExitResolver {

	/** ProcedureToCall из Properties.xml. Для остальных типов возвращает null. */
	fun procedureToCall(xml: String): String? = runCatching {
		val root = parse(xml).documentElement ?: return@runCatching null
		if (normalizedName(root) != "procedurecallactivitydefinition") {
			return@runCatching null
		}
		descendants(root)
			.firstOrNull { normalizedName(it) == "proceduretocall" }
			?.textContent
			?.trim()
			?.takeIf(String::isNotEmpty)
	}.getOrNull()

	fun resolve(
		processRoot: File,
		propertiesXml: Collection<String>,
		knownReferences: Collection<String>,
	): Map<String, List<String>> {
		val calledProcedureExitCache = hashMapOf<String, List<String>>()

		fun calledProcedureExits(procedureName: String): List<String> {
			return calledProcedureExitCache.getOrPut(procedureName.lowercase()) {
				val proceduresRoot = File(processRoot, PROCEDURES_DIRECTORY)
				val calledDir = proceduresRoot.listFiles().orEmpty().firstOrNull {
					it.isDirectory && it.name.equals(procedureName, ignoreCase = true)
				} ?: return@getOrPut emptyList()
				val layoutFile = File(calledDir, LAYOUT_FILE)
				if (!layoutFile.isFile) {
					return@getOrPut emptyList()
				}
				runCatching { parseProcedureExits(XmlReader.readXmlSafe(layoutFile)) }
					.getOrDefault(emptyList())
			}
		}

		val result = linkedMapOf<String, List<String>>()
		for (xml in propertiesXml) {
			val definition = runCatching {
				parseDefinition(xml, ::calledProcedureExits)
			}.getOrNull() ?: continue
			result[definition.reference] = definition.exits
		}

		// Как в merger: если Properties.xml нет/не распознан, пробуем стандартные
		// выходы по префиксу Reference. Для распознанного пустого списка ничего не
		// добавляем — у ProcedureReturn/EndProcess выходов действительно нет.
		for (reference in knownReferences) {
			if (!result.containsKey(reference)) {
				result[reference] = defaultExitsForReference(reference)
			}
		}

		return result
	}

	private fun parseDefinition(
		xml: String,
		calledProcedureExits: (String) -> List<String>,
	): ActivityDefinition? {
		val document = parse(xml)
		val root = document.documentElement ?: return null
		val reference = root.attributeValue("ReferenceName", "Reference")
			?.trim()
			?.takeIf(String::isNotEmpty)
			?: return null

		val exits = when (normalizedName(root)) {
			"formactivitydefinition" -> descendants(root)
				.filter { normalizedName(it) == "activitycommand" }
				.filterNot { it.attributeValue("Class").equals("BuiltIn", ignoreCase = true) }
				.mapNotNull { it.attributeValue("Value") }

			"bizruleactivitydefinition" -> BIZ_RULE_EXITS

			"segmentationtreeactivitydefinition" -> descendants(root)
				.filter { normalizedName(it) == "connectionid" }
				.mapNotNull { it.textContent?.trim() } + SEGMENTATION_TREE_EXITS

			"datasourceactivitydefinition" -> DATA_SOURCE_EXITS
			"mappingactivitydefinition" -> DATA_MAPPING_EXITS
			"setvalueactivitydefinition" -> SET_VALUE_EXITS

			"procedurecallactivitydefinition" -> {
				val procedureName = descendants(root)
					.firstOrNull { normalizedName(it) == "proceduretocall" }
					?.textContent
					?.trim()
					?.takeIf(String::isNotEmpty)
				procedureName?.let(calledProcedureExits).orEmpty()
			}

			"dispatchactivitydefinition" -> DISPATCH_EXITS
			"waitactivitydefinition" -> WAIT_EXITS
			"procedurereturnactivitydefinition" -> emptyList()
			"endprocessactivitydefinition" -> emptyList()
			"sendemailactivitydefinition" -> SEND_EMAIL_EXITS
			"phaseactivitydefinition" -> SET_PHASE_EXITS
			else -> return null
		}

		return ActivityDefinition(reference, exits.normalizedExits())
	}

	private fun parseProcedureExits(xml: String): List<String> {
		val document = parse(xml)
		val root = document.documentElement ?: return emptyList()
		val exitsElement = directChildren(root).firstOrNull {
			normalizedName(it) == "exits"
		} ?: return emptyList()

		return directChildren(exitsElement)
			.mapNotNull { it.textContent?.trim() }
			.normalizedExits()
	}

	private fun defaultExitsForReference(reference: String): List<String> =
		when (reference.substringBefore('_').uppercase()) {
			"BR" -> BIZ_RULE_EXITS
			"ST" -> SEGMENTATION_TREE_EXITS
			"DS" -> DATA_SOURCE_EXITS
			"DM" -> DATA_MAPPING_EXITS
			"SV" -> SET_VALUE_EXITS
			"PC" -> PROCEDURE_CALL_FALLBACK_EXITS
			"DR" -> DISPATCH_EXITS
			"WA", "WT" -> WAIT_EXITS
			"SE", "EM" -> SEND_EMAIL_EXITS
			"SP", "PH" -> SET_PHASE_EXITS
			else -> emptyList()
		}

	private fun parse(xml: String): Document {
		val factory = DocumentBuilderFactory.newInstance().apply {
			isNamespaceAware = true
			isXIncludeAware = false
			isExpandEntityReferences = false
			runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
			runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
			runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
			runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
			runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
		}
		return factory.newDocumentBuilder().parse(
			InputSource(StringReader(xml.removePrefix("\uFEFF")))
		)
	}

	private fun Element.attributeValue(vararg names: String): String? {
		val accepted = names.map { it.lowercase() }.toSet()
		for (index in 0 until attributes.length) {
			val attribute = attributes.item(index)
			if (attribute.nodeName.substringAfter(':').lowercase() in accepted) {
				return attribute.nodeValue
			}
		}
		return null
	}

	private fun descendants(element: Element): List<Element> {
		val nodes = element.getElementsByTagName("*")
		return buildList(nodes.length) {
			for (index in 0 until nodes.length) {
				(nodes.item(index) as? Element)?.let(::add)
			}
		}
	}

	private fun directChildren(element: Element): List<Element> {
		val result = arrayListOf<Element>()
		val nodes = element.childNodes
		for (index in 0 until nodes.length) {
			(nodes.item(index) as? Element)?.let(result::add)
		}
		return result
	}

	private fun normalizedName(node: Node): String =
		(node.localName ?: node.nodeName.substringAfter(':'))
			.lowercase()
			.replace("_", "")
			.replace("-", "")

	private fun List<String>.normalizedExits(): List<String> = asSequence()
		.map(String::trim)
		.filter(String::isNotEmpty)
		.distinct()
		.toList()

	private data class ActivityDefinition(
		val reference: String,
		val exits: List<String>,
	)

	private const val PROCEDURES_DIRECTORY = "Procedures"
	private const val LAYOUT_FILE = "Layout.xml"

	private val BIZ_RULE_EXITS = listOf("True", "False", "Failed")
	private val SEGMENTATION_TREE_EXITS = listOf("AllFalse", "Failed")
	private val DATA_SOURCE_EXITS = listOf("Empty", "Completed", "Unavailable", "Timeout", "Failed")
	private val DATA_MAPPING_EXITS = listOf("Completed", "Failed")
	private val SET_VALUE_EXITS = listOf("Completed", "Failed")
	private val PROCEDURE_CALL_FALLBACK_EXITS = listOf("Completed")
	private val DISPATCH_EXITS = listOf("Completed", "Unassigned", "Failed")
	private val WAIT_EXITS = listOf("Updated", "UserUnfreeze", "ExitTimeout")
	private val SEND_EMAIL_EXITS = listOf("Completed", "Failed")
	private val SET_PHASE_EXITS = listOf("Completed", "Failed")
}
