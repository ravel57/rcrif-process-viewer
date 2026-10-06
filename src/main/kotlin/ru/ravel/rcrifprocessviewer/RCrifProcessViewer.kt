package ru.ravel.rcrifprocessviewer

import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.Separator
import javafx.scene.control.SplitPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Stage
import javafx.util.Callback
import javafx.util.Duration
import javafx.util.StringConverter
import ru.ravel.rcrifprocessviewer.config.AppConfig
import ru.ravel.rcrifprocessviewer.db.ActivityCall
import ru.ravel.rcrifprocessviewer.db.ActivityPassRepository
import ru.ravel.rcrifprocessviewer.db.JdbcActivityPassRepository
import ru.ravel.rcrifprocessviewer.db.ProcessTraceEvent
import ru.ravel.rcrifprocessviewer.db.RequestTraceEntry
import ru.ravel.rcrifprocessviewer.db.SqliteActivityPassRepository
import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcedureRef
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.service.ActivitySearch
import ru.ravel.rcrifprocessviewer.service.GitProcessRepository
import ru.ravel.rcrifprocessviewer.service.LayoutSaver
import ru.ravel.rcrifprocessviewer.service.ProcessLoader
import ru.ravel.rcrifprocessviewer.service.ProcessVersionOption
import ru.ravel.rcrifprocessviewer.service.TraceActivityMatcher
import ru.ravel.rcrifprocessviewer.service.XsltSandbox
import ru.ravel.rcrifprocessviewer.service.XsltSandboxTarget
import ru.ravel.rcrifprocessviewer.ui.DataDocumentsWindow
import ru.ravel.rcrifprocessviewer.ui.ProcessDiagramPane
import ru.ravel.rcrifprocessviewer.ui.SettingsWindow
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Просмотр прохождения кредитного процесса по конкретной заявке.
 *
 * Сделано на базе части с лейаутами из RCrifLayoutTool:
 *  - процесс читается из выбранной папки; локальный .git позволяет открыть любую локальную ветку без checkout;
 *  - разделённый экран убран, схема одна;
 *  - схема обводок вынесена за ActivityOutlineScheme;
 *  - кружок-счётчик и зелёная обводка у пройденных активностей;
 *  - счётчики приходят из ActivityPassRepository (БД);
 *  - двойной клик по кружку открывает экран дата-документов с группировкой по вызовам;
 *  - поле номера заявки рядом с выбором папки;
 *  - список процедур процесса слева;
 *  - блоки и стрелки двигаются и выделяются, есть отмена/повтор и мультивыбор рамкой.
 */
class RCrifProcessViewer : Application() {

	private var repository: ActivityPassRepository = createTraceRepository()
	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "process-loader").apply { isDaemon = true }
	}

	private val allProcedures = FXCollections.observableArrayList<ProcedureRef>()
	private val filteredProcedures = FilteredList(allProcedures) { true }

	private var loader: ProcessLoader? = null
	private var currentModel: ProcedureModel? = null
	private var selectedProcessFolder: File? = null
	private var gitProcessRepository: GitProcessRepository? = null
	private var activeProcessVersion: ProcessVersionOption = ProcessVersionOption.LOCAL
	private var processVersionRequestId = 0L
	private var processVersionSelectionGuard = false
	private var processVersionFilterRequestId = 0L

	private val requestNumberField = TextField()
	private val folderField = TextField()
	private val allProcessVersionItems = FXCollections.observableArrayList<ProcessVersionOption>()
	private val processVersionItems = FilteredList(allProcessVersionItems) { true }
	private val processVersionComboBox = ComboBox<ProcessVersionOption>()
	private val busyIndicator = ProgressIndicator()

	private val undoButton = Button("↶ Отменить")
	private val redoButton = Button("↷ Повторить")

	private val searchComboBox = ComboBox<ProcessActivity>()
	private val searchIndicator = ProgressIndicator()
	/** Защита от рекурсии: правка items/текста редактора не должна запускать новый поиск. */
	private var searchGuard = false
	private val searchDebounce = PauseTransition(Duration.millis(250.0))
	private var searchTask: Task<List<ProcessActivity>>? = null
	private val searchExecutor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "activity-search").apply { isDaemon = true }
	}

	private lateinit var diagram: ProcessDiagramPane
	private lateinit var diagramScroll: ScrollPane
	private lateinit var emptyDiagram: ProcessDiagramPane
	private lateinit var emptyDiagramScroll: ScrollPane
	private lateinit var proceduresList: ListView<ProcedureRef>
	private val requestTrace = FXCollections.observableArrayList<RequestTraceEntry>()
	private val filteredTrace = FilteredList(requestTrace) { true }
	private lateinit var traceTable: TableView<RequestTraceEntry>
	private val traceTitleLabel = Label("Трейс заявки")
	/** Номер заявки, для которого сейчас показана таблица трейса. */
	private var traceRequestNumber: String? = null
	private var traceRefreshInFlight = false

	/** Модели процедур для перевода "Выход" в таблице трейса — грузятся лениво, по имени процедуры. */
	private val exitLabelModels = ConcurrentHashMap<String, ProcedureModel?>()

	/** "Выход", переведённый в реальную подпись стрелки дизайнера (см. resolveExitLabels). */
	private val resolvedExitLabels = IdentityHashMap<RequestTraceEntry, String>()
	private lateinit var layoutTabs: TabPane
	private val openLayoutTabs = linkedMapOf<String, Tab>()
	private val loadingLayoutKeys = mutableSetOf<String>()
	private var syncingProcedureSelection = false
	/** Отбрасывает устаревшие runLater от предыдущих быстрых zoom-событий. */
	private var zoomRequestId = 0L
	private var autoRefreshTimeline: Timeline? = null
	/** Не ставим в очередь второй запрос истории для той же вкладки. */
	private val historyRefreshInFlight = mutableSetOf<String>()

	private data class LayoutTabState(
		val key: String,
		val ref: ProcedureRef,
		val model: ProcedureModel,
		val diagram: ProcessDiagramPane,
		val scroll: ScrollPane,
		var passCountsLoaded: Boolean = false,
		var passCounts: Map<String, Int> = emptyMap(),
		var traceEvents: List<ProcessTraceEvent> = emptyList(),
		var loadedRequestNumber: String? = null,
		var needsInitialFit: Boolean = true,
	)

	override fun start(stage: Stage) {
		emptyDiagram = createDiagramPane()
		emptyDiagramScroll = createDiagramScroll(emptyDiagram)
		diagram = emptyDiagram
		diagramScroll = emptyDiagramScroll

		val leftPanel = buildProceduresPanel()
		val mainSplit = SplitPane(leftPanel, buildDiagramPanel()).apply {
			orientation = Orientation.HORIZONTAL
			setDividerPositions(leftPanel.prefWidth / 1400.0)
		}
		SplitPane.setResizableWithParent(leftPanel, false)

		val root = BorderPane().apply {
			top = buildTopBar(stage)
			center = mainSplit
		}

		val scene = Scene(root, 1400.0, 900.0)
		installShortcuts(scene)

		stage.title = "RCrif Process Viewer — прохождение процесса по заявке"
		stage.scene = scene
		stage.show()

		startAutoRefresh()
		restoreLastSession()
	}

	override fun stop() {
		super.stop()
		AppConfig.save(folderField.text, requestNumberField.text)
		autoRefreshTimeline?.stop()
		executor.shutdownNow()
		searchExecutor.shutdownNow()
		(repository as? AutoCloseable)?.close()
		gitProcessRepository?.close()
	}

	private fun createDiagramPane(): ProcessDiagramPane =
		ProcessDiagramPane(onCounterDoubleClick = ::openDataDocuments).also { pane ->
			pane.onNavigateToActivity = ::focusActivity
			pane.onProcedureCallDoubleClick = ::openCalledProcedure
			pane.onDeleteConfirmationRequest = ::confirmDelete
			pane.activityCallsProvider = ::loadActivityCallsForMenu
			pane.onOpenInXsltSandbox = ::openInXsltSandbox
			wireUndoManager(pane)
		}

	/** Для сопоставления переименованных активностей берём сырой id из уже загруженного трейса. */
	private fun loadActivityCallsForMenu(procedureName: String, activity: ProcessActivity): List<ActivityCall> {
		val traceReference = currentLayoutState()?.traceEvents
			?.lastOrNull { it.activityReference.equals(activity.reference, ignoreCase = true) }
			?.traceActivityReference
		return repository.loadActivityCalls(
			requestNumberField.text.orEmpty().trim(),
			procedureName,
			activity.copy(traceReference = traceReference ?: activity.traceReference),
		)
	}

	private fun openInXsltSandbox(activity: ProcessActivity, call: ActivityCall, target: XsltSandboxTarget) {
		val executable = XsltSandbox.configuredExecutable() ?: chooseXsltSandboxExecutable() ?: return
		runCatching { XsltSandbox.launch(executable, activity, call, target) }
			.onFailure { error ->
				warn("Не удалось открыть ${activity.reference}/${target.fileName} в XSLT-sandbox: ${error.message}")
			}
	}

	private fun chooseXsltSandboxExecutable(): File? {
		val chosen = FileChooser().apply {
			title = "Укажите XSLTSandbox (запуск из меню активности)"
		}.showOpenDialog(folderField.scene?.window) ?: return null
		AppConfig.saveXsltSandboxPath(chosen.absolutePath)
		return chosen
	}

	private fun createDiagramScroll(pane: ProcessDiagramPane): ScrollPane =
		ScrollPane(Group(pane)).apply {
			isPannable = false
			style = "-fx-background: white;"
			wireViewportTracking(pane, this)
		}

	/* ---------------------------------------------------------------- верх */

	/** Поле номера заявки стоит рядом с выбором папки. */
	private fun buildTopBar(stage: Stage): Region {
		val saveButton = Button("Сохранить").apply {
			tooltip = Tooltip("Сохранить текущий Layout.xml")
			setOnAction { saveCurrentLayout() }
		}

		val settingsButton = Button().apply {
			graphic = Label("⚙").apply { style = "-fx-font-size: 16px;" }
			tooltip = Tooltip("Настройки")
			minWidth = 34.0
			prefWidth = 34.0
			setOnAction { SettingsWindow.show(stage, ::reloadTraceRepository) }
		}

		folderField.apply {
			isEditable = false
			promptText = "Папка кредитного процесса"
		}
		HBox.setHgrow(folderField, Priority.ALWAYS)

		val chooseFolderButton = Button("Выбрать папку").apply {
			setOnAction {
				val chooser = DirectoryChooser().apply {
					title = "Выберите папку кредитного процесса"
					val previous = folderField.text?.let { File(it) }
					if (previous?.exists() == true) {
						initialDirectory = previous
					}
				}
				chooser.showDialog(stage)?.let { selectProcessFolder(it) }
			}
		}

		processVersionComboBox.apply {
			items = processVersionItems
			prefWidth = 210.0
			isEditable = true
			isDisable = true
			promptText = "Введите название ветки"
			converter = object : StringConverter<ProcessVersionOption>() {
				override fun toString(value: ProcessVersionOption?): String =
					value?.title.orEmpty()

				override fun fromString(text: String?): ProcessVersionOption {
					val query = text.orEmpty().trim()
					return allProcessVersionItems.firstOrNull {
						it.title.equals(query, ignoreCase = true)
					} ?: processVersionItems.firstOrNull { !it.isLocal }
						?: activeProcessVersion
				}
			}

			editor.textProperty().addListener { _, _, text ->
				if (processVersionSelectionGuard) return@addListener

				/*
				 * Нельзя менять FilteredList прямо из listener'а editor.text:
				 * при клике по элементу popup JavaFX ещё находится внутри
				 * ListView.clearAndSelect(). Если в этот момент вызвать setPredicate(),
				 * selection model получает изменение списка посреди собственного
				 * Change-event и падает с IndexOutOfBoundsException.
				 *
				 * Фильтрацию откладываем на следующий JavaFX pulse. Последний ввод
				 * побеждает, старые runLater автоматически игнорируются.
				 */
				val requestId = ++processVersionFilterRequestId
				val query = text.orEmpty()
				Platform.runLater {
					if (
						requestId == processVersionFilterRequestId &&
						!processVersionSelectionGuard
					) {
						filterProcessVersions(query)
					}
				}
			}
			selectionModel.selectedItemProperty().addListener { _, _, selected ->
				if (!processVersionSelectionGuard && selected != null) {
					selectProcessVersion(selected)
				}
			}
		}

		requestNumberField.apply {
			promptText = "Номер заявки"
			prefWidth = 200.0
			setOnAction { reloadPassCounts() }
		}

		val applyButton = Button("Показать прохождение").apply {
			setOnAction { reloadPassCounts() }
		}
		val currentProcessPositionButton = Button().apply {
			graphic = Label("⌖").apply { style = "-fx-font-size: 16px;" }
			tooltip = Tooltip("Где процесс находится сейчас")
			minWidth = 34.0
			prefWidth = 34.0
			setOnAction { showCurrentProcessPosition() }
		}

		busyIndicator.apply {
			isVisible = false
			setPrefSize(18.0, 18.0)
			setMaxSize(18.0, 18.0)
		}

		return HBox(
			8.0,
			saveButton,
			settingsButton,
			Separator(Orientation.VERTICAL),
			Label("Папка процесса:"),
			folderField,
			chooseFolderButton,
			Separator(Orientation.VERTICAL),
			Label("Версия:"),
			processVersionComboBox,
			Separator(Orientation.VERTICAL),
			Label("Номер заявки:"),
			requestNumberField,
			applyButton,
			currentProcessPositionButton,
			busyIndicator,
		).apply {
			alignment = Pos.CENTER_LEFT
			padding = Insets(10.0)
		}
	}

	/* ------------------------------------------------------- список процедур */

	/** Вместо дерева изменений — список доступных в процессе процедур. */
	private fun buildProceduresPanel(): Region {
		val filterField = TextField().apply {
			promptText = "Фильтр процедур"
			textProperty().addListener { _, _, newValue ->
				filteredProcedures.setPredicate { procedure ->
					newValue.isNullOrBlank() || procedure.name.contains(newValue, ignoreCase = true)
				}
			}
		}

		proceduresList = ListView(filteredProcedures).apply {
			placeholder = Label("Выберите папку процесса")
			selectionModel.selectedItemProperty().addListener { _, _, selected ->
				if (selected != null && !syncingProcedureSelection) {
					openProcedure(selected)
				}
			}
		}
		VBox.setVgrow(proceduresList, Priority.ALWAYS)

		val proceduresBox = VBox(6.0, Label("Процедуры процесса"), filterField, proceduresList).apply {
			padding = Insets(10.0, 10.0, 6.0, 10.0)
		}

		// Левая панель поделена на две части: процедуры сверху, трейс заявки снизу.
		return SplitPane(proceduresBox, buildTracePanel()).apply {
			orientation = Orientation.VERTICAL
			setDividerPositions(0.5)
			prefWidth = 360.0
			minWidth = 220.0
		}
	}

	/** Хронологическая таблица вызовов активностей по заявке во всех процедурах. */
	private fun buildTracePanel(): Region {
		// Номер берётся из индекса строки: одинаковые записи трейса не должны путать нумерацию.
		val indexColumn = TableColumn<RequestTraceEntry, RequestTraceEntry>("#").apply {
			cellValueFactory = Callback { cell -> ReadOnlyObjectWrapper(cell.value) }
			setCellFactory {
				object : TableCell<RequestTraceEntry, RequestTraceEntry>() {
					override fun updateItem(item: RequestTraceEntry?, empty: Boolean) {
						super.updateItem(item, empty)
						text = if (empty || item == null) null else (index + 1).toString()
					}
				}
			}
			prefWidth = 44.0
			isSortable = false
		}
		val procedureColumn = traceColumn("Процедура", 110.0) { it.procedureName }
		val activityColumn = traceColumn("Активность", 150.0) { it.activityReference }
		val startedColumn = traceColumn("Начало", 110.0) { formatTraceTime(it.startedAt) }
		val exitColumn = traceColumn("Выход", 90.0) { traceExitText(it) }

		val filterField = TextField().apply {
			promptText = "Поиск по трейсу: FM_0_1100, PMID001, Error"
			textProperty().addListener { _, _, newValue ->
				val matcher = ActivitySearch.compile(newValue.orEmpty())
				filteredTrace.setPredicate { entry -> matcher == null || matchesTraceEntry(matcher, entry) }
				updateTracePlaceholder()
			}
		}

		traceTable = TableView(filteredTrace).apply {
			columns.setAll(indexColumn, procedureColumn, activityColumn, startedColumn, exitColumn)
			placeholder = Label("Укажите номер заявки")
			setRowFactory {
				object : TableRow<RequestTraceEntry>() {
					init {
						// Переход по клику мышью, а не по selection listener'у: автообновление
						// восстанавливает выделение и не должно само перебрасывать схему.
						setOnMouseClicked { event ->
							val entry = item
							if (event.button != MouseButton.PRIMARY || entry == null) return@setOnMouseClicked
							when (event.clickCount) {
								1 -> teleportToTraceEntry(entry)
								2 -> openTraceEntry(entry)
							}
							event.consume()
						}
					}

					override fun updateItem(entry: RequestTraceEntry?, empty: Boolean) {
						super.updateItem(entry, empty)
						if (empty || entry == null) {
							style = ""
							tooltip = null
							return
						}
						style = when {
							entry.failed -> "-fx-text-background-color: #c62828;"
							entry.finishedAt == null -> "-fx-font-weight: bold;"
							else -> ""
						}
						tooltip = Tooltip(
							buildString {
								append(entry.procedureName).append(" / ").append(entry.activityReference)
								append("\nНачало: ").append(entry.startedAt ?: "—")
								append("\nЗавершение: ").append(entry.finishedAt ?: "—")
								append("\nВыход: ").append(traceExitText(entry))
								append("\n\nКлик — показать на схеме, двойной клик — открыть данные трейса")
							},
						)
					}
				}
			}
		}
		VBox.setVgrow(traceTable, Priority.ALWAYS)

		return VBox(6.0, traceTitleLabel, filterField, traceTable).apply {
			padding = Insets(6.0, 10.0, 10.0, 10.0)
		}
	}

	/** Тот же "умный" нечувствительный-к-регистру/разделителям поиск, что и у блока схемы. */
	private fun matchesTraceEntry(matcher: ActivitySearch.Matcher, entry: RequestTraceEntry): Boolean =
		matcher.matches(entry.procedureName) ||
			matcher.matches(entry.activityReference) ||
			matcher.matches(formatTraceTime(entry.startedAt)) ||
			matcher.matches(traceExitText(entry))

	/** Отличает «для заявки нет данных» от «есть данные, но фильтр ничего не оставил». */
	private fun updateTracePlaceholder() {
		traceTable.placeholder = Label(
			if (requestTrace.isNotEmpty() && filteredTrace.isEmpty()) {
				"Нет строк трейса, соответствующих фильтру"
			} else if (traceRequestNumber == null) {
				"Укажите номер заявки"
			} else {
				"Для заявки нет данных в tracer'е"
			}
		)
	}

	private fun traceColumn(
		title: String,
		width: Double,
		value: (RequestTraceEntry) -> String,
	): TableColumn<RequestTraceEntry, String> =
		TableColumn<RequestTraceEntry, String>(title).apply {
			cellValueFactory = Callback { cell -> ReadOnlyStringWrapper(value(cell.value)) }
			prefWidth = width
			isSortable = false
		}

	private fun traceExitText(entry: RequestTraceEntry): String = when {
		!entry.exitName.isNullOrBlank() -> resolvedExitLabels[entry] ?: entry.exitName
		entry.failed -> "Error"
		entry.finishedAt == null -> "выполняется"
		else -> "—"
	}

	/**
	 * ru-flow хранит в flow_trace.exit_name не настоящую подпись CRIF-стрелки, а своё собственное
	 * имя: для CALL-шагов это вообще null (см. Interpreter.java — CALL просто передаёт управление,
	 * у него нет "выбранного" выхода), а для обычных MAPPING/XSLT/GROOVY/SETVALUE-шагов —
	 * generic "Next" вне зависимости от того, как этот же переход подписан в самой диаграмме
	 * (обычно "Completed"). Переводим "Выход" в таблице трейса в реальную подпись стрелки, когда
	 * можем её однозначно найти по паре (активность, следующая активность) в загруженной модели
	 * процедуры — так таблица показывает то же имя, что и на схеме, а не внутреннее имя движка.
	 */
	private fun resolveExitLabels(entries: List<RequestTraceEntry>): Map<RequestTraceEntry, String> {
		val result = IdentityHashMap<RequestTraceEntry, String>()
		entries.forEachIndexed { index, entry ->
			val raw = entry.exitName?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
			val next = entries.getOrNull(index + 1)
			val resolved = if (next != null && next.procedureName == entry.procedureName) {
				resolveConnectionExitLabel(entry.procedureName, entry.activityReference, next.activityReference, raw)
			} else {
				null
			}
			result[entry] = resolved ?: raw
		}
		return result
	}

	private fun resolveConnectionExitLabel(procedureName: String, from: String, to: String, rawExit: String): String? {
		val model = exitLabelModelFor(procedureName) ?: return null
		// activityReference в RequestTraceEntry — сырой activity_id прямо из flow_trace, без
		// сопоставления с конкретной версией layout (в отличие от ProcessTraceEvent, который
		// диаграмма строит уже через TraceActivityMatcher). CRIF-экспорт может переименовать
		// активность между версиями, так что точное совпадение строки тут часто не находится —
		// нужен тот же fuzzy-матчер, что и для раскраски диаграммы.
		val fromActivity = TraceActivityMatcher.find(from, model.activities)?.activity ?: return null
		val toActivity = TraceActivityMatcher.find(to, model.activities)?.activity ?: return null
		val candidates = model.connections.filter { it.fromUid == fromActivity.uid && it.toUid == toActivity.uid }
		if (candidates.isEmpty()) return null

		val normalizedRaw = rawExit.trim().lowercase()
		candidates.singleOrNull { it.exitName.orEmpty().trim().lowercase() == normalizedRaw }?.let { return it.exitName }
		if (candidates.size == 1) return candidates.single().exitName
		// Несколько по-разному подписанных стрелок сходятся в одной и той же следующей активности
		// (например, и "Completed", и "Failed" из PC_*_GetTimeOut ведут в один узел) — раз ru-flow
		// схлопнул их в generic "Next"/null, различить их нельзя. "Completed" — самая частая
		// реальная подпись успешного перехода в CRIF, поэтому это наиболее вероятный вариант.
		return candidates.firstOrNull { it.exitName.orEmpty().trim().equals("Completed", ignoreCase = true) }?.exitName
	}

	private fun exitLabelModelFor(procedureName: String): ProcedureModel? {
		exitLabelModels[procedureName]?.let { return it }
		val activeLoader = loader ?: return null
		// flow_trace хранит верхнеуровневые шаги MainFlow под синтетическим process_id вида
		// "PMID001", а не под именем "MainFlow" — то же сопоставление, что уже делает
		// navigateToProcessActivity() при переходе по трейсу.
		val ref = activeLoader.procedures().firstOrNull { candidate ->
			normalizeProcedureName(candidate.name) == normalizeProcedureName(procedureName)
		} ?: return null
		val model = runCatching { activeLoader.load(ref) }.getOrNull() ?: return null
		exitLabelModels[procedureName] = model
		return model
	}

	/** Instant из PostgreSQL показываем в локальной зоне, строки SQLite — как есть, но короче. */
	private fun formatTraceTime(value: String?): String {
		val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return "—"
		val local = runCatching { LocalDateTime.ofInstant(Instant.parse(text), ZoneId.systemDefault()) }.getOrNull()
			?: runCatching { LocalDateTime.parse(text, SQLITE_TIME_FORMAT) }.getOrNull()
			?: return text
		return local.format(TRACE_TIME_FORMAT)
	}

	/**
	 * Перечитывает трейс заявки. Таблица обновляется только при реальных изменениях,
	 * чтобы автообновление не сбрасывало выделение и прокрутку.
	 */
	private fun refreshRequestTrace(showBusy: Boolean) {
		val requestNumber = requestNumberField.text.orEmpty().trim()
		if (requestNumber.isBlank()) {
			traceRequestNumber = null
			requestTrace.clear()
			traceTitleLabel.text = "Трейс заявки"
			updateTracePlaceholder()
			return
		}
		if (traceRefreshInFlight) return
		traceRefreshInFlight = true
		val sourceRepository = repository
		runInBackground(
			showBusy = showBusy,
			work = {
				val entries = sourceRepository.loadRequestTrace(requestNumber)
				entries to resolveExitLabels(entries)
			},
			onDone = traceLoaded@ { (entries, labels) ->
				traceRefreshInFlight = false
				if (requestNumber != requestNumberField.text.orEmpty().trim() || sourceRepository !== repository) {
					return@traceLoaded
				}
				val requestChanged = traceRequestNumber != requestNumber
				traceRequestNumber = requestNumber
				traceTitleLabel.text = "Трейс заявки $requestNumber: вызовов — ${entries.size}"
				if (entries == requestTrace) return@traceLoaded

				// Индексы выделения/скролла у TableView относятся к видимому (отфильтрованному)
				// списку, а не к сырым entries — иначе активный фильтр собьёт и то, и другое.
				val wasAtEnd = filteredTrace.isEmpty() ||
					traceTable.selectionModel.selectedIndex == filteredTrace.lastIndex
				val selectedIndex = traceTable.selectionModel.selectedIndex
				resolvedExitLabels.clear()
				resolvedExitLabels.putAll(labels)
				requestTrace.setAll(entries)
				updateTracePlaceholder()
				if (!requestChanged && selectedIndex in filteredTrace.indices) {
					traceTable.selectionModel.select(selectedIndex)
				}
				if ((requestChanged || wasAtEnd) && filteredTrace.isNotEmpty()) {
					traceTable.scrollTo(filteredTrace.lastIndex)
				}
			},
			onFail = { error ->
				traceRefreshInFlight = false
				if (showBusy) {
					warn("Не удалось получить трейс заявки: ${error.message}")
				}
			},
		)
	}

	/**
	 * Асинхронный поиск блока по части названия в текущей процедуре.
	 * Ввод дебаунсится, сам поиск считается в фоне, предыдущая задача отменяется.
	 * Результаты подставляются в выпадающий список комбобокса.
	 */
	private fun buildSearchBox(): Region {
		searchComboBox.apply {
			isEditable = true
			prefWidth = 300.0
			promptText = "Поиск блока: 0101Mapping"
			converter = object : StringConverter<ProcessActivity>() {
				override fun toString(item: ProcessActivity?): String = item?.reference.orEmpty()
				override fun fromString(string: String?): ProcessActivity? =
					items.firstOrNull { it.reference.equals(string, ignoreCase = true) }
			}
			setCellFactory {
				object : ListCell<ProcessActivity>() {
					override fun updateItem(item: ProcessActivity?, empty: Boolean) {
						super.updateItem(item, empty)
						text = if (empty || item == null) null else "${item.reference}   ·   ${item.type.name}"
					}
				}
			}
			// Дебаунс вешаем на реальные нажатия клавиш, а не на textProperty: выбор элемента
			// из выпадающего списка тоже программно переписывает текст редактора (на строку
			// выбранного элемента) и не отличим от пользовательского ввода на уровне
			// textProperty, из-за чего список раскрывался заново после каждого клика по
			// результату. Клавиатурные события при программной подстановке текста не летят.
			editor.addEventHandler(KeyEvent.KEY_RELEASED) {
				if (!searchGuard) {
					searchDebounce.playFromStart()
				}
			}
			valueProperty().addListener { _, _, selected ->
				if (selected != null && !searchGuard) {
					// searchGuard остаётся включённым до следующего пульса UI, чтобы
					// перекрыть весь синхронный каскад этого изменения value — включая
					// собственный текст-синк ComboBox'а и реентрантный runSearch из
					// focusActivity() (см. комментарий в runSearch).
					searchDebounce.stop()
					searchGuard = true
					hide()
					focusActivity(selected)
					Platform.runLater { searchGuard = false }
				}
			}
		}

		searchDebounce.setOnFinished { runSearch(searchComboBox.editor.text.orEmpty()) }

		searchIndicator.apply {
			isVisible = false
			setPrefSize(16.0, 16.0)
			setMaxSize(16.0, 16.0)
		}

		return HBox(6.0, Label("Блок:"), searchComboBox, searchIndicator).apply {
			alignment = Pos.CENTER_LEFT
		}
	}

	private fun runSearch(query: String) {
		// focusActivity() (вызываемый при выборе элемента из этого же списка) синхронно
		// доходит через diagram.selectOnly(...) до обновления состояния диаграммы, которое
		// само дёргает runSearch с текущим текстом поля — но в этот момент сам ComboBox ещё
		// не успел переписать текст редактора на строку выбранного элемента. Поиск по ещё
		// не обновлённому тексту находит "несовпадение" с уже выбранным значением и
		// раскрывает список заново. Пока применяется программный выбор, поиск не запускаем.
		if (searchGuard) return
		searchTask?.cancel(true)
		val model = currentModel
		if (model == null || query.trim().length < ActivitySearch.MIN_QUERY_LENGTH) {
			publishSearchResults(emptyList())
			searchIndicator.isVisible = false
			return
		}
		searchIndicator.isVisible = true
		val task = object : Task<List<ProcessActivity>>() {
			override fun call(): List<ProcessActivity> = ActivitySearch.search(query, model.activities)
		}
		task.setOnSucceeded {
			searchIndicator.isVisible = false
			publishSearchResults(task.value)
		}
		task.setOnFailed {
			searchIndicator.isVisible = false
			publishSearchResults(emptyList())
		}
		task.setOnCancelled { searchIndicator.isVisible = false }
		searchTask = task
		searchExecutor.submit(task)
	}

	/** Подмена items сбрасывает и value, и текст редактора — поэтому под защитой. */
	private fun publishSearchResults(results: List<ProcessActivity>) {
		val typed = searchComboBox.editor.text
		val caret = searchComboBox.editor.caretPosition
		// Текущее значение и его текстовое представление нужно запомнить ДО clearSelection() —
		// иначе сравнение ниже всегда бессмысленно: сами же обнулили value двумя строками ниже.
		val selectedText = searchComboBox.value?.let { searchComboBox.converter.toString(it) }
		searchGuard = true
		searchComboBox.selectionModel.clearSelection()
		searchComboBox.items.setAll(results)
		searchComboBox.editor.text = typed
		searchComboBox.editor.positionCaret(caret.coerceIn(0, typed?.length ?: 0))
		searchGuard = false
		// runSearch перезапускается не только по вводу, но и фоновым авто-обновлением модели
		// (см. AUTO_REFRESH_INTERVAL), а выбор элемента сам переписывает текст редактора на его
		// строковое представление, снова вызывая этот же слушатель текста — оба случая не
		// должны раскрывать список заново: без фокуса нет смысла, а если текст уже в точности
		// равен строке текущего выбранного значения, значит это его собственный текст, а не
		// новый ввод пользователя.
		if (results.isEmpty() || !searchComboBox.editor.isFocused || typed == selectedText) {
			searchComboBox.hide()
		} else {
			searchComboBox.show()
		}
	}

	/** Выделяет блок и подводит к нему область просмотра. */
	private fun focusActivity(activity: ProcessActivity) {
		diagram.selectOnly(activity)
		val rect = diagram.paneRectOf(activity) ?: return
		val bounds = diagramScroll.viewportBounds
		if (bounds.width <= 0.0 || bounds.height <= 0.0) {
			return
		}
		val factor = diagram.zoomFactor
		setScroll(
			targetScrollX = rect.centerX * factor - bounds.width / 2,
			targetScrollY = rect.centerY * factor - bounds.height / 2,
		)
	}

	/* -------------------------------------------------------------- диаграмма */

	private fun buildDiagramPanel(): Region {
		// П.3 ТЗ: кнопки масштабируют относительно центра видимой области.
		val zoomInButton = Button("+").apply {
			setOnAction { zoomAroundViewportCenter(diagram.zoomFactor * ZOOM_STEP) }
		}
		val zoomOutButton = Button("−").apply {
			setOnAction { zoomAroundViewportCenter(diagram.zoomFactor / ZOOM_STEP) }
		}
		val zoomResetButton = Button("100%").apply {
			setOnAction { zoomAroundViewportCenter(1.0) }
		}
		val fitButton = Button("Вписать").apply {
			setOnAction {
				if (fitToWindow()) currentLayoutState()?.needsInitialFit = false
			}
		}

		// П.5 ТЗ: отмена/повтор.
		undoButton.apply {
			isDisable = true
			tooltip = Tooltip("Отменить (Ctrl+Z)")
			setOnAction { diagram.undoManager.undo() }
		}
		redoButton.apply {
			isDisable = true
			tooltip = Tooltip("Повторить (Ctrl+Shift+Z / Ctrl+Y)")
			setOnAction { diagram.undoManager.redo() }
		}

		val toolbar = HBox(
			8.0,
			undoButton,
			redoButton,
			Separator(Orientation.VERTICAL),
			Label("Масштаб:"),
			zoomOutButton,
			zoomResetButton,
			zoomInButton,
			fitButton,
			Separator(Orientation.VERTICAL),
			buildSearchBox(),
		).apply {
			alignment = Pos.CENTER_LEFT
			padding = Insets(0.0, 10.0, 8.0, 10.0)
		}

		layoutTabs = TabPane().apply {
			tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS
			selectionModel.selectedItemProperty().addListener { _, _, newTab ->
				if (newTab == null) {
					currentModel = null
					diagram = emptyDiagram
					diagramScroll = emptyDiagramScroll
					publishSearchResults(emptyList())
					refreshUndoButtons()
				} else {
					activateLayoutTab(newTab)
				}
			}
		}

		return BorderPane().apply {
			top = toolbar
			center = layoutTabs
		}
	}

	private fun installShortcuts(scene: Scene) {
		scene.accelerators[KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN)] =
			Runnable { diagram.undoManager.undo() }
		scene.accelerators[KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)] =
			Runnable { diagram.undoManager.redo() }
		scene.accelerators[KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN)] =
			Runnable { diagram.undoManager.redo() }
		scene.accelerators[KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN)] =
			Runnable { saveCurrentLayout() }
	}

	private fun wireUndoManager(targetDiagram: ProcessDiagramPane) {
		targetDiagram.undoManager.onChange = {
			if (::diagram.isInitialized && diagram === targetDiagram) {
				refreshUndoButtons()
			}
		}
	}

	private fun refreshUndoButtons() {
		undoButton.isDisable = !diagram.undoManager.canUndo
		redoButton.isDisable = !diagram.undoManager.canRedo
		undoButton.tooltip = Tooltip(
			diagram.undoManager.undoTitle?.let { "Отменить: $it (Ctrl+Z)" } ?: "Отменить (Ctrl+Z)"
		)
		redoButton.tooltip = Tooltip(
			diagram.undoManager.redoTitle?.let { "Повторить: $it (Ctrl+Shift+Z / Ctrl+Y)" }
				?: "Повторить (Ctrl+Shift+Z / Ctrl+Y)"
		)
	}

	/**
	 * Каждая вкладка держит собственные Pane/ScrollPane, поэтому zoom/scroll/undo
	 * не теряются при переключении. Здесь навешиваются обработчики конкретной пары.
	 */
	private fun wireViewportTracking(
		targetDiagram: ProcessDiagramPane,
		targetScroll: ScrollPane,
	) {
		targetScroll.hvalueProperty().addListener { _, _, _ -> pushViewport(targetDiagram, targetScroll) }
		targetScroll.vvalueProperty().addListener { _, _, _ -> pushViewport(targetDiagram, targetScroll) }
		targetScroll.viewportBoundsProperty().addListener { _, _, _ ->
			pushViewport(targetDiagram, targetScroll)
			if (::diagram.isInitialized && diagram === targetDiagram) {
				fitActiveTabIfNeeded()
			}
		}

		// Клик мимо схемы снимает выделение только в этой вкладке.
		targetScroll.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
			if (event.button != MouseButton.PRIMARY || event.isControlDown || event.isShiftDown) {
				return@addEventHandler
			}
			if (isOnScrollBar(event.target as? Node)) {
				return@addEventHandler
			}
			targetDiagram.clearSelection()
		}

		// Ctrl/Command + колесо масштабирует относительно курсора.
		targetScroll.addEventFilter(ScrollEvent.SCROLL) { event ->
			if (!event.isControlDown && !event.isMetaDown) {
				return@addEventFilter
			}
			val target = if (event.deltaY > 0) {
				targetDiagram.zoomFactor * ZOOM_STEP
			} else {
				targetDiagram.zoomFactor / ZOOM_STEP
			}
			zoomAtScene(target, event.sceneX, event.sceneY, targetDiagram, targetScroll)
			event.consume()
		}

		// Удержание колёсика — pan layout'а в экранных пикселях.
		var middlePanning = false
		var panStartSceneX = 0.0
		var panStartSceneY = 0.0
		var panStartScrollX = 0.0
		var panStartScrollY = 0.0
		var previousCursor = Cursor.DEFAULT

		targetScroll.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
			if (event.button != MouseButton.MIDDLE || isOnScrollBar(event.target as? Node)) {
				return@addEventFilter
			}
			val bounds = targetScroll.viewportBounds
			val factor = targetDiagram.zoomFactor
			panStartSceneX = event.sceneX
			panStartSceneY = event.sceneY
			panStartScrollX = targetScroll.hvalue * max(0.0, targetDiagram.contentWidth * factor - bounds.width)
			panStartScrollY = targetScroll.vvalue * max(0.0, targetDiagram.contentHeight * factor - bounds.height)
			middlePanning = true
			previousCursor = targetScroll.cursor ?: Cursor.DEFAULT
			targetScroll.cursor = Cursor.CLOSED_HAND
			targetDiagram.requestFocus()
			event.consume()
		}
		targetScroll.addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
			if (!middlePanning) return@addEventFilter
			setScroll(
				targetScrollX = panStartScrollX - (event.sceneX - panStartSceneX),
				targetScrollY = panStartScrollY - (event.sceneY - panStartSceneY),
				targetDiagram = targetDiagram,
				targetScroll = targetScroll,
			)
			event.consume()
		}
		targetScroll.addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
			if (!middlePanning || event.button != MouseButton.MIDDLE) return@addEventFilter
			middlePanning = false
			targetScroll.cursor = previousCursor
			event.consume()
		}
	}

	private tailrec fun isOnScrollBar(node: Node?): Boolean = when (node) {
		null -> false
		is ScrollBar -> true
		else -> isOnScrollBar(node.parent)
	}

	private fun pushViewport(
		targetDiagram: ProcessDiagramPane = diagram,
		targetScroll: ScrollPane = diagramScroll,
	) {
		val bounds = targetScroll.viewportBounds
		if (bounds.width <= 0.0 || bounds.height <= 0.0) return
		val factor = targetDiagram.zoomFactor
		val extraX = max(0.0, targetDiagram.contentWidth * factor - bounds.width)
		val extraY = max(0.0, targetDiagram.contentHeight * factor - bounds.height)
		val scrollX = targetScroll.hvalue.coerceIn(0.0, 1.0) * extraX
		val scrollY = targetScroll.vvalue.coerceIn(0.0, 1.0) * extraY
		targetDiagram.updateViewport(
			minX = scrollX / factor,
			minY = scrollY / factor,
			width = bounds.width / factor,
			height = bounds.height / factor,
		)
	}

	private fun zoomAroundViewportCenter(value: Double) {
		val bounds = diagramScroll.viewportBounds
		zoomAtViewport(value, bounds.width / 2, bounds.height / 2, diagram, diagramScroll)
	}

	private fun zoomAtViewport(
		value: Double,
		anchorX: Double,
		anchorY: Double,
		targetDiagram: ProcessDiagramPane,
		targetScroll: ScrollPane,
	) {
		val bounds = targetScroll.viewportBounds
		if (bounds.width <= 0.0 || bounds.height <= 0.0) {
			targetDiagram.zoomFactor = value
			Platform.runLater { pushViewport(targetDiagram, targetScroll) }
			return
		}
		val before = targetDiagram.zoomFactor
		val scrollX = targetScroll.hvalue * max(0.0, targetDiagram.contentWidth * before - bounds.width)
		val scrollY = targetScroll.vvalue * max(0.0, targetDiagram.contentHeight * before - bounds.height)
		val anchorInDiagramX = (scrollX + anchorX) / before
		val anchorInDiagramY = (scrollY + anchorY) / before
		applyAnchoredZoom(
			value, anchorInDiagramX, anchorInDiagramY, anchorX, anchorY, targetDiagram, targetScroll
		)
	}

	private fun zoomAtScene(
		value: Double,
		sceneX: Double,
		sceneY: Double,
		targetDiagram: ProcessDiagramPane,
		targetScroll: ScrollPane,
	) {
		val bounds = targetScroll.viewportBounds
		if (bounds.width <= 0.0 || bounds.height <= 0.0) {
			targetDiagram.zoomFactor = value
			Platform.runLater { pushViewport(targetDiagram, targetScroll) }
			return
		}
		val before = targetDiagram.zoomFactor
		val anchorInDiagram = targetDiagram.sceneToLocal(sceneX, sceneY)
		val scrollX = targetScroll.hvalue * max(0.0, targetDiagram.contentWidth * before - bounds.width)
		val scrollY = targetScroll.vvalue * max(0.0, targetDiagram.contentHeight * before - bounds.height)
		val viewportPoint = targetScroll.lookup(".viewport")?.sceneToLocal(sceneX, sceneY)
		val anchorX = viewportPoint?.x?.coerceIn(0.0, bounds.width)
			?: (anchorInDiagram.x * before - scrollX)
		val anchorY = viewportPoint?.y?.coerceIn(0.0, bounds.height)
			?: (anchorInDiagram.y * before - scrollY)
		applyAnchoredZoom(
			value = value,
			anchorInDiagramX = anchorInDiagram.x,
			anchorInDiagramY = anchorInDiagram.y,
			anchorX = anchorX,
			anchorY = anchorY,
			targetDiagram = targetDiagram,
			targetScroll = targetScroll,
		)
	}

	private fun applyAnchoredZoom(
		value: Double,
		anchorInDiagramX: Double,
		anchorInDiagramY: Double,
		anchorX: Double,
		anchorY: Double,
		targetDiagram: ProcessDiagramPane,
		targetScroll: ScrollPane,
	) {
		val before = targetDiagram.zoomFactor
		targetDiagram.zoomFactor = value
		val after = targetDiagram.zoomFactor
		if (after == before) return

		val targetScrollX = anchorInDiagramX * after - anchorX
		val targetScrollY = anchorInDiagramY * after - anchorY
		val requestId = ++zoomRequestId
		setScroll(targetScrollX, targetScrollY, targetDiagram, targetScroll)
		Platform.runLater {
			if (requestId != zoomRequestId) return@runLater
			targetScroll.applyCss()
			targetScroll.layout()
			setScroll(targetScrollX, targetScrollY, targetDiagram, targetScroll)
		}
	}

	private fun setScroll(
		targetScrollX: Double,
		targetScrollY: Double,
		targetDiagram: ProcessDiagramPane = diagram,
		targetScroll: ScrollPane = diagramScroll,
	) {
		val bounds = targetScroll.viewportBounds
		val factor = targetDiagram.zoomFactor
		val extraX = max(0.0, targetDiagram.contentWidth * factor - bounds.width)
		val extraY = max(0.0, targetDiagram.contentHeight * factor - bounds.height)
		targetScroll.hvalue = if (extraX > 0.0) (targetScrollX / extraX).coerceIn(0.0, 1.0) else 0.0
		targetScroll.vvalue = if (extraY > 0.0) (targetScrollY / extraY).coerceIn(0.0, 1.0) else 0.0
		pushViewport(targetDiagram, targetScroll)
	}

	private fun fitToWindow(
		targetDiagram: ProcessDiagramPane = diagram,
		targetScroll: ScrollPane = diagramScroll,
	): Boolean {
		if (targetDiagram.contentWidth <= 0.0 || targetDiagram.contentHeight <= 0.0) return false
		val viewport = targetScroll.viewportBounds
		if (viewport.width <= 0.0 || viewport.height <= 0.0) return false
		val availableWidth = max(1.0, viewport.width - FIT_MARGIN)
		val availableHeight = max(1.0, viewport.height - FIT_MARGIN)
		targetDiagram.zoomFactor = minOf(
			availableWidth / targetDiagram.contentWidth,
			availableHeight / targetDiagram.contentHeight,
		)
		setScroll(0.0, 0.0, targetDiagram, targetScroll)
		return true
	}

	private fun fitActiveTabIfNeeded() {
		val state = currentLayoutState() ?: return
		if (state.needsInitialFit && fitToWindow(state.diagram, state.scroll)) {
			state.needsInitialFit = false
		}
	}

	/* ------------------------------------------------------------- поведение */

	private fun restoreLastSession() {
		AppConfig.loadRequestNumber()?.let { requestNumberField.text = it }
		refreshRequestTrace(showBusy = false)
		AppConfig.loadFolder()
			?.let { File(it) }
			?.takeIf { it.isDirectory }
			?.let { selectProcessFolder(it, restoredBranch = AppConfig.loadProcessVersion()) }
	}

	/**
	 * @param restoredBranch ветка из прошлой сессии. Сначала открывается локальная версия,
	 * затем, если такая ветка есть в .git этой папки, переключаемся на неё.
	 */
	private fun selectProcessFolder(folder: File, restoredBranch: String? = null) {
		val canonicalFolder = runCatching { folder.canonicalFile }.getOrElse { folder.absoluteFile }
		val localLoader = ProcessLoader(canonicalFolder)
		if (!localLoader.looksLikeProcessRoot()) {
			warn("В папке ${canonicalFolder.absolutePath} нет ни MainFlow, ни Procedures — это не похоже на кредитный процесс.")
			return
		}

		processVersionRequestId++
		gitProcessRepository?.close()
		selectedProcessFolder = canonicalFolder
		gitProcessRepository = GitProcessRepository.find(canonicalFolder)
		folderField.text = canonicalFolder.absolutePath
		AppConfig.save(canonicalFolder.absolutePath, requestNumberField.text)

		val branches = gitProcessRepository?.let { repository ->
			runCatching { repository.localBranches() }
				.onFailure { error ->
					warn("Не удалось прочитать локальные ветки из .git: ${error.message}")
				}
				.getOrDefault(emptyList())
		}.orEmpty()
		configureProcessVersions(branches)
		val branchToRestore = restoredBranch?.takeIf { it in branches }
		// Пока ветка восстанавливается, сохранённое значение не затираем локальной версией.
		activateProcessLoader(localLoader, ProcessVersionOption.LOCAL, remember = branchToRestore == null)
		branchToRestore?.let { selectProcessVersion(ProcessVersionOption.branch(it)) }
	}

	/** Локальная версия всегда нулевая; далее идут только локальные git-ветки. */
	private fun configureProcessVersions(branches: List<String>) {
		allProcessVersionItems.setAll(
			buildList {
				add(ProcessVersionOption.LOCAL)
				branches.forEach { add(ProcessVersionOption.branch(it)) }
			},
		)
		processVersionComboBox.isDisable = branches.isEmpty()
		processVersionSelectionGuard = true
		processVersionFilterRequestId++
		try {
			processVersionItems.setPredicate { true }
			processVersionComboBox.selectionModel.select(ProcessVersionOption.LOCAL)
			processVersionComboBox.editor.text = ProcessVersionOption.LOCAL.title
		} finally {
			processVersionSelectionGuard = false
		}
	}

	/**
	 * Editable ComboBox используется именно как фильтр, а не как поле для создания
	 * произвольной ветки. Пункт 0 "Локальная версия" остаётся видимым всегда,
	 * остальные ветки фильтруются по частичному совпадению без учёта регистра.
	 *
	 * Важно: изменение predicate у FilteredList синхронно пересчитывает selection
	 * ComboBox. JavaFX при этом обновляет editor.text, а его listener снова попадает
	 * сюда. Поэтому эта функция вызывается только отложенно через Platform.runLater,
	 * уже после завершения текущего mouse/key event.
	 */
	private fun filterProcessVersions(text: String) {
		val query = text.trim()
		val editor = processVersionComboBox.editor

		// Это не пользовательский фильтр, а текст, который ComboBox сам записал
		// в editor после выбора ветки. Список в этот момент вообще не трогаем.
		val selected = processVersionComboBox.selectionModel.selectedItem
		if (selected != null && selected.title.equals(text, ignoreCase = false)) {
			return
		}

		val caret = editor.caretPosition.coerceIn(0, text.length)
		val anchor = editor.anchor.coerceIn(0, text.length)

		processVersionSelectionGuard = true
		try {
			// Мы уже вне обработчика клика popup, поэтому selection/list можно
			// изменять безопасно.
			processVersionComboBox.selectionModel.clearSelection()
			processVersionItems.setPredicate { option ->
				option.isLocal ||
					query.isEmpty() ||
					option.title.contains(query, ignoreCase = true)
			}

			if (editor.text != text) {
				editor.text = text
			}
			editor.selectRange(anchor, caret)
		} finally {
			processVersionSelectionGuard = false
		}

		if (editor.isFocused && !processVersionComboBox.isShowing) {
			processVersionComboBox.show()
		}
	}

	private fun resetProcessVersionFilter() {
		// Вызывается из участков, уже защищённых processVersionSelectionGuard.
		// Отдельно не трогаем editor, чтобы выбранная ветка штатно отображалась.
		processVersionItems.setPredicate { true }
	}

	private fun selectProcessVersion(version: ProcessVersionOption) {
		val folder = selectedProcessFolder ?: return
		if (version == activeProcessVersion) return

		val requestId = ++processVersionRequestId
		if (version.isLocal) {
			activateProcessLoader(ProcessLoader(folder), version)
			return
		}

		val gitRepository = gitProcessRepository
		if (gitRepository == null) {
			warn("Для выбранной папки не найден локальный .git")
			restoreActiveProcessVersionSelection()
			return
		}
		val branch = version.gitRef ?: return
		runInBackground(
			work = { gitRepository.snapshot(branch) },
			onDone = versionLoaded@ { snapshotRoot ->
				if (requestId != processVersionRequestId || selectedProcessFolder != folder) {
					return@versionLoaded
				}
				val branchLoader = ProcessLoader(snapshotRoot)
				if (!branchLoader.looksLikeProcessRoot()) {
					warn("В ветке $branch выбранная папка не содержит MainFlow/Procedures")
					restoreActiveProcessVersionSelection()
					return@versionLoaded
				}
				activateProcessLoader(branchLoader, version)
			},
			onFail = { error ->
				if (requestId == processVersionRequestId) {
					warn("Не удалось открыть ветку $branch: ${error.message}")
					restoreActiveProcessVersionSelection()
				}
			},
		)
	}

	private fun restoreActiveProcessVersionSelection() {
		processVersionSelectionGuard = true
		processVersionFilterRequestId++
		try {
			resetProcessVersionFilter()
			processVersionComboBox.selectionModel.select(activeProcessVersion)
			processVersionComboBox.editor.text = activeProcessVersion.title
		} finally {
			processVersionSelectionGuard = false
		}
	}

	private fun activateProcessLoader(
		processLoader: ProcessLoader,
		version: ProcessVersionOption,
		remember: Boolean = true,
	) {
		loader = processLoader
		exitLabelModels.clear()
		activeProcessVersion = version
		if (remember) {
			AppConfig.saveProcessVersion(version.gitRef)
		}
		processVersionSelectionGuard = true
		processVersionFilterRequestId++
		try {
			resetProcessVersionFilter()
			processVersionComboBox.selectionModel.select(version)
			processVersionComboBox.editor.text = version.title
		} finally {
			processVersionSelectionGuard = false
		}

		val procedures = processLoader.procedures()
		allProcedures.setAll(procedures)
		loadingLayoutKeys.clear()
		openLayoutTabs.clear()
		layoutTabs.tabs.clear()
		currentModel = null
		diagram = emptyDiagram
		diagramScroll = emptyDiagramScroll
		emptyDiagram.render(null, emptyMap())
		refreshUndoButtons()
		proceduresList.selectionModel.selectFirst()
	}

	/** Открывает ProcedureToCall, указанный в Properties.xml активности ProcedureCall. */
	private fun openCalledProcedure(activity: ProcessActivity) {
		val rawTarget = activity.procedureToCall?.trim().orEmpty()
		if (rawTarget.isEmpty()) {
			warn("У ${activity.reference} в Properties.xml не указан ProcedureToCall.")
			return
		}

		val targetName = rawTarget
			.trimEnd('/', '\\')
			.substringAfterLast('/')
			.substringAfterLast('\\')
		val target = allProcedures.firstOrNull { procedure ->
			procedure.name.equals(rawTarget, ignoreCase = true) ||
				procedure.name.equals(targetName, ignoreCase = true)
		}
		if (target == null) {
			warn("Процедура «$rawTarget» из ${activity.reference}/Properties.xml не найдена в Procedures.")
			return
		}

		if (target in filteredProcedures) {
			syncingProcedureSelection = true
			try {
				proceduresList.selectionModel.select(target)
				proceduresList.scrollTo(target)
			} finally {
				syncingProcedureSelection = false
			}
		}
		openProcedure(target)
	}

	private fun openProcedure(
		ref: ProcedureRef,
		onOpened: ((LayoutTabState) -> Unit)? = null,
	) {
		val processLoader = loader ?: return
		val key = layoutKey(ref)
		val openedTab = openLayoutTabs[key]
		if (openedTab != null) {
			if (layoutTabs.selectionModel.selectedItem === openedTab) {
				activateLayoutTab(openedTab)
			} else {
				layoutTabs.selectionModel.select(openedTab)
			}
			val openedState = openedTab.userData as? LayoutTabState
			if (openedState != null && onOpened != null) {
				Platform.runLater { onOpened(openedState) }
			}
			return
		}
		if (!loadingLayoutKeys.add(key)) return

		runInBackground(
			work = { processLoader.load(ref) },
			onDone = { model ->
				loadingLayoutKeys.remove(key)
				if (processLoader === loader) {
					val existing = openLayoutTabs[key]
					if (existing != null) {
						layoutTabs.selectionModel.select(existing)
						val existingState = existing.userData as? LayoutTabState
						if (existingState != null && onOpened != null) {
							Platform.runLater { onOpened(existingState) }
						}
					} else {
						val pane = createDiagramPane()
						val scroll = createDiagramScroll(pane)
						pane.render(model, emptyMap())
						val state = LayoutTabState(
							key = key,
							ref = ref,
							model = model,
							diagram = pane,
							scroll = scroll,
						)
						val tab = Tab(ref.name, scroll).apply {
							isClosable = true
							userData = state
							setOnClosed { openLayoutTabs.remove(key) }
						}
						openLayoutTabs[key] = tab
						layoutTabs.tabs += tab
						layoutTabs.selectionModel.select(tab)
						if (onOpened != null) {
							Platform.runLater { onOpened(state) }
						}
					}
				}
			},
			onFail = { error ->
				loadingLayoutKeys.remove(key)
				if (processLoader === loader) {
					warn("Не удалось прочитать ${ref.name}/Layout.xml: ${error.message}")
				}
			},
		)
	}

	private fun activateLayoutTab(tab: Tab) {
		val state = tab.userData as? LayoutTabState ?: return
		diagram = state.diagram
		diagramScroll = state.scroll
		currentModel = state.model

		if (state.ref in filteredProcedures && proceduresList.selectionModel.selectedItem != state.ref) {
			syncingProcedureSelection = true
			try {
				proceduresList.selectionModel.select(state.ref)
				proceduresList.scrollTo(state.ref)
			} finally {
				syncingProcedureSelection = false
			}
		}

		refreshUndoButtons()
		runSearch(searchComboBox.editor.text.orEmpty())
		Platform.runLater {
			if (currentLayoutState() === state) {
				fitActiveTabIfNeeded()
				pushViewport(state.diagram, state.scroll)
			}
		}
		val requestNumber = requestNumberField.text.orEmpty().trim()
		if (!state.passCountsLoaded || state.loadedRequestNumber != requestNumber) {
			renderCurrent()
		}
	}

	private fun currentLayoutState(): LayoutTabState? =
		layoutTabs.selectionModel.selectedItem?.userData as? LayoutTabState

	private fun layoutKey(ref: ProcedureRef): String =
		ref.dir.absoluteFile.toPath().normalize().toString().lowercase()

	private fun reloadPassCounts() {
		AppConfig.save(folderField.text, requestNumberField.text)
		refreshRequestTrace(showBusy = true)
		if (currentLayoutState() == null) return
		renderCurrent()
	}

	/**
	 * Берёт последнюю строку tracer'а по заявке, открывает указанную в ней процедуру,
	 * ставит масштаб ровно 100% и центрирует viewport на последней активности.
	 */
	private fun showCurrentProcessPosition() {
		val requestNumber = requestNumberField.text.orEmpty().trim()
		if (requestNumber.isBlank()) {
			warn("Укажите номер заявки")
			return
		}

		runInBackground(
			work = { repository.loadCurrentProcessPosition(requestNumber) },
			onDone = positionLoaded@ { position ->
				if (position == null) {
					warn("Для заявки $requestNumber нет данных в tracer'е")
					return@positionLoaded
				}

				navigateToProcessActivity(position.procedureName, position.activityReference)
			},
			onFail = { error ->
				warn("Не удалось определить текущее положение процесса: ${error.message}")
			},
		)
	}

	/** Открывает процедуру из tracer'а и центрирует схему на активности при масштабе 100%. */
	private fun navigateToProcessActivity(
		procedureName: String,
		activityReference: String,
		onMissing: () -> Unit = { showRuntimeTraceDetails(procedureName, activityReference) },
	) {
		val procedure = allProcedures.firstOrNull { candidate ->
			normalizeProcedureName(candidate.name) == normalizeProcedureName(procedureName)
		}
		if (procedure == null) {
			onMissing()
			return
		}

		openProcedure(procedure) { state ->
			val match = TraceActivityMatcher.find(activityReference, state.model.activities)
			val activity = match?.activity
			if (activity == null) {
				onMissing()
				return@openProcedure
			}
			showActivityAt100Percent(state, activity)
		}
	}

	private fun openTraceEntry(entry: RequestTraceEntry) {
		navigateToProcessActivity(entry.procedureName, entry.activityReference)
	}

	/** Одинарный клик только переносит схему; окно данных при промахе открывает двойной клик. */
	private fun teleportToTraceEntry(entry: RequestTraceEntry) {
		navigateToProcessActivity(entry.procedureName, entry.activityReference) {}
	}

	/**
	 * Все runtime-события остаются доступны, даже если выбранный проект дизайнера относится к другой
	 * версии и не содержит соответствующей процедуры или активности.
	 */
	private fun showRuntimeTraceDetails(procedureName: String, activityReference: String) {
		val activity = ProcessActivity(
			uid = "trace:$procedureName/$activityReference",
			reference = activityReference,
			type = TraceActivityMatcher.typeOf(activityReference),
			x = 0.0,
			y = 0.0,
			width = 120.0,
			height = 60.0,
			availableExits = emptyList(),
			procedureToCall = null,
			dir = null,
			dataDocuments = emptyList(),
			traceReference = activityReference,
		)
		DataDocumentsWindow.show(
			owner = folderField.scene?.window,
			requestNumber = requestNumberField.text.orEmpty().trim(),
			procedureName = procedureName,
			activity = activity,
			repository = repository,
		)
	}

	private fun normalizeProcedureName(value: String): String {
		val trimmed = value.trim()
		if (trimmed.equals("MainFlow", ignoreCase = true) || trimmed.startsWith("PMID", ignoreCase = true)) {
			return "mainflow"
		}
		return (if (trimmed.startsWith("PR_", ignoreCase = true)) trimmed.substring(3) else trimmed)
			.lowercase()
	}

	private fun createTraceRepository(): ActivityPassRepository {
		val settings = AppConfig.loadTraceDatabaseSettings()
		return if (settings.url.isBlank()) {
			SqliteActivityPassRepository()
		} else {
			JdbcActivityPassRepository(settings.url, settings.user, settings.password)
		}
	}

	private fun reloadTraceRepository() {
		val previous = repository
		runCatching { createTraceRepository() }
			.onSuccess { replacement ->
				repository = replacement
				(previous as? AutoCloseable)?.close()
				traceRequestNumber = null
				refreshRequestTrace(showBusy = true)
				renderCurrent()
			}
			.onFailure { error ->
				warn("Не удалось применить настройки БД трейсов: ${error.message}")
			}
	}

	private fun showActivityAt100Percent(state: LayoutTabState, activity: ProcessActivity) {
		val tab = openLayoutTabs[state.key] ?: return
		if (layoutTabs.selectionModel.selectedItem !== tab) {
			layoutTabs.selectionModel.select(tab)
		}

		// Автоматическое «Вписать» для только что открытой вкладки больше не должно
		// перетереть установленный здесь масштаб 100%.
		state.needsInitialFit = false
		state.diagram.zoomFactor = 1.0
		state.diagram.selectOnly(activity)

		Platform.runLater {
			val rect = state.diagram.paneRectOf(activity) ?: return@runLater
			val viewport = state.scroll.viewportBounds
			if (viewport.width <= 0.0 || viewport.height <= 0.0) return@runLater

			setScroll(
				targetScrollX = rect.centerX - viewport.width / 2.0,
				targetScrollY = rect.centerY - viewport.height / 2.0,
				targetDiagram = state.diagram,
				targetScroll = state.scroll,
			)
		}
	}

	/** Счётчики обновляются без повторного render(), чтобы состояние вкладки не сбрасывалось. */
	private fun renderCurrent() {
		val state = currentLayoutState() ?: return
		refreshLayoutHistory(
			state = state,
			requestNumber = requestNumberField.text.orEmpty().trim(),
			showBusy = true,
		)
	}

	/** Периодический refresh читает БД, не трогая zoom/scroll/undo и не мигая busy indicator'ом. */
	private fun startAutoRefresh() {
		autoRefreshTimeline?.stop()
		autoRefreshTimeline = Timeline(
			KeyFrame(AUTO_REFRESH_INTERVAL, {
				val state = currentLayoutState()
				val requestNumber = requestNumberField.text.orEmpty().trim()
				if (requestNumber.isNotBlank()) {
					refreshRequestTrace(showBusy = false)
				}
				if (state != null && requestNumber.isNotBlank()) {
					refreshLayoutHistory(state, requestNumber, showBusy = false)
				}
			}),
		).apply {
			cycleCount = Animation.INDEFINITE
			play()
		}
	}

	private fun refreshLayoutHistory(
		state: LayoutTabState,
		requestNumber: String,
		showBusy: Boolean,
	) {
		if (!historyRefreshInFlight.add(state.key)) return
		val model = state.model
		runInBackground(
			showBusy = showBusy,
			work = {
				val rawCounts = repository.loadPassCounts(
					requestNumber = requestNumber,
					procedureName = model.name,
					activityReferences = model.activities.map { it.reference },
				)
				val rawTraceEvents = repository.loadTraceEvents(
					requestNumber = requestNumber,
					procedureName = model.name,
				)
				val matches = (rawCounts.keys + rawTraceEvents.map { it.activityReference })
					.distinct()
					.associateWith { TraceActivityMatcher.find(it, model.activities)?.activity }
				val counts = linkedMapOf<String, Int>()
				rawCounts.forEach { (traceReference, count) ->
					val reference = matches[traceReference]?.reference ?: return@forEach
					counts[reference] = (counts[reference] ?: 0) + count
				}
				val traceEvents = rawTraceEvents.mapNotNull { event ->
					val activity = matches[event.activityReference] ?: return@mapNotNull null
					event.copy(
						activityReference = activity.reference,
						traceActivityReference = event.activityReference,
					)
				}
				counts to traceEvents
			},
			onDone = { (counts, traceEvents) ->
				historyRefreshInFlight.remove(state.key)
				state.diagram.updateProcessHistory(counts, traceEvents)
				state.passCountsLoaded = true
				state.passCounts = counts
				state.traceEvents = traceEvents
				state.loadedRequestNumber = requestNumber
				if (currentLayoutState() === state) {
					runSearch(searchComboBox.editor.text.orEmpty())
					pushViewport(state.diagram, state.scroll)
				}
			},
			onFail = { error ->
				historyRefreshInFlight.remove(state.key)
				if (showBusy) {
					warn("Не удалось получить данные о прохождении: ${error.message}")
				}
			},
		)
	}

	private fun saveCurrentLayout() {
		if (!activeProcessVersion.isLocal) {
			warn("Сохранение доступно только для пункта «Локальная версия». Git-ветки открываются как read-only snapshot.")
			return
		}
		val state = currentLayoutState()
		if (state == null) {
			warn("Нет открытого layout'а для сохранения.")
			return
		}
		runCatching {
			LayoutSaver.save(
				model = state.model,
				renderedWaypoints = state.diagram.connectionWaypointsForSave(),
				renderedActivitySizes = state.diagram.activitySizesForSave(),
			)
		}.onFailure { error ->
			warn("Не удалось сохранить ${state.model.name}/Layout.xml: ${error.message}")
		}
	}

	/** Двойной клик по кружку-счётчику. */
	private fun openDataDocuments(activity: ProcessActivity) {
		val state = currentLayoutState() ?: return
		val model = state.model
		val byReference = model.activities.associateBy { it.reference.trim().lowercase() }
		val traceActivities = state.traceEvents.mapNotNull { event ->
			byReference[event.activityReference.trim().lowercase()]?.copy(
				traceReference = event.traceActivityReference,
			)
		}
		val initialTraceIndex = traceActivities.indexOfLast { traced ->
			traced.reference.equals(activity.reference, ignoreCase = true)
		}

		DataDocumentsWindow.show(
			owner = folderField.scene?.window,
			requestNumber = requestNumberField.text.orEmpty().trim(),
			procedureName = model.name,
			activity = activity,
			repository = repository,
			traceActivities = traceActivities,
			initialTraceIndex = initialTraceIndex,
			onShowOnLayout = { target -> showActivityOnLayout(state, target) },
			resolveExitLabel = { current, occurrence, rawExit -> resolveCallExitLabel(model, traceActivities, current, occurrence, rawExit) },
		)
	}

	/**
	 * То же преобразование "Выход", что и для таблицы трейсов (см. resolveExitLabels), только
	 * здесь occurrence и следующая активность находятся по уже сматченному через
	 * TraceActivityMatcher списку traceActivities, а не по сырым строкам из flow_trace —
	 * поэтому связь A -> B ищется прямо по uid, без повторного fuzzy-матчинга.
	 */
	private fun resolveCallExitLabel(
		model: ProcedureModel,
		traceActivities: List<ProcessActivity>,
		current: ProcessActivity,
		occurrence: Int,
		rawExit: String?,
	): String? {
		val raw = rawExit?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		var seen = 0
		var toActivity: ProcessActivity? = null
		for (index in traceActivities.indices) {
			if (traceActivities[index].uid != current.uid) continue
			seen++
			if (seen == occurrence) {
				toActivity = traceActivities.getOrNull(index + 1)
				break
			}
		}
		val to = toActivity ?: return null
		val candidates = model.connections.filter { it.fromUid == current.uid && it.toUid == to.uid }
		if (candidates.isEmpty()) return null

		val normalizedRaw = raw.lowercase()
		candidates.singleOrNull { it.exitName.orEmpty().trim().lowercase() == normalizedRaw }?.let { return it.exitName }
		if (candidates.size == 1) return candidates.single().exitName
		return candidates.firstOrNull { it.exitName.orEmpty().trim().equals("Completed", ignoreCase = true) }?.exitName
	}

	/** Возвращает пользователя на вкладку, которой принадлежит открытое окно трейса. */
	private fun showActivityOnLayout(state: LayoutTabState, activity: ProcessActivity) {
		val tab = openLayoutTabs[state.key] ?: return
		if (layoutTabs.selectionModel.selectedItem !== tab) {
			layoutTabs.selectionModel.select(tab)
		}
		Platform.runLater {
			if (currentLayoutState() === state) focusActivity(activity)
		}
	}

	private fun <T> runInBackground(
		work: () -> T,
		onDone: (T) -> Unit,
		onFail: (Throwable) -> Unit,
		showBusy: Boolean = true,
	) {
		if (showBusy) busyIndicator.isVisible = true
		val task = object : Task<T>() {
			override fun call(): T = work()
		}
		task.setOnSucceeded {
			if (showBusy) busyIndicator.isVisible = false
			onDone(task.value)
		}
		task.setOnFailed {
			if (showBusy) busyIndicator.isVisible = false
			onFail(task.exception ?: RuntimeException("неизвестная ошибка"))
		}
		executor.submit(task)
	}

	private fun confirmDelete(message: String): Boolean {
		val alert = Alert(
			Alert.AlertType.CONFIRMATION,
			message,
			ButtonType.OK,
			ButtonType.CANCEL,
		).apply {
			headerText = "Подтверждение удаления"
			folderField.scene?.window?.let { initOwner(it) }
		}
		return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
	}

	private fun warn(message: String) {
		Alert(Alert.AlertType.WARNING, message).apply {
			headerText = null
			show()
		}
	}

	private companion object {
		const val ZOOM_STEP = 1.2
		val AUTO_REFRESH_INTERVAL: Duration = Duration.seconds(3.0)

		/** Небольшой отступ, чтобы после «Вписать» схема не упиралась в края. */
		const val FIT_MARGIN = 24.0

		val TRACE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
		val SQLITE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
	}
}


fun main() {
	Application.launch(RCrifProcessViewer::class.java)
}
