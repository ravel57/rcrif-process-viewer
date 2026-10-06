package ru.ravel.rcrifprocessviewer.ui

import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.dto.Waypoint

/**
 * П.5 ТЗ: отмена и повтор действий на схеме.
 */
interface DiagramCommand {

	val title: String

	fun apply()

	fun revert()
}


class UndoManager {

	private val undoStack = ArrayDeque<DiagramCommand>()
	private val redoStack = ArrayDeque<DiagramCommand>()

	/** Вызывается после любого изменения стеков — на это подписан UI. */
	var onChange: (() -> Unit)? = null

	val canUndo: Boolean get() = undoStack.isNotEmpty()
	val canRedo: Boolean get() = redoStack.isNotEmpty()

	val undoTitle: String? get() = undoStack.lastOrNull()?.title
	val redoTitle: String? get() = redoStack.lastOrNull()?.title

	/** Команда уже применена к модели — просто кладём её в стек. */
	fun push(command: DiagramCommand) {
		undoStack.addLast(command)
		if (undoStack.size > LIMIT) {
			undoStack.removeFirst()
		}
		redoStack.clear()
		onChange?.invoke()
	}

	fun undo() {
		val command = undoStack.removeLastOrNull() ?: return
		command.revert()
		redoStack.addLast(command)
		onChange?.invoke()
	}

	fun redo() {
		val command = redoStack.removeLastOrNull() ?: return
		command.apply()
		undoStack.addLast(command)
		onChange?.invoke()
	}

	fun clear() {
		undoStack.clear()
		redoStack.clear()
		onChange?.invoke()
	}

	private companion object {
		const val LIMIT = 200
	}
}


class MovedActivity(
	val activity: ProcessActivity,
	val fromX: Double,
	val fromY: Double,
	val toX: Double,
	val toY: Double,
)


class MovedWaypoint(
	val waypoint: Waypoint,
	val fromX: Double,
	val fromY: Double,
	val toX: Double,
	val toY: Double,
)


/**
 * Перемещение блоков и/или точек излома стрелок одним действием:
 * мультивыбор двигается и отменяется целиком.
 */
class MoveCommand(
	private val activities: List<MovedActivity>,
	private val waypoints: List<MovedWaypoint>,
	private val afterApply: () -> Unit,
	private val afterRevert: () -> Unit = afterApply,
) : DiagramCommand {

	override val title: String = when {
		activities.isNotEmpty() && waypoints.isNotEmpty() -> "Перемещение элементов"
		waypoints.isNotEmpty() -> "Перемещение стрелки"
		activities.size == 1 -> "Перемещение блока"
		else -> "Перемещение блоков"
	}

	fun isEmpty(): Boolean = activities.isEmpty() && waypoints.isEmpty()

	override fun apply() {
		activities.forEach {
			it.activity.x = it.toX
			it.activity.y = it.toY
		}
		waypoints.forEach {
			it.waypoint.x = it.toX
			it.waypoint.y = it.toY
		}
		afterApply()
	}

	override fun revert() {
		activities.forEach {
			it.activity.x = it.fromX
			it.activity.y = it.fromY
		}
		waypoints.forEach {
			it.waypoint.x = it.fromX
			it.waypoint.y = it.fromY
		}
		afterRevert()
	}
}
