package ru.ravel.rcrifprocessviewer.ui

import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity

/**
 * Точка расширения для правил обводки блоков.
 *
 * П.3 ТЗ: прежняя схема разделения обводок (раскраска блоков по diff'у
 * из LayoutMerger'а) убрана целиком. Новая схема, которую опишут позже,
 * добавляется отдельной реализацией этого интерфейса и подставляется
 * через [ProcessDiagramPane.outlineScheme] — трогать саму отрисовку не нужно.
 */
fun interface ActivityOutlineScheme {

	fun applyTo(box: Rectangle, activity: ProcessActivity, passCount: Int)
}


/**
 * П.5 ТЗ: пройденные активности — зелёная обводка, остальные — без обводки.
 */
class PassedActivityOutlineScheme : ActivityOutlineScheme {

	override fun applyTo(box: Rectangle, activity: ProcessActivity, passCount: Int) {
		if (passCount > 0) {
			box.stroke = DiagramStyle.PASSED_STROKE
			box.strokeWidth = 2.5
		} else {
			box.stroke = Color.TRANSPARENT
			box.strokeWidth = 0.0
		}
	}
}


/**
 * Заготовка под схему из п.3 ТЗ. Пока ведёт себя как [PassedActivityOutlineScheme];
 * заменить тело метода, когда правила будут описаны.
 */
class CustomActivityOutlineScheme : ActivityOutlineScheme {

	private val fallback = PassedActivityOutlineScheme()

	override fun applyTo(box: Rectangle, activity: ProcessActivity, passCount: Int) {
		// TODO: описать правила обводки блоков, когда они будут заданы.
		fallback.applyTo(box, activity, passCount)
	}
}
