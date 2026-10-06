package ru.ravel.rcrifprocessviewer.ui

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SplitPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TextArea
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Callback
import ru.ravel.rcrifprocessviewer.db.ActivityPassRepository
import ru.ravel.rcrifprocessviewer.db.DataDocumentValue
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.service.IdeaLightEdit

/**
 * Окно трейса/дата-документов активности.
 *
 * Кроме данных конкретного вызова окно умеет идти по фактической хронологии
 * процесса. При переходе к соседнему событию содержимое окна перестраивается,
 * а callback [onShowOnLayout] позволяет сразу показать эту активность на схеме.
 */
object DataDocumentsWindow {

	fun show(
		owner: Window?,
		requestNumber: String,
		procedureName: String,
		activity: ProcessActivity,
		repository: ActivityPassRepository,
		traceActivities: List<ProcessActivity> = emptyList(),
		initialTraceIndex: Int = -1,
		onShowOnLayout: ((ProcessActivity) -> Unit)? = null,
		/**
		 * ru-flow хранит в flow_trace.exit_name не настоящую подпись CRIF-стрелки (см.
		 * Interpreter.java), а generic "Next"/null. Переводит её в реальную подпись стрелки
		 * дизайнера для конкретного вызова (occurrence — 1-based, как ActivityCall.index)
		 * текущей (при навигации Пред./След. — меняющейся) активности.
		 */
		resolveExitLabel: ((current: ProcessActivity, occurrence: Int, rawExit: String?) -> String?)? = null,
	) {
		val stage = Stage().apply {
			initModality(Modality.NONE)
			if (owner != null) initOwner(owner)
		}
		val root = VBox(10.0).apply { padding = Insets(15.0) }
		stage.scene = Scene(root, 1040.0, 600.0)

		var currentTraceIndex = initialTraceIndex
			.takeIf { it in traceActivities.indices }
			?: traceActivities.indexOfLast { sameActivity(it, activity) }

		fun currentActivity(): ProcessActivity =
			traceActivities.getOrNull(currentTraceIndex) ?: activity

		fun renderCurrentActivity() {
			val current = currentActivity()
			val calls = runCatching {
				repository.loadActivityCalls(requestNumber, procedureName, current)
			}.getOrDefault(emptyList())

			val previousButton = Button("← Предыдущая активность").apply {
				isDisable = currentTraceIndex <= 0
				setOnAction {
					if (currentTraceIndex > 0) {
						currentTraceIndex--
						renderCurrentActivity()
						onShowOnLayout?.invoke(currentActivity())
					}
				}
			}
			val nextButton = Button("Следующая активность →").apply {
				isDisable = currentTraceIndex !in traceActivities.indices ||
					currentTraceIndex >= traceActivities.lastIndex
				setOnAction {
					if (currentTraceIndex in 0 until traceActivities.lastIndex) {
						currentTraceIndex++
						renderCurrentActivity()
						onShowOnLayout?.invoke(currentActivity())
					}
				}
			}
			val showButton = Button("Показать на лейауте").apply {
				isDisable = onShowOnLayout == null
				setOnAction { onShowOnLayout?.invoke(current) }
			}
			val tracePosition = Label(
				if (currentTraceIndex in traceActivities.indices) {
					"Трейс: ${currentTraceIndex + 1} / ${traceActivities.size}"
				} else {
					"Активность отсутствует в текущем трейсе"
				}
			)
			val navigation = HBox(8.0, previousButton, nextButton, showButton, tracePosition).apply {
				alignment = Pos.CENTER_LEFT
			}

			val header = Label(
				buildString {
					append("Активность: ").append(current.reference)
					append("   |   Тип: ").append(current.type.name)
					append("   |   Процедура: ").append(procedureName)
					append('\n')
					append("Заявка: ").append(requestNumber.ifBlank { "не указана" })
					append("   |   Вызовов: ").append(calls.size)
					append("   |   Дата-документов в свойствах: ").append(current.dataDocuments.size)
					append("   |   Источник: ").append(repository.sourceDescription)
				}
			)

			val body: javafx.scene.Node = if (calls.isEmpty()) {
				val declared = current.dataDocuments.map {
					DataDocumentValue(it.referenceName, it.access, null, null)
				}
				VBox(
					6.0,
					Label("Процесс по этой активности не проходил — показан состав из свойств"),
					table(declared),
				).apply { VBox.setVgrow(children[1], Priority.ALWAYS) }
			} else {
				TabPane().apply {
					tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
					calls.forEach { call ->
						tabs.add(
							Tab("Вызов ${call.index}").apply {
								content = callContent(
									startedAt = call.startedAt,
									finishedAt = call.finishedAt,
									exitName = resolveExitLabel?.invoke(current, call.index, call.exitName) ?: call.exitName,
									onStart = call.documentsOnStart,
									onExit = call.documentsOnExit,
									connectorInput = call.connectorInput,
									connectorOutput = call.connectorOutput,
									error = call.error,
								)
							}
						)
					}

					// Если эта активность встречалась в трейсе несколько раз, выбираем
					// вкладку вызова, соответствующую текущему occurrence в хронологии.
					val occurrence = traceOccurrence(traceActivities, currentTraceIndex, current)
					val callTabIndex = calls.indexOfFirst { it.index == occurrence }
					if (callTabIndex >= 0) selectionModel.select(callTabIndex)
				}
			}
			VBox.setVgrow(body, Priority.ALWAYS)

			root.children.setAll(navigation, header, body)
			stage.title = "Трейс — ${current.reference}"
		}

		renderCurrentActivity()
		stage.show()
	}

	private fun sameActivity(left: ProcessActivity, right: ProcessActivity): Boolean =
		left.reference.equals(right.reference, ignoreCase = true)

	private fun traceOccurrence(
		traceActivities: List<ProcessActivity>,
		traceIndex: Int,
		activity: ProcessActivity,
	): Int {
		if (traceIndex !in traceActivities.indices) return 1
		return traceActivities
			.take(traceIndex + 1)
			.count { sameActivity(it, activity) }
			.coerceAtLeast(1)
	}

	private fun callContent(
		startedAt: String?,
		finishedAt: String?,
		exitName: String?,
		onStart: List<DataDocumentValue>,
		onExit: List<DataDocumentValue>,
		connectorInput: String?,
		connectorOutput: String?,
		error: String?,
	): javafx.scene.Node {
		val info = Label(
			buildString {
				append("Старт: ").append(startedAt ?: "—")
				append("   |   Выход: ").append(finishedAt ?: "—")
				append("   |   По выходу: ").append(exitName ?: "—")
			}
		)

		// По имени дата-документа ищем его же значение на другой стороне вызова, чтобы двойной
		// клик мог сразу открыть diff "было -> стало", а не просто одно значение.
		val onStartByName = onStart.associateBy(DataDocumentValue::name)
		val onExitByName = onExit.associateBy(DataDocumentValue::name)

		val startPane = VBox(
			4.0, Label("Данные на старте этого вызова"),
			table(onStart) { name, ownValue -> onExitByName[name]?.value?.let { after -> ownValue to after } },
		).apply {
			padding = Insets(6.0)
			VBox.setVgrow(children[1], Priority.ALWAYS)
		}
		val exitPane = VBox(
			4.0, Label("Данные на выходе из этого вызова"),
			table(onExit) { name, ownValue -> onStartByName[name]?.value?.let { before -> before to ownValue } },
		).apply {
			padding = Insets(6.0)
			VBox.setVgrow(children[1], Priority.ALWAYS)
		}

		val documents = SplitPane(startPane, exitPane).apply {
			setDividerPositions(0.5)
		}
		val details = TabPane().apply {
			tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
			tabs += Tab("DataDocuments", documents)
			if (!connectorInput.isNullOrBlank()) tabs += payloadTab("ConnectorInput", connectorInput)
			if (!connectorOutput.isNullOrBlank()) tabs += payloadTab("ConnectorOutput", connectorOutput)
			if (!error.isNullOrBlank()) tabs += errorTab(error)
		}
		VBox.setVgrow(details, Priority.ALWAYS)

		return VBox(6.0, info, details).apply { padding = Insets(8.0) }
	}

	/** По аналогии с DataDocuments: та же таблица (без колонки "Значение"), значение — по двойному клику. */
	private fun payloadTab(title: String, value: String): Tab = Tab(
		title,
		table(listOf(DataDocumentValue(name = title, access = "—", value = value, updatedAt = null))),
	)

	private fun errorTab(value: String): Tab = Tab(
		"Ошибка",
		TextArea(value).apply {
			isEditable = false
			isWrapText = false
			style = "-fx-font-family: monospace;"
		},
	)

	/**
	 * [diffPair] — по имени дата-документа и его значению в этой таблице возвращает пару
	 * (было, стало), если у документа есть значение на другой стороне вызова. Когда пары нет
	 * (или колбэк не задан — payload-вкладки, ещё не пройденная активность), двойной клик
	 * просто открывает значение как раньше.
	 */
	private fun table(
		documents: List<DataDocumentValue>,
		diffPair: (name: String, ownValue: String) -> Pair<String, String>? = { _, _ -> null },
	): TableView<DataDocumentRow> {
		val nameColumn = TableColumn<DataDocumentRow, String>("Дата-документ").apply {
			cellValueFactory = Callback { it.value.name }
			prefWidth = 240.0
		}
		val accessColumn = TableColumn<DataDocumentRow, String>("Доступ").apply {
			cellValueFactory = Callback { it.value.access }
			prefWidth = 80.0
		}
		val updatedColumn = TableColumn<DataDocumentRow, String>("Обновлён").apply {
			cellValueFactory = Callback { it.value.updatedAt }
			prefWidth = 150.0
		}

		val rows = FXCollections.observableArrayList(
			documents.map { document ->
				DataDocumentRow(
					name = SimpleStringProperty(document.name),
					access = SimpleStringProperty(document.access),
					value = SimpleStringProperty(document.value ?: "—"),
					updatedAt = SimpleStringProperty(document.updatedAt ?: "—"),
				)
			}
		)

		val table = TableView(rows)
		// "Значение" сюда намеренно не входит: значения часто — многострочный XML, который
		// в ячейке всё равно нечитаем; двойной клик по строке открывает его в LightEdit целиком.
		table.columns.setAll(nameColumn, accessColumn, updatedColumn)
		table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
		table.placeholder = Label("В свойствах активности нет дата-документов")
		table.selectionModel.selectionMode = SelectionMode.MULTIPLE

		/*
		 * Двойной клик именно по строке (а не по пустому месту TableView) открывает значение
		 * дата-документа в IntelliJ IDEA: если на другой стороне вызова есть значение того же
		 * документа — сравнением "было / стало" (IDEA diff), иначе — просто LightEdit.
		 * Временные файлы создаются вне проекта, поэтому viewer ничего не меняет ни в layout, ни в БД.
		 */
		table.setRowFactory {
			TableRow<DataDocumentRow>().apply {
				setOnMouseClicked { event ->
					if (event.button != MouseButton.PRIMARY || event.clickCount != 2 || isEmpty) {
						return@setOnMouseClicked
					}
					val document = item ?: return@setOnMouseClicked
					val name = document.name.get().orEmpty()
					val content = document.value.get().orEmpty()
					if (content.isBlank()) return@setOnMouseClicked
					val pair = diffPair(name, content)

					runCatching {
						if (pair != null) {
							val (before, after) = pair
							IdeaLightEdit.openDiff(name, before, after)
						} else {
							IdeaLightEdit.open(name, content)
						}
					}.onFailure { error ->
						Alert(Alert.AlertType.WARNING).apply {
							title = "IntelliJ IDEA"
							headerText = if (pair != null) "Не удалось открыть сравнение в IDEA"
							else "Не удалось открыть дата-документ в LightEdit"
							contentText = error.message ?: error.javaClass.simpleName
						}.showAndWait()
					}
					event.consume()
				}
			}
		}

		val openSelectedItem = MenuItem("Открыть выделенные").apply {
			setOnAction { openSelected(table, diffPair) }
		}
		table.contextMenu = ContextMenu(openSelectedItem)
		// Копирование (⌘C) и выделение всего (⌘A) остаются доступны с клавиатуры — из меню
		// убран только их пункт, не сама функция.
		table.setOnKeyPressed { event ->
			if (!event.isShortcutDown) return@setOnKeyPressed
			when (event.code) {
				KeyCode.C -> copySelection(table)
				KeyCode.A -> table.selectionModel.selectAll()
				else -> return@setOnKeyPressed
			}
			event.consume()
		}
		return table
	}

	private fun copySelection(table: TableView<DataDocumentRow>) {
		val selected = table.selectionModel.selectedItems.filterNotNull()
		if (selected.isEmpty()) return
		val text = selected.joinToString(System.lineSeparator()) { row ->
			listOf(row.name.get(), row.access.get(), row.value.get(), row.updatedAt.get()).joinToString("\t")
		}
		Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
	}

	/**
	 * Оборачивает значения выделенных дата-документов в корневой <Data> — как их же формирует
	 * движок. Если хотя бы у части выделенных документов есть значение на другой стороне вызова
	 * (тот же [diffPair], что и у двойного клика), открывается сравнение "было / стало" по этому
	 * подмножеству — документы без пары на другой стороне в сравнение не попадают, но не мешают
	 * ему открыться для остальных. Если пары нет вообще ни у кого — как раньше, просто открытие.
	 */
	private fun openSelected(
		table: TableView<DataDocumentRow>,
		diffPair: (name: String, ownValue: String) -> Pair<String, String>?,
	) {
		val ownValues = mutableListOf<String>()
		val beforeValues = mutableListOf<String>()
		val afterValues = mutableListOf<String>()
		for (row in table.selectionModel.selectedItems.filterNotNull()) {
			val ownValue = row.value.get()?.takeIf { it.isNotBlank() && it != "—" } ?: continue
			ownValues += ownValue
			diffPair(row.name.get().orEmpty(), ownValue)?.let { (before, after) ->
				beforeValues += before
				afterValues += after
			}
		}
		if (ownValues.isEmpty()) return
		val diffing = beforeValues.isNotEmpty()

		runCatching {
			if (diffing) {
				IdeaLightEdit.openDiff("Data", wrapAsData(beforeValues), wrapAsData(afterValues))
			} else {
				IdeaLightEdit.open("Data", wrapAsData(ownValues))
			}
		}.onFailure { error ->
			Alert(Alert.AlertType.WARNING).apply {
				title = "IntelliJ IDEA"
				headerText = if (diffing) "Не удалось открыть сравнение выделенного в IDEA"
				else "Не удалось открыть выделенное в LightEdit"
				contentText = error.message ?: error.javaClass.simpleName
			}.showAndWait()
		}
	}

	private fun wrapAsData(values: List<String>): String =
		values.joinToString("\n", prefix = "<Data>\n", postfix = "\n</Data>")

	class DataDocumentRow(
		val name: SimpleStringProperty,
		val access: SimpleStringProperty,
		val value: SimpleStringProperty,
		val updatedAt: SimpleStringProperty,
	)
}
