package ru.ravel.rcrifprocessviewer.service

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcedureRef
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.dto.ProcessConnection
import ru.ravel.rcrifprocessviewer.dto.Waypoint
import ru.ravel.rcrifprocessviewer.model.activity.ActivityProperties
import ru.ravel.rcrifprocessviewer.model.activity.ReferredDocument
import ru.ravel.rcrifprocessviewer.model.layout.DiagramLayout
import ru.ravel.rcrifprocessviewer.util.XmlReader
import java.io.File

/**
 * Чтение кредитного процесса из указанной папки.
 *
 * П.1 ТЗ: источник — файловая система, никакого git. Ожидаемая структура папки:
 *
 *   <корень процесса>/
 *     MainFlow/Layout.xml
 *     MainFlow/<Активность>/Properties.xml
 *     Procedures/<Процедура>/Layout.xml
 *     Procedures/<Процедура>/<Активность>/Properties.xml
 */
class ProcessLoader(val root: File) {

	private val mapper = XmlMapper()

	/** Кэш метаданных активностей: папка процедуры -> (ReferenceName -> метаданные). */
	private val metaCache = mutableMapOf<String, Map<String, ActivityMeta>>()

	/**
	 * Список процедур, доступных в процессе (п.9 ТЗ).
	 * MainFlow всегда первый, остальные — по алфавиту.
	 */
	fun procedures(): List<ProcedureRef> {
		val result = mutableListOf<ProcedureRef>()

		val mainFlow = File(root, MAIN_FLOW)
		if (File(mainFlow, LAYOUT_FILE).isFile) {
			result += ProcedureRef(MAIN_FLOW, mainFlow, isMainFlow = true)
		}

		File(root, PROCEDURES).listFiles()
			?.filter { it.isDirectory && File(it, LAYOUT_FILE).isFile }
			?.sortedBy { it.name.lowercase() }
			?.forEach { result += ProcedureRef(it.name, it, isMainFlow = false) }

		return result
	}

	/** Признак того, что выбранная папка похожа на корень кредитного процесса. */
	fun looksLikeProcessRoot(): Boolean =
		File(root, MAIN_FLOW).isDirectory || File(root, PROCEDURES).isDirectory

	/** Разбор Layout.xml процедуры вместе со свойствами её активностей. */
	fun load(ref: ProcedureRef): ProcedureModel {
		val layoutFile = File(ref.dir, LAYOUT_FILE)
		val layout = mapper.readValue(XmlReader.readXmlSafe(layoutFile), DiagramLayout::class.java)
		val meta = activityMeta(ref.dir)
		val references = layout.elements?.diagramElements.orEmpty().mapNotNull { it.reference }
		val availableExits = ActivityExitResolver.resolve(
			processRoot = root,
			propertiesXml = meta.values.map { it.xml },
			knownReferences = references,
		)

		val activities = layout.elements?.diagramElements.orEmpty().mapNotNull { element ->
			val uid = element.uid ?: return@mapNotNull null
			val reference = element.reference ?: return@mapNotNull null
			val activityMeta = meta[reference]
			ProcessActivity(
				uid = uid,
				reference = reference,
				type = activityMeta?.type ?: ActivityType.UNKNOWN,
				x = (element.x ?: 0).toDouble(),
				y = (element.y ?: 0).toDouble(),
				width = (element.width ?: DEFAULT_WIDTH).toDouble().coerceAtLeast(MIN_SIZE),
				height = (element.height ?: DEFAULT_HEIGHT).toDouble().coerceAtLeast(MIN_SIZE),
				availableExits = availableExits[reference].orEmpty(),
				procedureToCall = activityMeta?.procedureToCall,
				dir = activityMeta?.dir,
				dataDocuments = activityMeta?.documents.orEmpty(),
				returnExit = activityMeta?.returnExit,
			)
		}.toMutableList()

		val connections = layout.connections?.diagramConnections.orEmpty().mapNotNull { connection ->
			val points = connection.endPoints?.points.orEmpty()
			if (points.size < 2) {
				return@mapNotNull null
			}
			// У приёмника ExitPointRef обычно равен Enter, у источника — имя выхода
			// (либо Start). Поэтому нельзя искать просто «непустой ExitPointRef»:
			// он заполнен у обоих концов.
			val sourceIndex = points.indexOfFirst {
				!it.exitPointRef.isNullOrBlank() && !it.exitPointRef.equals(ENTER_EXIT, ignoreCase = true)
			}.takeIf { it >= 0 } ?: 0
			val targetIndex = points.indices.firstOrNull { it != sourceIndex } ?: return@mapNotNull null
			val sourcePoint = points[sourceIndex]
			val sourceUid = sourcePoint.elementRef ?: if (
				sourcePoint.exitPointRef.equals(START_EXIT, ignoreCase = true)
			) {
				layout.startElement?.uid
			} else {
				null
			}
			ProcessConnection(
				uid = connection.uid,
				fromUid = sourceUid,
				toUid = points[targetIndex].elementRef,
				exitName = sourcePoint.exitPointRef,
				waypoints = connection.splits?.splits.orEmpty()
					.map { Waypoint(it.x.toDouble(), it.y.toDouble()) }
					.toMutableList(),
			)
		}.toMutableList()

		val start = layout.startElement?.let { Waypoint((it.x ?: 0).toDouble(), (it.y ?: 0).toDouble()) }

		return ProcedureModel(
			name = ref.name,
			dir = ref.dir,
			activities = activities,
			connections = connections,
			start = start,
			startUid = layout.startElement?.uid,
		)
	}

	/**
	 * Метаданные всех активностей процедуры: тип и список дата-документов
	 * из Properties.xml. Ключ — ReferenceName, именно он стоит в Layout.xml.
	 */
	private fun activityMeta(procedureDir: File): Map<String, ActivityMeta> {
		return metaCache.getOrPut(procedureDir.absolutePath) {
			procedureDir.listFiles().orEmpty()
				.filter { it.isDirectory }
				.mapNotNull { activityDir ->
					val properties = File(activityDir, PROPERTIES_FILE)
					if (!properties.isFile) {
						return@mapNotNull null
					}
					val xml = XmlReader.readXmlSafe(properties)
					val parsed = runCatching { mapper.readValue(xml, ActivityProperties::class.java) }.getOrNull()
					val reference = parsed?.referenceName?.takeIf { it.isNotBlank() } ?: activityDir.name
					val type = detectType(xml)
					reference to ActivityMeta(
						dir = activityDir,
						type = type,
						documents = parsed?.referredDocuments?.documents.orEmpty(),
						procedureToCall = if (type == ActivityType.PROCEDURE_CALL) {
							ActivityExitResolver.procedureToCall(xml)
						} else {
							null
						},
						returnExit = if (type == ActivityType.PROCEDURE_RETURN) returnExitOf(xml) else null,
						xml = xml,
					)
				}
				.toMap()
		}
	}

	/** ConnectionID блока ProcedureReturn: куда вернётся вызывающий блок. Пустой — Completed. */
	private fun returnExitOf(xml: String): String =
		CONNECTION_ID_REGEX.find(xml)?.groupValues?.get(1)?.trim().orEmpty()

	/** Тип активности определяется по имени корневого элемента Properties.xml. */
	private fun detectType(xml: String): ActivityType {
		val tag = ROOT_TAG_REGEX.find(xml)?.groupValues?.get(1) ?: return ActivityType.UNKNOWN
		return when (tag) {
			"BizRuleActivityDefinition" -> ActivityType.BIZ_RULE
			"DataSourceActivityDefinition" -> ActivityType.DATA_SOURCE
			"DispatchActivityDefinition" -> ActivityType.DISPATCH
			"FormActivityDefinition" -> ActivityType.FORM
			"MappingActivityDefinition" -> ActivityType.DATA_MAPPING
			"ProcedureCallActivityDefinition" -> ActivityType.PROCEDURE_CALL
			"SegmentationTreeActivityDefinition" -> ActivityType.SEGMENTATION_TREE
			"SetValueActivityDefinition" -> ActivityType.SET_VALUE
			"WaitActivityDefinition" -> ActivityType.WAIT
			"ProcedureReturnActivityDefinition" -> ActivityType.PROCEDURE_RETURN
			"EndProcessActivityDefinition" -> ActivityType.END_PROCEDURE
			"SendEMailActivityDefinition" -> ActivityType.SEND_EMAIL
			"PhaseActivityDefinition" -> ActivityType.SET_PHASE
			else -> ActivityType.UNKNOWN
		}
	}

	private data class ActivityMeta(
		val dir: File,
		val type: ActivityType,
		val documents: List<ReferredDocument>,
		val procedureToCall: String?,
		val returnExit: String?,
		val xml: String,
	)

	private companion object {
		const val MAIN_FLOW = "MainFlow"
		const val PROCEDURES = "Procedures"
		const val LAYOUT_FILE = "Layout.xml"
		const val PROPERTIES_FILE = "Properties.xml"
		const val START_EXIT = "Start"
		const val ENTER_EXIT = "Enter"

		const val DEFAULT_WIDTH = 120
		const val DEFAULT_HEIGHT = 60
		const val MIN_SIZE = 24.0

		/** Первый настоящий тег: `<?xml ...` и `<!-- ... -->` под шаблон не попадают. */
		val ROOT_TAG_REGEX = Regex("""<\s*([A-Za-z][A-Za-z0-9_.\-]*)""")

		val CONNECTION_ID_REGEX = Regex("""<ConnectionID>([^<]*)</ConnectionID>""")
	}
}
