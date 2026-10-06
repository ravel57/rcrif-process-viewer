package ru.ravel.rcrifprocessviewer.ui

import javafx.scene.paint.Color
import ru.ravel.rcrifprocessviewer.dto.ActivityType

/**
 * П.7 ТЗ: свой цвет для каждого типа блока.
 *
 * Заливка светлая, чтобы поверх неё читались и подпись, и зелёная обводка
 * пройденной активности, и чёрная рамка выделения.
 */
object ActivityPalette {

	private val fills = mapOf(
		ActivityType.FORM to Color.web("#d6e4fb"),
		ActivityType.BIZ_RULE to Color.web("#fde3cf"),
		ActivityType.SEGMENTATION_TREE to Color.web("#ecdcf7"),
		ActivityType.DATA_SOURCE to Color.web("#d2efdd"),
		ActivityType.DATA_MAPPING to Color.web("#cfeaf2"),
		ActivityType.SET_VALUE to Color.web("#fbeec2"),
		ActivityType.PROCEDURE_CALL to Color.web("#dcdcf6"),
		ActivityType.DISPATCH to Color.web("#fbd7de"),
		ActivityType.WAIT to Color.web("#e8e2d6"),
		ActivityType.PROCEDURE_RETURN to Color.web("#dfe6ea"),
		ActivityType.END_PROCEDURE to Color.web("#f0cdcd"),
		ActivityType.SEND_EMAIL to Color.web("#d9ecd0"),
		ActivityType.SET_PHASE to Color.web("#e6f0cd"),
		ActivityType.UNKNOWN to Color.web("#eceff1"),
	)

	fun fillFor(type: ActivityType): Color = fills[type] ?: fills.getValue(ActivityType.UNKNOWN)

	/** Чуть темнее заливки — тонкий контур, чтобы блок не сливался с фоном. */
	fun hairlineFor(type: ActivityType): Color = fillFor(type).darker()

	fun legend(): List<Pair<ActivityType, Color>> = ActivityType.values().map { it to fillFor(it) }
}
