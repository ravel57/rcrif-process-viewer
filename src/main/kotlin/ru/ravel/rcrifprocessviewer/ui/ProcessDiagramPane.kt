package ru.ravel.rcrifprocessviewer.ui

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Polygon
import javafx.scene.shape.Polyline
import javafx.scene.shape.Rectangle
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.text.TextAlignment
import javafx.scene.transform.Scale
import ru.ravel.rcrifprocessviewer.db.ProcessTraceEvent
import ru.ravel.rcrifprocessviewer.db.ActivityCall
import ru.ravel.rcrifprocessviewer.debug.DebugSteps
import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.dto.ProcessConnection
import ru.ravel.rcrifprocessviewer.dto.Waypoint
import ru.ravel.rcrifprocessviewer.service.XsltSandbox
import ru.ravel.rcrifprocessviewer.service.XsltSandboxTarget
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Отрисовка схемы процедуры по координатам из Layout.xml.
 *
 * Отвечает за:
 *  - п.1: перетаскивание блоков и стрелок удержанием ЛКМ;
 *  - п.2: выбор блока/стрелки с чёрной рамкой;
 *  - п.3: размеры блоков и трассировка стрелок из [DiagramGeometry];
 *  - п.4/п.5(исх.): кружок-счётчик прохождений и сменная схема обводки;
 *  - п.5: отмена/повтор перемещений через [undoManager];
 *  - п.6: мультивыбор рамкой по пустому полю;
 *  - п.7: цвет блока по типу активности ([ActivityPalette]);
 *  - п.8: в сцену попадают только элементы, видимые в текущем окне просмотра.
 */
class ProcessDiagramPane(
	private val onCounterDoubleClick: (ProcessActivity) -> Unit,
) : Pane() {

	/** Схема обводки блоков. Меняется без правки отрисовки — см. п.3 исходного ТЗ. */
	var outlineScheme: ActivityOutlineScheme = PassedActivityOutlineScheme()
		set(value) {
			field = value
			invalidate()
		}

	val undoManager = UndoManager()

	/** Уведомление о смене выделения: (блоков, стрелок). */
	var onSelectionChanged: ((Int, Int) -> Unit)? = null

	/**
	 * П.2 ТЗ: переход к соседнему блоку из контекстного меню.
	 * Обработчик обычно выделяет блок и доскролливает до него.
	 */
	var onNavigateToActivity: ((ProcessActivity) -> Unit)? = null

	/** Двойной клик по ProcedureCall открывает вызываемую процедуру. */
	var onProcedureCallDoubleClick: ((ProcessActivity) -> Unit)? = null

	/** Подтверждение удаления блоков/стрелок. Возвращает true, если удалять. */
	var onDeleteConfirmationRequest: ((String) -> Boolean)? = null

	/** Вызовы активности из трейса заявки: (имя процедуры, активность) -> вызовы по порядку. */
	var activityCallsProvider: ((String, ProcessActivity) -> List<ActivityCall>)? = null

	/** Выбран пункт "Открыть в XSLT-sandbox": (активность, вызов, какой файл открыть). */
	var onOpenInXsltSandbox: ((ProcessActivity, ActivityCall, XsltSandboxTarget) -> Unit)? = null

	private val zoom = Scale(1.0, 1.0)

	private val connectionLayer = Group()
	private val activityLayer = Group()
	private val overlayLayer = Group()

	/** Предпросмотр новой стрелки рисуется в слое соединений, то есть всегда под блоками. */
	private val connectionDraftLine = Polyline().apply {
		fill = Color.TRANSPARENT
		stroke = Color.web("#5f6368")
		strokeLineCap = StrokeLineCap.ROUND
		strokeLineJoin = StrokeLineJoin.ROUND
		isMouseTransparent = true
		isVisible = false
	}
	private val connectionDraftArrow = Polygon().apply {
		fill = Color.web("#5f6368")
		stroke = Color.web("#5f6368")
		isMouseTransparent = true
		isVisible = false
	}
	private val connectionDraftView = Group(connectionDraftLine, connectionDraftArrow).apply {
		isMouseTransparent = true
	}


	private var model: ProcedureModel? = null
	private var passCounts: Map<String, Int> = emptyMap()
	private var traceEvents: List<ProcessTraceEvent> = emptyList()
	private var activityTimings: Map<String, ActivityTiming> = emptyMap()
	private var connectionTraceColors: Map<ProcessConnection, Color> = emptyMap()

	/** Ключи (процедура/активность, см. [DebugSteps.keyOf]) активностей, выполненных в отладчике. */
	private var debugMarks: Set<String> = emptySet()

	private var originX = 0.0
	private var originY = 0.0

	private val activityViews = LinkedHashMap<String, ActivityView>()
	/**
	 * ProcessConnection — data class с MutableList<Waypoint>, поэтому его hashCode
	 * меняется при drag стрелки. Обычный HashMap после первого движения терял ключ
	 * и каждый кадр создавал новый ConnectionView — именно отсюда были «следы» из
	 * десятков старых линий. Для изменяемых model-объектов используем identity.
	 */
	private val connectionViews = IdentityHashMap<ProcessConnection, ConnectionView>()
	/**
	 * После перемещения блока его инцидентные стрелки остаются на автоматически
	 * рассчитанном безопасном route, а не прыгают обратно к старым DiagramSplit.
	 */
	private val autoRoutedConnections: MutableSet<ProcessConnection> =
		Collections.newSetFromMap(IdentityHashMap())
	private var startView: StackPane? = null
	private var activitiesByUid: Map<String, ProcessActivity> = emptyMap()

	/** Renderer-only геометрия выходов, повторяющая r-crif-layout-merger. */
	private var blockHeightsByUid: Map<String, Double> = emptyMap()
	private var outputPortsByUid: Map<String, List<OutputPortPosition>> = emptyMap()

	private val selectedActivities: MutableSet<ProcessActivity> =
		Collections.newSetFromMap(IdentityHashMap())
	private val selectedConnections: MutableSet<ProcessConnection> =
		Collections.newSetFromMap(IdentityHashMap())

	/** Пока ScrollPane не сообщил реальные размеры, строим только разумный минимум. */
	private var viewport: DiagramGeometry.Rect? = DiagramGeometry.Rect(0.0, 0.0, 1600.0, 900.0)

	private var contextMenu: ContextMenu? = null

	private var rebuilding = false
	private var rebuildPending = false

	/** Обрезка по размеру схемы: держит границы панели независимыми от отсечения. */
	private val contentClip = Rectangle()

	/* --------------------------------------------------- состояние перетаскивания */

	private var dragging = false
	/** Реальное перемещение начинается только после небольшого screen-space порога. */
	private var dragMoved = false
	private var dragStartX = 0.0
	private var dragStartY = 0.0
	private val dragActivityOrigin = IdentityHashMap<ProcessActivity, DiagramGeometry.Point>()
	private val dragWaypointOrigin = IdentityHashMap<Waypoint, DiagramGeometry.Point>()
	private val dragAutoRoutedOrigin: MutableSet<ProcessConnection> =
		Collections.newSetFromMap(IdentityHashMap())

	private data class ConnectionDraft(
		val sourceUid: String,
		val sourceActivity: ProcessActivity?,
		val exitReference: String?,
		val sourceAnchor: DiagramGeometry.Point,
	)

	private var connectionDraft: ConnectionDraft? = null

	private val rubberBand = Rectangle().apply {
		fill = Color.web("#1a73e8", 0.12)
		stroke = Color.web("#1a73e8")
		strokeWidth = 1.0
		isVisible = false
		isMouseTransparent = true
	}
	private var rubberActive = false
	private var rubberStartX = 0.0
	private var rubberStartY = 0.0

	/** Размер схемы без учёта масштаба — нужен для «вписать в окно». */
	var contentWidth: Double = 0.0
		private set
	var contentHeight: Double = 0.0
		private set

	init {
		transforms.add(zoom)
		background = Background(BackgroundFill(Color.WHITE, null, null))
		// Чтобы клик по пустому месту схемы гарантированно доходил до панели.
		isPickOnBounds = true
		isFocusTraversable = true
		clip = contentClip
		children.addAll(connectionLayer, activityLayer, overlayLayer)
		connectionLayer.children.add(connectionDraftView)
		overlayLayer.children.add(rubberBand)
		// Как в merger: любой левый клик по схеме закрывает контекстное меню.
		addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
			if (event.button == MouseButton.PRIMARY) dismissContextMenu()
		}
		addEventFilter(KeyEvent.KEY_PRESSED) { event ->
			if (event.code == KeyCode.DELETE || event.code == KeyCode.BACK_SPACE) {
				if (selectedActivities.isNotEmpty() || selectedConnections.isNotEmpty()) {
					deleteSelectionWithConfirmation()
					event.consume()
				}
			}
		}
		installFieldHandlers()
	}

	var zoomFactor: Double
		get() = zoom.x
		set(value) {
			val clamped = value.coerceIn(DiagramStyle.MIN_ZOOM, DiagramStyle.MAX_ZOOM)
			if (clamped != zoom.x) {
				zoom.x = clamped
				zoom.y = clamped
				invalidate()
			}
		}

	/* ------------------------------------------------------------------ модель */

	fun render(model: ProcedureModel?, passCounts: Map<String, Int>) {
		this.model = model
		this.passCounts = passCounts
		traceEvents = emptyList()
		activityTimings = emptyMap()
		activitiesByUid = model?.activities?.associateBy { it.uid }.orEmpty()
		rebuildConnectionTraceColors()
		clearSelection()
		undoManager.clear()
		cancelConnectionDraft()
		dropAllViews()

		if (model == null || model.activities.isEmpty()) {
			originX = 0.0
			originY = 0.0
			blockHeightsByUid = emptyMap()
			outputPortsByUid = emptyMap()
			activitiesByUid = emptyMap()
			contentWidth = 0.0
			contentHeight = 0.0
			setPrefSize(0.0, 0.0)
			return
		}

		rebuildOutputPortLayoutCache(model)
		recalculateOrigin(model)
		invalidate()
	}

	/** Оранжевая рамка у активностей, которые выполняли отладчиком xslt-sandbox. */
	fun updateDebugMarks(marks: Set<String>) {
		if (marks == debugMarks) return
		debugMarks = marks
		invalidate()
	}

	private fun isDebugged(activity: ProcessActivity): Boolean {
		val procedure = activity.procedureName ?: model?.name ?: return false
		return DebugSteps.keyOf(procedure, activity.reference) in debugMarks
	}

	/**
	 * Обновляет данные прохождения без повторного render(), поэтому zoom/selection/undo
	 * текущей вкладки не сбрасываются.
	 */
	fun updateProcessHistory(
		passCounts: Map<String, Int>,
		traceEvents: List<ProcessTraceEvent>,
	) {
		this.passCounts = passCounts
		this.traceEvents = traceEvents
		rebuildActivityTimings()
		rebuildConnectionTraceColors()
		invalidate()
	}

	/** Последний вызов каждой активности определяет тайминг, показанный на её блоке. */
	private fun rebuildActivityTimings() {
		if (traceEvents.isEmpty()) {
			activityTimings = emptyMap()
			return
		}

		val result = linkedMapOf<String, ActivityTiming>()
		traceEvents.forEach { event ->
			val reference = normalizeTracePart(event.activityReference)
			if (reference.isEmpty()) return@forEach

			val startedAt = traceTimestamp(event.startedAt) ?: return@forEach
			val finishedAt = traceTimestamp(event.finishedAt)
			val durationMillis = finishedAt
				?.takeIf { it >= startedAt }
				?.minus(startedAt)

			result[reference] = if (durationMillis == null) {
				ActivityTiming(
					text = "выполняется",
					tooltip = "Тайминг выполнения: активность выполняется",
				)
			} else {
				val formatted = formatDuration(durationMillis)
				ActivityTiming(
					text = formatted,
					tooltip = "Тайминг выполнения: $formatted",
				)
			}
		}
		activityTimings = result
	}

	private fun formatDuration(durationMillis: Long): String {
		val millis = durationMillis.coerceAtLeast(0L)
		return when {
			millis < 1_000L -> "${millis}мс"
			millis < 60_000L -> "${formatDurationValue(millis / 1_000.0)}с"
			millis < 86_400_000L -> "${formatDurationValue(millis / 3_600_000.0)}ч"
			else -> "${formatDurationValue(millis / 86_400_000.0)}д"
		}
	}

	private fun formatDurationValue(value: Double): String {
		val rounded = String.format(java.util.Locale.ROOT, "%.1f", value)
		return rounded.removeSuffix(".0")
	}

	private fun rebuildConnectionTraceColors() {
		val current = model ?: run {
			connectionTraceColors = emptyMap()
			return
		}
		if (traceEvents.isEmpty()) {
			connectionTraceColors = emptyMap()
			return
		}

		// Храним цвет по identity самого ProcessConnection. ProcessConnection содержит
		// изменяемые waypoints, поэтому его нельзя класть в обычный HashMap: после
		// перетаскивания стрелки меняется hashCode и цвет визуально «пропадает».
		val traversed = IdentityHashMap<ProcessConnection, TracePosition>()
		val activityByReference = current.activities.associateBy {
			normalizeTracePart(it.reference)
		}

		// Start -> первая реально вызванная активность.
		val firstEvent = traceEvents.first()
		val firstActivity = activityByReference[normalizeTracePart(firstEvent.activityReference)]
		if (firstActivity != null) {
			current.connections
				.singleOrNull { connection ->
					connection.fromUid == current.startUid && connection.toUid == firstActivity.uid
				}
				?.let { connection ->
					traversed[connection] = TracePosition(
						order = 0,
						timestampMillis = traceTimestamp(firstEvent.startedAt)
							?: traceTimestamp(firstEvent.finishedAt),
					)
				}
		}

		// Для каждой соседней пары событий ищем конкретную стрелку A -> B по UID
		// блоков. exit_name используется для выбора между несколькими стрелками,
		// ведущими из A в тот же B. Так подсветка не расползается по другим веткам.
		traceEvents.zipWithNext().forEachIndexed { index, (event, nextEvent) ->
			val fromActivity = activityByReference[normalizeTracePart(event.activityReference)]
				?: return@forEachIndexed
			val toActivity = activityByReference[normalizeTracePart(nextEvent.activityReference)]
				?: return@forEachIndexed

			val candidates = current.connections.filter { connection ->
				connection.fromUid == fromActivity.uid && connection.toUid == toActivity.uid
			}
			if (candidates.isEmpty()) return@forEachIndexed

			val exitName = normalizeTracePart(event.exitName)
			val exactCandidates = if (exitName.isEmpty()) {
				candidates
			} else {
				candidates.filter { connection ->
					normalizeTracePart(connection.exitName) == exitName
				}
			}

			// A CALL-type activity's trace event carries no exit name at all (see
			// Interpreter.java — a CALL always just hands off, it has no chosen exit the way
			// e.g. a RULE does), and a MAPPING-type activity's own exit is collapsed to the
			// generic "Next" in the compiled ru-flow package regardless of what the real CRIF
			// connection is actually labelled in the designer (often "Completed"). Either way,
			// when several same-source/same-target connections remain and none names match, we
			// still know for certain A -> B was traversed exactly once — colouring every
			// candidate (e.g. both "Completed" and "Failed") would falsely show a branch that
			// wasn't taken, so pick the single most likely edge instead, preferring "Completed"
			// the same way the trace table's exit-label column resolves this ambiguity.
			val ambiguous = exactCandidates.ifEmpty { candidates }
			val chosen = if (ambiguous.size <= 1) {
				ambiguous
			} else {
				ambiguous.firstOrNull { normalizeTracePart(it.exitName) == "completed" }
					?.let { listOf(it) }
					?: ambiguous
			}

			// Если одна стрелка проходилась несколько раз, остаётся время последнего
			// прохождения — оно и должно определять её текущий цвет.
			val position = TracePosition(
				order = index + 1,
				timestampMillis = traceTimestamp(event.finishedAt)
					?: traceTimestamp(nextEvent.startedAt)
					?: traceTimestamp(event.startedAt),
			)
			chosen.forEach { connection -> traversed[connection] = position }
		}

		if (traversed.isEmpty()) {
			connectionTraceColors = emptyMap()
			return
		}

		val timestamps = traversed.values.mapNotNull { it.timestampMillis }
		val useRealTime = timestamps.size == traversed.size &&
			timestamps.size >= 2 && timestamps.minOrNull() != timestamps.maxOrNull()
		val oldestTime = timestamps.minOrNull() ?: 0L
		val newestTime = timestamps.maxOrNull() ?: oldestTime
		val oldestOrder = traversed.values.minOf { it.order }
		val newestOrder = traversed.values.maxOf { it.order }
		val orderSpan = (newestOrder - oldestOrder).coerceAtLeast(1)

		val colors = IdentityHashMap<ProcessConnection, Color>()
		traversed.forEach { (connection, position) ->
			val gradientPosition = if (useRealTime) {
				(position.timestampMillis!! - oldestTime).toDouble() /
					(newestTime - oldestTime).toDouble()
			} else if (oldestOrder == newestOrder) {
				1.0
			} else {
				(position.order - oldestOrder).toDouble() / orderSpan.toDouble()
			}
			colors[connection] = DiagramStyle.processTraceColor(gradientPosition)
		}
		connectionTraceColors = colors
	}

	private fun normalizeTracePart(value: String?): String =
		value?.trim()?.lowercase().orEmpty()

	private fun traceTimestamp(value: String?): Long? {
		val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
			?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
			?: runCatching {
				LocalDateTime.parse(text, TRACE_DB_TIME_FORMATTER)
					.toInstant(ZoneOffset.UTC)
					.toEpochMilli()
			}.getOrNull()
			?: runCatching {
				LocalDateTime.parse(text)
					.toInstant(ZoneOffset.UTC)
					.toEpochMilli()
			}.getOrNull()
	}

	/**
	 * Возвращает фактически видимые маршруты стрелок для записи в Layout.xml.
	 * Это важно для автоматически перестроенных стрелок: их старые DiagramSplit
	 * не должны возвращаться после сохранения и повторного открытия layout'а.
	 */
	fun connectionWaypointsForSave(): Map<ProcessConnection, List<Waypoint>> {
		val current = model ?: return emptyMap()
		val boxes = HashMap<String, DiagramGeometry.Rect>(current.activities.size + 1)
		current.activities.forEach { activity -> boxes[activity.uid] = rectOf(activity) }
		startRect()?.let { rect -> current.startUid?.let { boxes[it] = rect } }

		val result = IdentityHashMap<ProcessConnection, List<Waypoint>>()
		current.connections.forEach { connection ->
			val from = boxes[connection.fromUid] ?: return@forEach
			val to = boxes[connection.toUid] ?: return@forEach
			val route = routeFor(connection, from, to, boxes.values)
			result[connection] = route
				.drop(1)
				.dropLast(1)
				.map { point ->
					Waypoint(
						x = DiagramGeometry.snap(point.x + originX - DiagramStyle.PADDING),
						y = DiagramGeometry.snap(point.y + originY - DiagramStyle.PADDING),
					)
				}
		}
		return result
	}

	/** Фактические размеры блоков, которые сейчас отрисованы. */
	fun activitySizesForSave(): Map<String, Pair<Int, Int>> =
		model?.activities.orEmpty().associate { activity ->
			activity.uid to (DiagramGeometry.BLOCK_WIDTH.toInt() to blockHeight(activity).toInt())
		}

	fun selectionCounts(): Pair<Int, Int> = selectedActivities.size to selectedConnections.size

	fun clearSelection() {
		if (selectedActivities.isEmpty() && selectedConnections.isEmpty()) {
			return
		}
		selectedActivities.clear()
		selectedConnections.clear()
		notifySelection()
	}

	/** Выделить ровно один блок — используется поиском и переходом по стрелке. */
	/** Единственный выделенный блок; null — не выделено ничего или выделено несколько. */
	fun selectedActivity(): ProcessActivity? = selectedActivities.singleOrNull()

	fun selectOnly(activity: ProcessActivity) {
		selectedActivities.clear()
		selectedConnections.clear()
		selectedActivities += activity
		notifySelection()
		invalidate()
	}

	/** Прямоугольник блока в координатах схемы: нужен, чтобы доскроллить до него. */
	fun paneRectOf(activity: ProcessActivity): DiagramGeometry.Rect? {
		val model = this.model ?: return null
		if (model.activities.none { it.uid == activity.uid }) {
			return null
		}
		return rectOf(activity)
	}

	/**
	 * П.8 ТЗ: окно просмотра в координатах схемы (без масштаба).
	 * Вызывается при скролле, изменении размера и смене масштаба.
	 */
	fun updateViewport(minX: Double, minY: Double, width: Double, height: Double) {
		val next = DiagramGeometry.Rect(minX, minY, width, height)
		val previous = viewport
		viewport = next
		if (previous == null || !similar(previous, next)) {
			invalidate()
		}
	}

	/**
	 * Порог сравнения задан в экранных пикселях: на мелком масштабе одна единица
	 * схемы — доли пикселя, и дрожание скроллбара запускало бесконечную пересборку.
	 */
	private fun similar(a: DiagramGeometry.Rect, b: DiagramGeometry.Rect): Boolean {
		val tolerance = VIEWPORT_TOLERANCE / zoom.x.coerceAtLeast(DiagramStyle.MIN_ZOOM)
		return kotlin.math.abs(a.x - b.x) < tolerance &&
			kotlin.math.abs(a.y - b.y) < tolerance &&
			kotlin.math.abs(a.w - b.w) < tolerance &&
			kotlin.math.abs(a.h - b.h) < tolerance
	}

	/* ------------------------------------------------------ пересчёт и отсечение */

	/**
	 * Точка входа для перерисовки.
	 *
	 * Пересборка меняет children, из-за чего ScrollPane пересчитывает hvalue/vvalue
	 * и синхронно дёргает наш слушатель — получался повторный вход в [rebuild]
	 * посреди незавершённого изменения ObservableList, а он не реентерабелен
	 * (UnsupportedOperationException в ListChangeBuilder). Поэтому вложенный вызов
	 * не выполняется сразу, а откладывается на следующий пульс.
	 */
	private fun invalidate() {
		if (rebuilding) {
			rebuildPending = true
			return
		}
		rebuilding = true
		try {
			rebuild()
		} finally {
			rebuilding = false
		}
		if (rebuildPending) {
			rebuildPending = false
			Platform.runLater { invalidate() }
		}
	}

	/**
	 * Пересобирает сцену: в неё попадают только элементы, пересекающие окно
	 * просмотра, расширенное на [CULL_MARGIN]. При мелком масштабе подписи
	 * и счётчики не создаются вовсе — это и есть экономия ресурсов из п.8.
	 * Масштаб задаётся аффинным преобразованием, поэтому фигуры остаются
	 * векторными и при приближении не мылятся.
	 */
	private fun rebuild() {
		val model = this.model ?: return

		val activityRects = model.activities.associateWith { rectOf(it) }
		val startRect = startRect()

		contentWidth = (activityRects.map { (activity, rect) -> rect.right + outputLabelExtent(activity) } +
			listOfNotNull(startRect?.right) +
			model.connections.flatMap { it.waypoints }.map { toPaneX(it.x) })
			.maxOrNull()?.plus(DiagramStyle.PADDING) ?: 0.0
		contentHeight = (activityRects.values.map { it.bottom } +
			listOfNotNull(startRect?.bottom) +
			model.connections.flatMap { it.waypoints }.map { toPaneY(it.y) })
			.maxOrNull()?.plus(DiagramStyle.PADDING) ?: 0.0
		setPrefSize(contentWidth, contentHeight)
		// Без обрезки границы панели зависели бы от того, какие узлы сейчас построены:
		// добавление блока у края меняло размер содержимого, ScrollPane правил hvalue,
		// это снова меняло видимую область — и отсечение зацикливалось.
		contentClip.width = contentWidth
		contentClip.height = contentHeight

		val visible = cullingArea()
		val detailed = zoom.x >= DETAIL_ZOOM

		val boxes = HashMap<String, DiagramGeometry.Rect>(activityRects.size + 1)
		activityRects.forEach { (activity, rect) -> boxes[activity.uid] = rect }
		val startUid = model.startUid
		if (startUid != null && startRect != null) {
			boxes[startUid] = startRect
		}

		syncStart(startRect, visible)
		syncConnections(model.connections, boxes, visible, detailed)
		syncActivities(model.activities, activityRects, visible, detailed)
	}

	private fun cullingArea(): DiagramGeometry.Rect {
		val current = viewport ?: return DiagramGeometry.Rect(0.0, 0.0, contentWidth, contentHeight)
		return DiagramGeometry.Rect(
			current.x - CULL_MARGIN,
			current.y - CULL_MARGIN,
			current.w + 2 * CULL_MARGIN,
			current.h + 2 * CULL_MARGIN,
		)
	}

	private fun syncActivities(
		activities: List<ProcessActivity>,
		rects: Map<ProcessActivity, DiagramGeometry.Rect>,
		visible: DiagramGeometry.Rect,
		detailed: Boolean,
	) {
		val alive = HashSet<String>()
		activities.forEach { activity ->
			val rect = rects.getValue(activity)
			if (!rect.intersects(visible)) {
				return@forEach
			}
			alive += activity.uid
			val view = activityViews.getOrPut(activity.uid) {
				ActivityView(activity).also { activityLayer.children.add(it.root) }
			}
			view.update(rect, passCounts[activity.reference] ?: 0, detailed)
		}
		activityViews.keys.filterNot { it in alive }.forEach { uid ->
			activityViews.remove(uid)?.let { activityLayer.children.remove(it.root) }
		}
	}

	private fun syncConnections(
		connections: List<ProcessConnection>,
		boxes: Map<String, DiagramGeometry.Rect>,
		visible: DiagramGeometry.Rect,
		detailed: Boolean,
	) {
		val alive: MutableSet<ProcessConnection> =
			Collections.newSetFromMap(IdentityHashMap())
		connections.forEach { connection ->
			val from = boxes[connection.fromUid] ?: return@forEach
			val to = boxes[connection.toUid] ?: return@forEach
			val points = routeFor(connection, from, to, boxes.values)
			if (!DiagramGeometry.boundsOf(points).intersects(visible)) {
				return@forEach
			}
			alive += connection
			val view = connectionViews.getOrPut(connection) {
				ConnectionView(connection).also { connectionLayer.children.add(it.root) }
			}
			view.update(points, detailed)
		}
		connectionViews.keys.filterNot { it in alive }.toList().forEach { connection ->
			connectionViews.remove(connection)?.let { connectionLayer.children.remove(it.root) }
		}
		refreshConnectionZOrder()
	}

	/**
	 * Пройденные по трейсу соединения всегда лежат выше обычных соединений,
	 * но весь connectionLayer по-прежнему находится под activityLayer. Поэтому
	 * цветной маршрут лучше читается на пересечениях стрелок и никогда не рисуется
	 * поверх блоков.
	 */
	private fun refreshConnectionZOrder() {
		connectionViews.forEach { (connection, view) ->
			if (connectionTraceColors[connection] != null) view.root.toFront()
		}
		// Черновик новой стрелки должен оставаться видимым поверх готовых соединений.
		connectionDraftView.toFront()
	}

	private fun syncStart(rect: DiagramGeometry.Rect?, visible: DiagramGeometry.Rect) {
		if (rect == null || !rect.intersects(visible)) {
			startView?.let { activityLayer.children.remove(it) }
			startView = null
			return
		}
		val view = startView ?: StackPane(
			Circle(DiagramGeometry.START_SIZE / 2).apply { fill = DiagramStyle.START_FILL }
		).also { node ->
			Tooltip.install(node, Tooltip("Начало процедуры"))
			node.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
				if (event.button != MouseButton.PRIMARY) return@addEventHandler
				requestFocus()
				if (canCreateStartConnection()) {
					beginStartConnectionDraft(event)
					event.consume()
				}
			}
			node.addEventHandler(MouseEvent.MOUSE_DRAGGED) { event ->
				if (connectionDraft?.let { it.sourceActivity == null } == true) {
					updateConnectionDraft(event.sceneX, event.sceneY)
					event.consume()
				}
			}
			node.addEventHandler(MouseEvent.MOUSE_RELEASED) { event ->
				if (connectionDraft?.let { it.sourceActivity == null } == true) {
					finishConnectionDraft(event)
					event.consume()
				}
			}
			activityLayer.children.add(node)
			startView = node
		}
		view.cursor = if (canCreateStartConnection()) Cursor.CROSSHAIR else Cursor.DEFAULT
		view.layoutX = rect.x
		view.layoutY = rect.y
	}

	private fun dropAllViews() {
		activityLayer.children.clear()
		connectionViews.values.toList().forEach { connectionLayer.children.remove(it.root) }
		activityViews.clear()
		connectionViews.clear()
		autoRoutedConnections.clear()
		startView = null
	}

	/* -------------------------------------------------------------- координаты */

	private data class OutputPortPosition(
		val exitReference: String,
		val offsetY: Double,
	)

	private fun rebuildOutputPortLayoutCache(model: ProcedureModel) {
		val heights = linkedMapOf<String, Double>()
		val positions = linkedMapOf<String, List<OutputPortPosition>>()
		val outgoingByUid = model.connections.groupBy { it.fromUid }

		for (activity in model.activities) {
			val connected = linkedMapOf<String, Pair<String, Double>>()
			for (connection in outgoingByUid[activity.uid].orEmpty()) {
				val exitReference = connection.exitName?.takeIf { it.isNotBlank() } ?: continue
				val key = exitReference.lowercase()
				if (key !in connected) {
					connected[key] = exitReference to (connection.waypoints.firstOrNull()?.y ?: activity.y)
				}
			}

			val exits = buildList {
				addAll(activity.availableExits)
				addAll(connected.values.map { it.first })
			}.distinctBy { it.lowercase() }

			val renderHeight = requiredBlockHeight(exits.size)
			heights[activity.uid] = renderHeight
			if (exits.isEmpty()) {
				positions[activity.uid] = emptyList()
				continue
			}

			val minOffsetY = -renderHeight / 2.0 +
				DiagramGeometry.PORT_RADIUS + DiagramGeometry.OUTPUT_PORT_EDGE_PADDING
			val maxOffsetY = renderHeight / 2.0 -
				DiagramGeometry.PORT_RADIUS - DiagramGeometry.OUTPUT_PORT_EDGE_PADDING
			val availableRange = (maxOffsetY - minOffsetY).coerceAtLeast(0.0)

			val ordered = exits.mapIndexed { index, exitName ->
				val evenlyDistributedOffset = if (exits.size <= 1) {
					0.0
				} else {
					minOffsetY + availableRange * index / (exits.size - 1)
				}
				val desiredOffset = connected[exitName.lowercase()]
					?.second
					?.minus(activity.y)
					?.coerceIn(minOffsetY, maxOffsetY)
					?: evenlyDistributedOffset
				Triple(exitName, desiredOffset, index)
			}.sortedWith(compareBy<Triple<String, Double, Int>>({ it.second }, { it.third }))

			positions[activity.uid] = ordered.mapIndexed { index, item ->
				val offsetY = if (ordered.size <= 1) {
					item.second
				} else {
					minOffsetY + availableRange * index / (ordered.size - 1)
				}
				OutputPortPosition(item.first, kotlin.math.round(offsetY))
			}
		}

		blockHeightsByUid = heights
		outputPortsByUid = positions
	}

	private fun requiredBlockHeight(exitCount: Int): Double = DiagramGeometry.requiredBlockHeight(exitCount)

	private fun blockHeight(activity: ProcessActivity): Double =
		blockHeightsByUid[activity.uid] ?: DiagramGeometry.BLOCK_MIN_HEIGHT

	private fun outputPortPositions(activity: ProcessActivity): List<OutputPortPosition> =
		outputPortsByUid[activity.uid].orEmpty()

	private fun recalculateOrigin(model: ProcedureModel) {
		val minActivityX = model.activities.minOfOrNull {
			it.x - DiagramGeometry.BLOCK_WIDTH / 2.0 - DiagramGeometry.PORT_RADIUS
		} ?: 0.0
		val minActivityY = model.activities.minOfOrNull {
			it.y - blockHeight(it) / 2.0
		} ?: 0.0
		val minWaypointX = model.connections.flatMap { it.waypoints }.minOfOrNull { it.x }
		val minWaypointY = model.connections.flatMap { it.waypoints }.minOfOrNull { it.y }
		val minStartX = model.start?.x?.minus(DiagramGeometry.START_SIZE / 2.0)
		val minStartY = model.start?.y?.minus(DiagramGeometry.START_SIZE / 2.0)

		originX = listOfNotNull(minActivityX, minWaypointX, minStartX).minOrNull() ?: 0.0
		originY = listOfNotNull(minActivityY, minWaypointY, minStartY).minOrNull() ?: 0.0
	}

	private fun toPaneX(modelX: Double): Double = modelX - originX + DiagramStyle.PADDING

	private fun toPaneY(modelY: Double): Double = modelY - originY + DiagramStyle.PADDING

	private fun rectOf(activity: ProcessActivity) = DiagramGeometry.Rect(
		x = toPaneX(activity.x) - DiagramGeometry.BLOCK_WIDTH / 2.0,
		y = toPaneY(activity.y) - blockHeight(activity) / 2.0,
		w = DiagramGeometry.BLOCK_WIDTH,
		h = blockHeight(activity),
	)

	private fun startRect(): DiagramGeometry.Rect? {
		val point = model?.start ?: return null
		return DiagramGeometry.Rect(
			x = toPaneX(point.x) - DiagramGeometry.START_SIZE / 2.0,
			y = toPaneY(point.y) - DiagramGeometry.START_SIZE / 2.0,
			w = DiagramGeometry.START_SIZE,
			h = DiagramGeometry.START_SIZE,
		)
	}

	private fun paneWaypoint(waypoint: Waypoint) = Waypoint(toPaneX(waypoint.x), toPaneY(waypoint.y))

	private fun outputPortY(
		activity: ProcessActivity,
		exitReference: String?,
		fallbackPaneY: Double?,
	): Double {
		val centerY = toPaneY(activity.y)
		val height = blockHeight(activity)
		val minY = centerY - height / 2.0 +
			DiagramGeometry.PORT_RADIUS + DiagramGeometry.OUTPUT_PORT_EDGE_PADDING
		val maxY = centerY + height / 2.0 -
			DiagramGeometry.PORT_RADIUS - DiagramGeometry.OUTPUT_PORT_EDGE_PADDING

		if (!exitReference.isNullOrBlank()) {
			outputPortPositions(activity)
				.firstOrNull { it.exitReference.equals(exitReference, ignoreCase = true) }
				?.let { return centerY + it.offsetY }
		}
		return fallbackPaneY?.coerceIn(minY, maxY) ?: centerY
	}

	private fun routeFor(
		connection: ProcessConnection,
		from: DiagramGeometry.Rect,
		to: DiagramGeometry.Rect,
		blockBoxes: Collection<DiagramGeometry.Rect>,
	): List<DiagramGeometry.Point> {
		val fromActivity = connection.fromUid?.let(activitiesByUid::get)
		val toActivity = connection.toUid?.let(activitiesByUid::get)
		val paneWaypoints = connection.waypoints.map(::paneWaypoint)
		val source = if (fromActivity != null) {
			DiagramGeometry.Point(
				x = from.right + DiagramGeometry.PORT_RADIUS,
				y = outputPortY(fromActivity, connection.exitName, paneWaypoints.firstOrNull()?.y),
			)
		} else {
			DiagramGeometry.Point(from.right, from.centerY)
		}
		val target = if (toActivity != null) {
			DiagramGeometry.Point(to.x - DiagramGeometry.PORT_RADIUS, to.centerY)
		} else {
			DiagramGeometry.Point(to.x, to.centerY)
		}
		val backwardDetourY = min(from.y, to.y) - DiagramGeometry.PORT_STUB
		val storedRoute = DiagramGeometry.route(source, target, paneWaypoints, backwardDetourY)

		/*
		 * При drag блока его инцидентные стрелки строятся заново на каждом кадре,
		 * как в r-crif-layout-merger. Кроме того, если сохранённый route оказался
		 * под любым блоком (в том числе блок подвинули поверх чужой стрелки), сразу
		 * переключаемся на obstacle-aware Manhattan route.
		 */
		val movingBlockUids = if (dragMoved) {
			dragActivityOrigin.keys.mapTo(HashSet()) { it.uid }
		} else {
			emptySet()
		}
		val incidentToMovingBlock =
			connection.fromUid in movingBlockUids || connection.toUid in movingBlockUids
		val routeBlocked = DiagramGeometry.crossesAnyBlock(storedRoute, blockBoxes)

		if (connection in autoRoutedConnections || incidentToMovingBlock || routeBlocked) {
			DiagramGeometry.routeAvoidingBlocks(
				source = source,
				target = target,
				sourceBox = from,
				targetBox = to,
				blockBoxes = blockBoxes,
			)?.let {
				if (incidentToMovingBlock || routeBlocked) autoRoutedConnections += connection
				return it
			}
		}

		return storedRoute
	}

	private fun outputLabelExtent(activity: ProcessActivity): Double {
		val longest = outputPortPositions(activity).maxOfOrNull { it.exitReference.length } ?: 0
		if (longest == 0) return 0.0
		// Для content bounds достаточно стабильной оценки; сам текст рисуется JavaFX Text.
		return DiagramGeometry.PORT_RADIUS + DiagramGeometry.EXIT_LABEL_GAP +
			longest * DiagramGeometry.EXIT_LABEL_FONT_SIZE * 0.62 + 4.0
	}

	/* ------------------------------------------------------------- взаимодействие */

	/** П.6 ТЗ: удержание ЛКМ по пустому полю — рамка мультивыбора. */
	private fun installFieldHandlers() {
		addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
			if (event.button != MouseButton.PRIMARY) {
				return@addEventHandler
			}
			requestFocus()
			val point = sceneToLocal(event.sceneX, event.sceneY)
			rubberStartX = point.x
			rubberStartY = point.y
			rubberActive = true
			rubberBand.isVisible = false
			if (!event.isControlDown && !event.isShiftDown) {
				clearSelection()
				invalidate()
			}
		}
		addEventHandler(MouseEvent.MOUSE_DRAGGED) { event ->
			if (!rubberActive) {
				return@addEventHandler
			}
			val point = sceneToLocal(event.sceneX, event.sceneY)
			rubberBand.isVisible = true
			rubberBand.x = min(rubberStartX, point.x)
			rubberBand.y = min(rubberStartY, point.y)
			rubberBand.width = kotlin.math.abs(point.x - rubberStartX)
			rubberBand.height = kotlin.math.abs(point.y - rubberStartY)
		}
		addEventHandler(MouseEvent.MOUSE_RELEASED) {
			if (!rubberActive) {
				return@addEventHandler
			}
			rubberActive = false
			if (rubberBand.isVisible) {
				selectInside(
					DiagramGeometry.Rect(rubberBand.x, rubberBand.y, rubberBand.width, rubberBand.height)
				)
			}
			rubberBand.isVisible = false
		}
	}

	private fun selectInside(area: DiagramGeometry.Rect) {
		val model = this.model ?: return
		val boxes = HashMap<String, DiagramGeometry.Rect>()
		model.activities.forEach { activity ->
			val rect = rectOf(activity)
			boxes[activity.uid] = rect
			if (rect.intersects(area)) {
				selectedActivities += activity
			}
		}
		startRect()?.let { rect -> model.startUid?.let { boxes[it] = rect } }
		model.connections.forEach { connection ->
			val from = boxes[connection.fromUid] ?: return@forEach
			val to = boxes[connection.toUid] ?: return@forEach
			val points = routeFor(connection, from, to, boxes.values)
			if (points.zipWithNext().any { (a, b) -> segmentIntersectsRect(a, b, area) }) {
				selectedConnections += connection
			}
		}
		notifySelection()
		invalidate()
	}

	private fun segmentIntersectsRect(
		a: DiagramGeometry.Point,
		b: DiagramGeometry.Point,
		rect: DiagramGeometry.Rect,
	): Boolean {
		if (rect.contains(a.x, a.y) || rect.contains(b.x, b.y)) return true
		if (a.y == b.y) {
			return a.y in rect.y..rect.bottom && max(min(a.x, b.x), rect.x) <= min(max(a.x, b.x), rect.right)
		}
		if (a.x == b.x) {
			return a.x in rect.x..rect.right && max(min(a.y, b.y), rect.y) <= min(max(a.y, b.y), rect.bottom)
		}
		// Fallback для редкого диагонального сегмента: проверяем пересечение по bounds.
		return DiagramGeometry.Rect(
			x = min(a.x, b.x),
			y = min(a.y, b.y),
			w = kotlin.math.abs(a.x - b.x),
			h = kotlin.math.abs(a.y - b.y),
		).intersects(rect)
	}

	private fun notifySelection() {
		onSelectionChanged?.invoke(selectedActivities.size, selectedConnections.size)
	}

	private fun selectActivity(activity: ProcessActivity, additive: Boolean) {
		if (!additive) {
			if (activity in selectedActivities && selectedActivities.size == 1 && selectedConnections.isEmpty()) {
				return
			}
			selectedActivities.clear()
			selectedConnections.clear()
			selectedActivities += activity
		} else if (!selectedActivities.remove(activity)) {
			selectedActivities += activity
		}
		notifySelection()
	}

	private fun selectConnection(connection: ProcessConnection, additive: Boolean) {
		if (!additive) {
			selectedActivities.clear()
			selectedConnections.clear()
			selectedConnections += connection
		} else if (!selectedConnections.remove(connection)) {
			selectedConnections += connection
		}
		notifySelection()
	}

	/* ------------------------------------------------------ рисование стрелок */

	private fun isOutputConnected(activity: ProcessActivity, exitReference: String): Boolean =
		model?.connections.orEmpty().any { connection ->
			connection.fromUid == activity.uid &&
				connection.exitName.equals(exitReference, ignoreCase = true)
		}

	private fun canCreateStartConnection(): Boolean {
		val current = model ?: return false
		val startUid = current.startUid ?: return false
		return current.start != null && current.connections.none { it.fromUid == startUid }
	}

	/** Незанятый выходной кружок под курсором. */
	private fun unusedOutputPortAt(
		activity: ProcessActivity,
		sceneX: Double,
		sceneY: Double,
	): OutputPortPosition? {
		if (zoom.x < DETAIL_ZOOM) return null
		val point = sceneToLocal(sceneX, sceneY)
		val rect = rectOf(activity)
		val hitRadius = DiagramGeometry.PORT_RADIUS + CONNECTION_PORT_HIT_PADDING_PX / zoom.x.coerceAtLeast(0.01)

		return outputPortPositions(activity)
			.asSequence()
			.filterNot { isOutputConnected(activity, it.exitReference) }
			.map { port ->
				val center = DiagramGeometry.Point(rect.right, rect.centerY + port.offsetY)
				port to hypot(point.x - center.x, point.y - center.y)
			}
			.filter { (_, distance) -> distance <= hitRadius }
			.minByOrNull { (_, distance) -> distance }
			?.first
	}

	private fun beginConnectionDraft(
		activity: ProcessActivity,
		port: OutputPortPosition,
		event: MouseEvent,
	) {
		val rect = rectOf(activity)
		connectionDraft = ConnectionDraft(
			sourceUid = activity.uid,
			sourceActivity = activity,
			exitReference = port.exitReference,
			sourceAnchor = DiagramGeometry.Point(
				x = rect.right + DiagramGeometry.PORT_RADIUS,
				y = rect.centerY + port.offsetY,
			),
		)
		updateConnectionDraft(event.sceneX, event.sceneY)
	}

	private fun beginStartConnectionDraft(event: MouseEvent) {
		val current = model ?: return
		val startUid = current.startUid ?: return
		val rect = startRect() ?: return
		if (!canCreateStartConnection()) return
		connectionDraft = ConnectionDraft(
			sourceUid = startUid,
			sourceActivity = null,
			exitReference = START_EXIT_REFERENCE,
			sourceAnchor = DiagramGeometry.Point(rect.right, rect.centerY),
		)
		updateConnectionDraft(event.sceneX, event.sceneY)
	}

	private fun updateConnectionDraft(sceneX: Double, sceneY: Double) {
		val draft = connectionDraft ?: return
		val end = sceneToLocal(sceneX, sceneY)
		val start = draft.sourceAnchor
		val points = listOf(
			start,
			DiagramGeometry.Point(end.x, start.y),
			DiagramGeometry.Point(end.x, end.y),
		)
		val flat = ArrayList<Double>(points.size * 2)
		points.forEach { point ->
			flat += point.x
			flat += point.y
		}
		connectionDraftLine.points.setAll(flat)
		connectionDraftLine.strokeWidth = 2.0 / zoom.x.coerceAtLeast(0.01)
		connectionDraftLine.strokeDashArray.setAll(6.0 / zoom.x.coerceAtLeast(0.01), 4.0 / zoom.x.coerceAtLeast(0.01))
		connectionDraftLine.isVisible = true
		updateArrow(connectionDraftArrow, points, DiagramGeometry.ARROW_SIZE / zoom.x.coerceAtLeast(0.01))
	}

	private fun finishConnectionDraft(event: MouseEvent) {
		val draft = connectionDraft ?: return
		val target = connectionTargetAt(event.sceneX, event.sceneY, draft.sourceUid)
		cancelConnectionDraft()
		if (target == null) return

		val currentModel = model ?: return
		val sourceStillAvailable = if (draft.sourceActivity != null) {
			val exit = draft.exitReference ?: return
			!isOutputConnected(draft.sourceActivity, exit)
		} else {
			canCreateStartConnection()
		}
		if (!sourceStillAvailable) return

		val connection = ProcessConnection(
			uid = UUID.randomUUID().toString(),
			fromUid = draft.sourceUid,
			toUid = target.uid,
			exitName = draft.exitReference,
			waypoints = mutableListOf(),
		)
		val insertIndex = currentModel.connections.size
		val autoRouteBefore = snapshotAutoRoutedConnections()
		val command = object : DiagramCommand {
			override val title: String = "Создание стрелки"

			override fun apply() {
				if (currentModel.connections.none { it === connection }) {
					currentModel.connections.add(insertIndex.coerceIn(0, currentModel.connections.size), connection)
				}
				restoreAutoRoutedConnections(autoRouteBefore + connection)
				afterModelMutation(currentModel)
				selectedConnections += connection
				notifySelection()
				invalidate()
			}

			override fun revert() {
				currentModel.connections.removeAll { it === connection }
				restoreAutoRoutedConnections(autoRouteBefore)
				afterModelMutation(currentModel)
			}
		}

		command.apply()
		undoManager.push(command)
	}

	private fun connectionTargetAt(sceneX: Double, sceneY: Double, sourceUid: String): ProcessActivity? {
		val currentModel = model ?: return null
		val point = sceneToLocal(sceneX, sceneY)
		val hitRadius = DiagramGeometry.PORT_RADIUS + CONNECTION_PORT_HIT_PADDING_PX / zoom.x.coerceAtLeast(0.01)

		// Сначала именно входной кружок: он немного выступает за левую грань блока.
		currentModel.activities
			.asSequence()
			.filter { it.uid != sourceUid }
			.map { activity ->
				val rect = rectOf(activity)
				activity to hypot(point.x - rect.x, point.y - rect.centerY)
			}
			.filter { (_, distance) -> distance <= hitRadius }
			.minByOrNull { (_, distance) -> distance }
			?.first
			?.let { return it }

		// Как в merger, бросить стрелку можно и на само тело блока.
		return currentModel.activities.asReversed().firstOrNull { activity ->
			if (activity.uid == sourceUid) return@firstOrNull false
			val rect = rectOf(activity)
			point.x in rect.x..rect.right && point.y in rect.y..rect.bottom
		}
	}

	private fun cancelConnectionDraft() {
		connectionDraft = null
		connectionDraftLine.isVisible = false
		connectionDraftArrow.isVisible = false
		connectionDraftLine.points.clear()
	}

	private fun updateArrow(arrow: Polygon, points: List<DiagramGeometry.Point>, size: Double) {
		val last = points.lastOrNull()
		val previous = points.getOrNull(points.size - 2)
		if (last == null || previous == null || (last.x == previous.x && last.y == previous.y)) {
			arrow.isVisible = false
			return
		}
		val angle = atan2(last.y - previous.y, last.x - previous.x)
		arrow.points.setAll(
			last.x, last.y,
			last.x - size * cos(angle - PI / 7),
			last.y - size * sin(angle - PI / 7),
			last.x - size * cos(angle + PI / 7),
			last.y - size * sin(angle + PI / 7),
		)
		arrow.isVisible = true
	}

	/* ------------------------------------------------------------ перетаскивание */

	/** П.1 ТЗ: удержание ЛКМ на блоке или стрелке двигает всё выделенное. */
	private fun beginDrag(event: MouseEvent) {
		val point = sceneToLocal(event.sceneX, event.sceneY)
		dragStartX = point.x
		dragStartY = point.y
		dragActivityOrigin.clear()
		dragWaypointOrigin.clear()
		dragAutoRoutedOrigin.clear()
		dragAutoRoutedOrigin.addAll(autoRoutedConnections)
		dragMoved = false
		selectedActivities.forEach {
			dragActivityOrigin[it] = DiagramGeometry.Point(it.x, it.y)
		}
		selectedConnections.forEach { connection ->
			connection.waypoints.forEach { dragWaypointOrigin[it] = DiagramGeometry.Point(it.x, it.y) }
		}
		// Явный drag стрелки снова отдаёт управление сохранённым DiagramSplit.
		if (dragActivityOrigin.isEmpty()) {
			selectedConnections.forEach { autoRoutedConnections.remove(it) }
		}
		dragging = dragActivityOrigin.isNotEmpty() || dragWaypointOrigin.isNotEmpty()
	}

	private fun continueDrag(event: MouseEvent) {
		if (!dragging) {
			return
		}
		val point = sceneToLocal(event.sceneX, event.sceneY)
		val dx = point.x - dragStartX
		val dy = point.y - dragStartY
		if (!dragMoved) {
			val screenDistance = hypot(dx, dy) * zoom.x
			if (screenDistance < DRAG_START_THRESHOLD_PX) {
				return
			}
			dragMoved = true
		}
		dragActivityOrigin.forEach { (activity, origin) ->
			activity.x = DiagramGeometry.snap(origin.x + dx)
			activity.y = DiagramGeometry.snap(origin.y + dy)
		}
		dragWaypointOrigin.forEach { (waypoint, origin) ->
			waypoint.x = DiagramGeometry.snap(origin.x + dx)
			waypoint.y = DiagramGeometry.snap(origin.y + dy)
		}
		invalidate()
	}

	private fun finishDrag() {
		if (!dragging) {
			return
		}
		dragging = false
		if (!dragMoved) {
			restoreAutoRoutedConnections(dragAutoRoutedOrigin)
			dragActivityOrigin.clear()
			dragWaypointOrigin.clear()
			dragAutoRoutedOrigin.clear()
			invalidate()
			return
		}
		dragMoved = false
		val movedActivities = dragActivityOrigin.map { (activity, origin) ->
			activity.x = DiagramGeometry.snap(activity.x)
			activity.y = DiagramGeometry.snap(activity.y)
			MovedActivity(activity, origin.x, origin.y, activity.x, activity.y)
		}.filter { it.fromX != it.toX || it.fromY != it.toY }
		val movedWaypoints = dragWaypointOrigin.map { (waypoint, origin) ->
			waypoint.x = DiagramGeometry.snap(waypoint.x)
			waypoint.y = DiagramGeometry.snap(waypoint.y)
			MovedWaypoint(waypoint, origin.x, origin.y, waypoint.x, waypoint.y)
		}.filter { it.fromX != it.toX || it.fromY != it.toY }
		val autoRouteBefore = dragAutoRoutedOrigin.toList()

		dragActivityOrigin.clear()
		dragWaypointOrigin.clear()
		dragAutoRoutedOrigin.clear()
		invalidate()
		val autoRouteAfter = snapshotAutoRoutedConnections()

		if (movedActivities.isNotEmpty() || movedWaypoints.isNotEmpty()) {
			undoManager.push(
				MoveCommand(
					activities = movedActivities,
					waypoints = movedWaypoints,
					afterApply = {
						restoreAutoRoutedConnections(autoRouteAfter)
						invalidate()
					},
					afterRevert = {
						restoreAutoRoutedConnections(autoRouteBefore)
						invalidate()
					},
				)
			)
		}
	}

	/* -------------------------------------------------------------- удаление */

	private fun deleteSelectionWithConfirmation() {
		requestDelete(
			activities = selectedActivities.toList(),
			connections = selectedConnections.toList(),
		)
	}

	private fun requestDelete(
		activities: List<ProcessActivity>,
		connections: List<ProcessConnection>,
	) {
		val current = model ?: return
		val activitySet: MutableSet<ProcessActivity> =
			Collections.newSetFromMap(IdentityHashMap())
		activitySet.addAll(activities)
		val connectionSet: MutableSet<ProcessConnection> =
			Collections.newSetFromMap(IdentityHashMap())
		connectionSet.addAll(connections)
		if (activitySet.isNotEmpty()) {
			val removedUids = activitySet.mapTo(HashSet()) { it.uid }
			current.connections
				.filterTo(connectionSet) { it.fromUid in removedUids || it.toUid in removedUids }
		}

		val activityEntries = current.activities.mapIndexedNotNull { index, activity ->
			if (activity in activitySet) index to activity else null
		}
		val connectionEntries = current.connections.mapIndexedNotNull { index, connection ->
			if (connection in connectionSet) index to connection else null
		}
		if (activityEntries.isEmpty() && connectionEntries.isEmpty()) return

		val confirmation = when {
			activityEntries.size == 1 -> buildString {
				append("Удалить блок «").append(activityEntries.single().second.reference).append("»?")
				if (connectionEntries.isNotEmpty()) {
					append("\nСвязанные стрелки также будут удалены: ").append(connectionEntries.size).append('.')
				}
			}
			activityEntries.isEmpty() && connectionEntries.size == 1 -> "Удалить выбранную стрелку?"
			else -> "Удалить выбранные элементы?\nБлоков: ${activityEntries.size}, стрелок: ${connectionEntries.size}."
		}
		if (onDeleteConfirmationRequest?.invoke(confirmation) == false) return
		val autoRouteBefore = snapshotAutoRoutedConnections()
		val removedConnections: MutableSet<ProcessConnection> =
			Collections.newSetFromMap(IdentityHashMap())
		removedConnections.addAll(connectionEntries.map { it.second })
		val autoRouteAfter = autoRouteBefore.filterNot { it in removedConnections }

		val command = object : DiagramCommand {
			override val title: String = when {
				activityEntries.isNotEmpty() && connectionEntries.isNotEmpty() -> "Удаление блоков и стрелок"
				activityEntries.isNotEmpty() -> "Удаление блоков"
				else -> "Удаление стрелок"
			}

			override fun apply() {
				current.activities.removeAll { candidate -> activityEntries.any { (_, item) -> item === candidate } }
				current.connections.removeAll { candidate -> connectionEntries.any { (_, item) -> item === candidate } }
				restoreAutoRoutedConnections(autoRouteAfter)
				afterModelMutation(current)
			}

			override fun revert() {
				activityEntries.sortedBy { it.first }.forEach { (index, activity) ->
					current.activities.add(index.coerceIn(0, current.activities.size), activity)
				}
				connectionEntries.sortedBy { it.first }.forEach { (index, connection) ->
					current.connections.add(index.coerceIn(0, current.connections.size), connection)
				}
				restoreAutoRoutedConnections(autoRouteBefore)
				afterModelMutation(current)
			}
		}

		command.apply()
		undoManager.push(command)
	}

	private fun afterModelMutation(current: ProcedureModel) {
		activitiesByUid = current.activities.associateBy { it.uid }
		rebuildOutputPortLayoutCache(current)
		cancelConnectionDraft()
		selectedActivities.clear()
		selectedConnections.clear()
		val aliveConnections: MutableSet<ProcessConnection> =
			Collections.newSetFromMap(IdentityHashMap())
		aliveConnections.addAll(current.connections)
		autoRoutedConnections.removeAll { it !in aliveConnections }
		notifySelection()
		invalidate()
	}

	private fun snapshotAutoRoutedConnections(): List<ProcessConnection> =
		autoRoutedConnections.toList()

	private fun restoreAutoRoutedConnections(connections: Collection<ProcessConnection>) {
		autoRoutedConnections.clear()
		autoRoutedConnections.addAll(connections)
	}

	/* ------------------------------------------------------- контекстное меню */

	/**
	 * П.2 ТЗ: по ПКМ на блоке показываем, откуда в него можно прийти
	 * (входящие стрелки) и куда из него можно уйти (исходящие стрелки).
	 * Пункт меню переводит выделение на соседний блок.
	 */
	private fun dismissContextMenu() {
		contextMenu?.hide()
		contextMenu = null
	}

	private fun showActivityMenu(activity: ProcessActivity, screenX: Double, screenY: Double) {
		val model = this.model ?: return
		dismissContextMenu()
		requestFocus()
		if (activity !in selectedActivities) {
			selectActivity(activity, additive = false)
			invalidate()
		}

		val byUid = model.activities.associateBy { it.uid }
		val incoming = model.connections.filter { it.toUid == activity.uid }
		val outgoing = model.connections.filter { it.fromUid == activity.uid }

		val menu = ContextMenu()
		menu.items += MenuItem(activity.reference).apply { isDisable = true }
		menu.items += javafx.scene.control.SeparatorMenuItem()
		menu.items += xsltSandboxMenu(activity.procedureName ?: model.name, activity)
		menu.items += javafx.scene.control.SeparatorMenuItem()
		menu.items += neighbourMenu(
			title = "Предыдущие активности",
			connections = incoming,
			byUid = byUid,
			neighbourUid = { it.fromUid },
		)
		menu.items += neighbourMenu(
			title = "Следующие активности",
			connections = outgoing,
			byUid = byUid,
			neighbourUid = { it.toUid },
		)
		menu.items += javafx.scene.control.SeparatorMenuItem()
		menu.items += MenuItem("Удалить блок").apply {
			setOnAction { requestDelete(listOf(activity), emptyList()) }
		}

		menu.setOnHidden { if (contextMenu === menu) contextMenu = null }
		contextMenu = menu
		menu.show(this, screenX, screenY)
	}

	private fun showConnectionMenu(connection: ProcessConnection, screenX: Double, screenY: Double) {
		dismissContextMenu()
		requestFocus()
		if (connection !in selectedConnections) {
			selectConnection(connection, additive = false)
			invalidate()
		}
		val menu = ContextMenu(
			MenuItem("Удалить стрелку").apply {
				setOnAction { requestDelete(emptyList(), listOf(connection)) }
			},
		)
		menu.setOnHidden { if (contextMenu === menu) contextMenu = null }
		contextMenu = menu
		menu.show(this, screenX, screenY)
	}

	/**
	 * Вызовы читаются из БД только при раскрытии подменю, а не при каждом ПКМ.
	 * Если вызов один, он не показывается отдельным уровнем; если файлов для типа
	 * активности несколько (DS: MappingInput/MappingOutput), они идут вторым уровнем.
	 */
	private fun xsltSandboxMenu(procedureName: String, activity: ProcessActivity): Menu {
		val targets = XsltSandbox.targetsFor(activity.type)
		val menu = Menu("Открыть в XSLT-sandbox")
		if (targets.isEmpty()) {
			menu.items += MenuItem("недоступно для типа ${activity.type.name}").apply { isDisable = true }
			return menu
		}

		fun targetItem(call: ActivityCall, target: XsltSandboxTarget) =
			MenuItem(target.fileName).apply {
				isDisable = target.inputData(call) == null
				setOnAction { onOpenInXsltSandbox?.invoke(activity, call, target) }
			}

		menu.setOnShowing {
			val calls = runCatching { activityCallsProvider?.invoke(procedureName, activity) }
				.getOrNull()
				.orEmpty()
			menu.items.clear()
			when {
				calls.isEmpty() -> menu.items += MenuItem("нет вызовов в трейсе").apply { isDisable = true }
				calls.size == 1 -> targets.forEach { menu.items += targetItem(calls.single(), it) }
				else -> calls.forEach { call ->
					val caption = "Вызов ${call.index}   ·   ${callTimeLabel(call)}"
					if (targets.size == 1) {
						menu.items += targetItem(call, targets.single()).apply { text = caption }
					} else {
						menu.items += Menu(caption).apply {
							targets.forEach { items += targetItem(call, it) }
						}
					}
				}
			}
		}
		// Пустой дочерний пункт нужен, чтобы JavaFX вообще раскрыл подменю и вызвал onShowing.
		menu.items += MenuItem("загрузка…").apply { isDisable = true }
		return menu
	}

	private fun callTimeLabel(call: ActivityCall): String {
		val millis = traceTimestamp(call.startedAt) ?: return call.startedAt ?: "—"
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
			.format(CALL_TIME_FORMATTER)
	}

	private fun neighbourMenu(
		title: String,
		connections: List<ProcessConnection>,
		byUid: Map<String, ProcessActivity>,
		neighbourUid: (ProcessConnection) -> String?,
	): Menu = Menu(title).apply {
		if (connections.isEmpty()) {
			items += MenuItem("нет стрелок").apply { isDisable = true }
			return@apply
		}
		connections.forEach { connection ->
			val neighbour = byUid[neighbourUid(connection)]
			val exit = connection.exitName?.takeIf { it.isNotBlank() }
			val caption = when {
				neighbour == null -> "СТАРТ процедуры"
				exit == null -> neighbour.reference
				else -> "${neighbour.reference}   ·   выход «$exit»"
			}
			items += MenuItem(caption).apply {
				isDisable = neighbour == null
				setOnAction { neighbour?.let { onNavigateToActivity?.invoke(it) } }
			}
		}
	}

	/* -------------------------------------------------------------------- виды */

	private inner class ActivityView(val activity: ProcessActivity) {

		private val selectionFrame = Rectangle().apply {
			fill = Color.TRANSPARENT
			stroke = DiagramStyle.SELECTION_STROKE
			strokeWidth = 1.5
			arcWidth = 12.0
			arcHeight = 12.0
			isMouseTransparent = true
		}
		private val box = Rectangle().apply {
			arcWidth = 10.0
			arcHeight = 10.0
		}
		private val label = Label().apply {
			isWrapText = true
			font = Font.font(11.0)
			textFill = DiagramStyle.BOX_TEXT
			textAlignment = TextAlignment.CENTER
			alignment = Pos.CENTER
			isMouseTransparent = true
		}
		private val labelHolder = StackPane(label).apply { isMouseTransparent = true }
		private val icon = ImageView(ActivityIcons.imageFor(activity.type)).apply {
			fitWidth = ActivityIcons.SIZE
			fitHeight = ActivityIcons.SIZE
			isPreserveRatio = true
			isSmooth = true
			isMouseTransparent = true
		}
		private val badgeCircle = Circle(DiagramStyle.BADGE_RADIUS).apply { strokeWidth = 1.5 }
		private val badgeText = Text().apply { font = Font.font(11.0) }
		private val badge = StackPane(badgeCircle, badgeText).apply {
			cursor = Cursor.HAND
			isPickOnBounds = true
		}
		private val timingBubbleText = Text().apply {
			font = Font.font(10.0)
			fill = Color.web("#3c4043")
			isMouseTransparent = true
		}
		private val timingBubbleBackground = Rectangle().apply {
			arcWidth = 14.0
			arcHeight = 14.0
			fill = Color.web("#ffffff", 0.94)
			stroke = Color.web("#9aa0a6")
			strokeWidth = 1.0
			isMouseTransparent = true
		}
		private val timingBubble = Group(timingBubbleBackground, timingBubbleText).apply {
			isMouseTransparent = true
		}
		private val timingTooltip = Tooltip()
		private val ports = Group().apply { isMouseTransparent = false }
		val root = Group(selectionFrame, box, icon, labelHolder, ports, badge, timingBubble)

		init {
			root.cursor = Cursor.MOVE
			root.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
				if (event.button != MouseButton.PRIMARY) {
					return@addEventHandler
				}
				requestFocus()

				val outputPort = unusedOutputPortAt(activity, event.sceneX, event.sceneY)
				if (outputPort != null) {
					beginConnectionDraft(activity, outputPort, event)
					event.consume()
					return@addEventHandler
				}

				selectActivity(activity, additive = event.isControlDown || event.isShiftDown)
				invalidate()
				beginDrag(event)
				event.consume()
			}
			root.addEventHandler(MouseEvent.MOUSE_DRAGGED) { event ->
				if (connectionDraft != null) {
					updateConnectionDraft(event.sceneX, event.sceneY)
				} else {
					continueDrag(event)
				}
				event.consume()
			}
			root.addEventHandler(MouseEvent.MOUSE_RELEASED) { event ->
				if (connectionDraft != null) {
					finishConnectionDraft(event)
				} else {
					finishDrag()
				}
				event.consume()
			}
			root.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
				if (
					event.button == MouseButton.PRIMARY &&
					event.clickCount >= 2 &&
					activity.type == ActivityType.PROCEDURE_CALL &&
					unusedOutputPortAt(activity, event.sceneX, event.sceneY) == null
				) {
					onProcedureCallDoubleClick?.invoke(activity)
					event.consume()
				}
			}
			root.setOnContextMenuRequested { event ->
				showActivityMenu(activity, event.screenX, event.screenY)
				event.consume()
			}
			// Кружок не запускает drag блока, но обычный ЛКМ всё равно выбирает активность.
			badge.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
				requestFocus()
				event.consume()
			}
			badge.addEventHandler(MouseEvent.MOUSE_DRAGGED) { it.consume() }
			badge.addEventHandler(MouseEvent.MOUSE_RELEASED) { it.consume() }
			badge.setOnMouseClicked { event ->
				if (event.button == MouseButton.PRIMARY) {
					selectActivity(activity, additive = event.isControlDown || event.isShiftDown)
					invalidate()
					if (event.clickCount >= 2) onCounterDoubleClick(activity)
				}
				event.consume()
			}
		}

		fun update(rect: DiagramGeometry.Rect, passCount: Int, detailed: Boolean) {
			root.layoutX = rect.x
			root.layoutY = rect.y

			box.width = rect.w
			box.height = rect.h
			box.fill = ActivityPalette.fillFor(activity.type)
			outlineScheme.applyTo(box, activity, passCount)
			val debugged = isDebugged(activity)
			if (debugged) {
				box.stroke = DiagramStyle.DEBUGGED_STROKE
				box.strokeWidth = 2.5
			}

			val selected = activity in selectedActivities
			selectionFrame.isVisible = selected
			if (selected) {
				selectionFrame.x = -SELECTION_INSET
				selectionFrame.y = -SELECTION_INSET
				selectionFrame.width = rect.w + 2 * SELECTION_INSET
				selectionFrame.height = rect.h + 2 * SELECTION_INSET
			}

			// Иконка сверху по центру блока, подпись — под ней.
			val hasIcon = detailed && icon.image != null
			icon.isVisible = hasIcon
			if (hasIcon) {
				icon.layoutX = (rect.w - ActivityIcons.SIZE) / 2.0
				icon.layoutY = ActivityIcons.PADDING
			}

			labelHolder.isVisible = detailed
			if (detailed) {
				// Подпись опускается под строку иконки, чтобы длинное имя не залезало под картинку.
				labelHolder.padding = if (hasIcon) LABEL_UNDER_ICON_PADDING else Insets.EMPTY
				label.text = activity.reference
				label.maxWidth = max(20.0, rect.w - 10.0)
				labelHolder.setMinSize(rect.w, rect.h)
				labelHolder.setPrefSize(rect.w, rect.h)
				labelHolder.setMaxSize(rect.w, rect.h)
			}

			badge.isVisible = detailed
			badge.isManaged = detailed
			if (detailed) {
				val passed = passCount > 0
				badgeCircle.fill = when {
					debugged -> DiagramStyle.DEBUGGED_BADGE_FILL
					passed -> DiagramStyle.PASSED_BADGE_FILL
					else -> DiagramStyle.IDLE_BADGE_FILL
				}
				badgeCircle.stroke = when {
					debugged -> DiagramStyle.DEBUGGED_STROKE
					passed -> DiagramStyle.PASSED_STROKE
					else -> DiagramStyle.IDLE_BADGE_STROKE
				}
				badgeText.text = passCount.toString()
				badgeText.fill = when {
					debugged -> DiagramStyle.DEBUGGED_BADGE_TEXT
					passed -> DiagramStyle.PASSED_BADGE_TEXT
					else -> DiagramStyle.IDLE_BADGE_TEXT
				}
				badge.layoutX = -DiagramStyle.BADGE_RADIUS
				badge.layoutY = -DiagramStyle.BADGE_RADIUS
			}

			val timing = activityTimings[normalizeTracePart(activity.reference)]
			timingBubble.isVisible = detailed && timing != null
			if (detailed && timing != null) {
				timingBubbleText.text = "⏱ ${timing.text}"

				val textBounds = timingBubbleText.layoutBounds
				val bubbleWidth = max(TIMING_BUBBLE_MIN_WIDTH, textBounds.width + TIMING_BUBBLE_HORIZONTAL_PADDING * 2.0)
				val bubbleHeight = max(TIMING_BUBBLE_HEIGHT, textBounds.height + TIMING_BUBBLE_VERTICAL_PADDING * 2.0)

				timingBubbleBackground.width = bubbleWidth
				timingBubbleBackground.height = bubbleHeight
				timingBubbleText.layoutX = (bubbleWidth - textBounds.width) / 2.0 - textBounds.minX
				timingBubbleText.layoutY = (bubbleHeight - textBounds.height) / 2.0 - textBounds.minY

				// Центр облачка лежит точно на левом нижнем углу блока.
				timingBubble.layoutX = -bubbleWidth / 2.0
				timingBubble.layoutY = rect.h - bubbleHeight / 2.0

				timingTooltip.text = timing.tooltip
			}

			ports.isVisible = detailed
			if (detailed) {
				ports.children.clear()
				// Вход всегда строго слева по центру, как в r-crif-layout-merger.
				ports.children += portCircle(0.0, rect.h / 2.0)

				for (port in outputPortPositions(activity)) {
					val localY = rect.h / 2.0 + port.offsetY
					ports.children += portCircle(rect.w, localY).apply {
						cursor = if (isOutputConnected(activity, port.exitReference)) Cursor.DEFAULT else Cursor.CROSSHAIR
					}
					ports.children += Text(port.exitReference).apply {
						font = Font.font(DiagramGeometry.EXIT_LABEL_FONT_SIZE)
						fill = DiagramStyle.BOX_TEXT
						layoutX = rect.w + DiagramGeometry.PORT_RADIUS + DiagramGeometry.EXIT_LABEL_GAP
						// Подпись целиком ниже горизонтального участка стрелки: линия больше не перечёркивает текст.
						layoutY = localY + DiagramGeometry.EXIT_LABEL_FONT_SIZE + EXIT_LABEL_BELOW_GAP
						isMouseTransparent = true
					}
				}
			} else if (ports.children.isNotEmpty()) {
				ports.children.clear()
			}

			Tooltip.install(
				root,
				Tooltip(
					buildString {
						append(activity.reference).append('\n')
						activity.procedureName?.let { append("Процедура: ").append(it).append('\n') }
						append("Тип: ").append(activity.type.name).append('\n')
						append("Прохождений: ").append(passCount).append('\n')
						append("Дата-документов: ").append(activity.dataDocuments.size)
					}
				),
			)
		}

		private fun portCircle(centerX: Double, centerY: Double): Circle =
			Circle(centerX, centerY, DiagramGeometry.PORT_RADIUS).apply {
				fill = Color.WHITE
				stroke = Color.web("#4d4d4d")
				strokeWidth = 1.1
				isMouseTransparent = false
			}
	}


	private inner class ConnectionView(val connection: ProcessConnection) {

		/** Узкая stroke-only hit-зона; fill отсутствует, поэтому внутренняя область polyline не кликабельна. */
		private val hitArea = Polyline().apply {
			stroke = Color.TRANSPARENT
			strokeWidth = DiagramGeometry.CONNECTION_HIT_WIDTH
			fill = null
			strokeLineCap = StrokeLineCap.ROUND
			isPickOnBounds = false
		}
		private val line = Polyline().apply {
			fill = Color.TRANSPARENT
			strokeLineJoin = StrokeLineJoin.MITER
			isMouseTransparent = true
		}
		/**
		 * Чёрная пунктирная рамка выбранной стрелки рисуется поверх основной линии.
		 * Основная линия остаётся на месте, поэтому между штрихами сохраняется её
		 * исходный цвет: градиент трассы либо обычный серо-чёрный цвет.
		 */
		private val selectionLine = Polyline().apply {
			fill = Color.TRANSPARENT
			stroke = Color.web("#202020")
			strokeLineJoin = StrokeLineJoin.MITER
			strokeLineCap = StrokeLineCap.BUTT
			strokeDashArray.setAll(7.0, 5.0)
			isMouseTransparent = true
			isVisible = false
		}
		private val arrow = Polygon().apply { isMouseTransparent = true }
		val root = Group(hitArea, line, selectionLine, arrow).apply { isPickOnBounds = false }
		private var currentPoints: List<DiagramGeometry.Point> = emptyList()

		init {
			hitArea.cursor = Cursor.MOVE
			root.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
				if (event.button != MouseButton.PRIMARY || !hitAt(event.sceneX, event.sceneY)) {
					return@addEventHandler
				}
				requestFocus()
				selectConnection(connection, additive = event.isControlDown || event.isShiftDown)
				invalidate()
				beginDrag(event)
				event.consume()
			}
			root.addEventHandler(MouseEvent.MOUSE_DRAGGED) { event ->
				continueDrag(event)
				event.consume()
			}
			root.addEventHandler(MouseEvent.MOUSE_RELEASED) { event ->
				finishDrag()
				event.consume()
			}
			root.setOnContextMenuRequested { event ->
				if (hitAt(event.sceneX, event.sceneY)) {
					showConnectionMenu(connection, event.screenX, event.screenY)
					event.consume()
				}
			}
		}

		private fun hitAt(sceneX: Double, sceneY: Double): Boolean {
			if (currentPoints.size < 2) return false
			val point = this@ProcessDiagramPane.sceneToLocal(sceneX, sceneY)
			val threshold = CONNECTION_PICK_TOLERANCE_PX / zoom.x.coerceAtLeast(0.01)
			return currentPoints.zipWithNext().any { (a, b) ->
				distanceToSegment(point.x, point.y, a.x, a.y, b.x, b.y) <= threshold
			}
		}

		fun update(points: List<DiagramGeometry.Point>, detailed: Boolean) {
			currentPoints = points
			val flat = ArrayList<Double>(points.size * 2)
			points.forEach {
				flat += it.x
				flat += it.y
			}
			line.points.setAll(flat)
			selectionLine.points.setAll(flat)
			hitArea.points.setAll(flat)
			// Hit-зона остаётся узкой и постоянной в экранных пикселях при любом zoom.
			hitArea.strokeWidth = DiagramGeometry.CONNECTION_HIT_WIDTH / zoom.x.coerceAtLeast(0.01)

			val selected = connection in selectedConnections
			val traceColor = connectionTraceColors[connection]

			// Основной цвет стрелки никогда не меняется из-за selection:
			// пройденные сохраняют цвет временного градиента, остальные — стандартный серый.
			line.stroke = traceColor ?: DiagramStyle.CONNECTION
			// Пройденный маршрут заметно жирнее обычных непройденных соединений.
			line.strokeWidth = if (traceColor != null) 2.4 else 1.2

			// Selection — отдельный чёрный пунктир поверх исходной линии. За счёт
			// промежутков пунктира исходный цвет остаётся виден и у цветных, и у серых стрелок.
			selectionLine.isVisible = selected
			selectionLine.strokeWidth = 3.0

			arrow.fill = line.stroke
			arrow.stroke = if (selected) Color.web("#202020") else line.stroke
			arrow.strokeWidth = if (selected) 1.6 else 1.0

			updateArrow(arrow, points, DiagramGeometry.ARROW_SIZE)
		}
	}

	private fun distanceToSegment(
		px: Double,
		py: Double,
		ax: Double,
		ay: Double,
		bx: Double,
		by: Double,
	): Double {
		val dx = bx - ax
		val dy = by - ay
		val lengthSquared = dx * dx + dy * dy
		if (lengthSquared == 0.0) return hypot(px - ax, py - ay)
		val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
		return hypot(px - (ax + t * dx), py - (ay + t * dy))
	}


	private data class TracePosition(
		val order: Int,
		val timestampMillis: Long?,
	)

	private data class ActivityTiming(
		val text: String,
		val tooltip: String,
	)

	private companion object {
		const val START_EXIT_REFERENCE = "Start"
		val TRACE_DB_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
		val CALL_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss.SSS")

		/** Ниже этого масштаба подписи и счётчики не строятся — экономия на мелком зуме. */
		const val DETAIL_ZOOM = 0.3

		/** Запас вокруг окна просмотра, чтобы при скролле не мигало. */
		const val CULL_MARGIN = 250.0

		const val SELECTION_INSET = 3.0

		val LABEL_UNDER_ICON_PADDING = Insets(ActivityIcons.SIZE, 0.0, 0.0, 0.0)

		/** Сдвиг окна просмотра меньше этого числа экранных пикселей игнорируется. */
		const val VIEWPORT_TOLERANCE = 4.0

		/** Клик без фактического drag не должен снапать блок к сетке. */
		const val DRAG_START_THRESHOLD_PX = 3.0

		/** Допуск hit/drop по кружкам в экранных пикселях. */
		const val CONNECTION_PORT_HIT_PADDING_PX = 4.0

		/** Стрелка выбирается только на самой линии плюс примерно два экранных пикселя. */
		const val CONNECTION_PICK_TOLERANCE_PX = 3.0

		/** Отступ подписи выхода вниз от горизонтального участка стрелки. */
		const val EXIT_LABEL_BELOW_GAP = 2.0

		/** Геометрия облачка с таймингом в правом нижнем углу активности. */
		const val TIMING_BUBBLE_MIN_WIDTH = 48.0
		const val TIMING_BUBBLE_HEIGHT = 20.0
		const val TIMING_BUBBLE_HORIZONTAL_PADDING = 7.0
		const val TIMING_BUBBLE_VERTICAL_PADDING = 3.0
	}
}
