package ru.ravel.rcrifprocessviewer.ui

import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import ru.ravel.rcrifprocessviewer.dto.ProcedureModel
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.flow.ActivityRef
import ru.ravel.rcrifprocessviewer.flow.ProcedureCatalog
import ru.ravel.rcrifprocessviewer.service.ActivitySearch

/** Выбор начала и конца кратчайшего пути: блоки процедуры и всех процедур, которые она вызывает. */
object PathDialog {

	data class Choice(val start: ActivityRef, val end: ActivityRef, val extraCallDepth: Int)

	private class DepthOption(val extraCallDepth: Int, val title: String) {
		override fun toString(): String = title
	}

	private val depthOptions = listOf(
		DepthOption(0, "Кратчайшая цепочка вызовов"),
		DepthOption(1, "Кратчайшая + 1 уровень вложенности"),
		DepthOption(2, "Кратчайшая + 2 уровня вложенности"),
		DepthOption(Int.MAX_VALUE, "Любая вложенность"),
	)

	private class Item(
		val ref: ActivityRef,
		val reference: String,
		val procedure: String,
		val title: String,
		val isProcedureStart: Boolean = false,
	) {
		override fun toString(): String = title
	}

	fun show(
		owner: Window?,
		root: ProcedureModel,
		catalog: ProcedureCatalog,
		preselectedEnd: ProcessActivity?,
	): Choice? {
		val procedureStart = root.startUid
			?.takeIf { uid -> root.connections.any { it.fromUid == uid } }
			?.let { Item(ActivityRef(root.name, it), "СТАРТ", root.name, "▶ СТАРТ процедуры", isProcedureStart = true) }

		// Сначала блоки самой процедуры, затем вложенных — с указанием, где они лежат.
		val activities = catalog.reachableFrom(root.name).flatMap { flow ->
			val isRoot = flow.name.equals(root.name, ignoreCase = true)
			flow.model.activities.sortedBy { it.reference.lowercase() }.map { activity ->
				Item(
					ref = ActivityRef(flow.name, activity.uid),
					reference = activity.reference,
					procedure = flow.name,
					title = if (isRoot) activity.reference else "${activity.reference}   ·   ${flow.name}",
				)
			}
		}

		val stage = Stage().apply {
			title = "Путь по процедуре ${root.name}"
			initModality(Modality.WINDOW_MODAL)
			if (owner != null) initOwner(owner)
		}

		val startPane = picker("Начало пути", listOfNotNull(procedureStart) + activities)
		val endPane = picker("Конец пути", activities)
		startPane.list.selectionModel.select(procedureStart ?: activities.firstOrNull())
		preselectedEnd?.let { end -> endPane.list.items.firstOrNull { it.ref.uid == end.uid && it.ref.procedure == root.name } }
			?.let { item ->
				endPane.list.selectionModel.select(item)
				endPane.list.scrollTo(item)
			}

		val depthBox = ComboBox(FXCollections.observableArrayList(depthOptions)).apply {
			selectionModel.selectFirst()
			tooltip = Tooltip(
				"Если блок лежит во вложенной процедуре, показывается кратчайший путь через её вызов.\n" +
					"Большая вложенность нужна, только если кратчайший путь идёт через цепочку вызовов длиннее минимальной.",
			)
		}

		var result: Choice? = null
		val showButton = Button("Показать путь").apply {
			isDefaultButton = true
			disableProperty().bind(
				startPane.list.selectionModel.selectedItemProperty().isNull
					.or(endPane.list.selectionModel.selectedItemProperty().isNull),
			)
			setOnAction {
				result = Choice(
					startPane.list.selectionModel.selectedItem.ref,
					endPane.list.selectionModel.selectedItem.ref,
					depthBox.selectionModel.selectedItem.extraCallDepth,
				)
				stage.close()
			}
		}
		val cancelButton = Button("Отмена").apply {
			isCancelButton = true
			setOnAction { stage.close() }
		}
		val options = HBox(8.0, Label("Вложенность вызовов:"), depthBox)
			.apply { alignment = Pos.CENTER_LEFT }
		val buttons = HBox(8.0, showButton, cancelButton).apply { alignment = Pos.CENTER_RIGHT }

		val root = VBox(
			10.0,
			HBox(12.0, startPane.box, endPane.box).apply { VBox.setVgrow(this, Priority.ALWAYS) },
			options,
			buttons,
		).apply {
			padding = Insets(16.0)
		}
		stage.scene = Scene(root, 760.0, 560.0)
		stage.showAndWait()
		return result
	}

	private class Picker(val box: VBox, val list: ListView<Item>)

	private fun picker(title: String, items: List<Item>): Picker {
		val filtered = FilteredList(FXCollections.observableArrayList(items)) { true }
		val filterField = TextField().apply { promptText = "Фильтр: блок или процедура" }
		filterField.textProperty().addListener { _, _, query ->
			val matcher = ActivitySearch.compile(query.orEmpty())
			val plain = query.orEmpty().trim()
			filtered.setPredicate { item ->
				matcher == null ||
					item.isProcedureStart ||
					matcher.rank(item.reference) != null ||
					item.procedure.contains(plain, ignoreCase = true)
			}
		}
		val list = ListView(filtered)
		VBox.setVgrow(list, Priority.ALWAYS)
		val box = VBox(6.0, Label(title), filterField, list).apply { HBox.setHgrow(this, Priority.ALWAYS) }
		box.prefWidth = 360.0
		return Picker(box, list)
	}
}
