package ru.ravel.rcrifprocessviewer.ui

import javafx.scene.paint.Color

object DiagramStyle {

	val PASSED_STROKE: Color = Color.web("#1e8e3e")
	val PASSED_BADGE_FILL: Color = Color.web("#e6f4ea")
	val PASSED_BADGE_TEXT: Color = Color.web("#0f6b33")

	val IDLE_BADGE_FILL: Color = Color.web("#f1f3f4")
	val IDLE_BADGE_STROKE: Color = Color.web("#c4c7c5")
	val IDLE_BADGE_TEXT: Color = Color.web("#5f6368")

	/** Запасная заливка; основной цвет блока берётся из [ActivityPalette] (п.7 ТЗ). */
	val BOX_FILL: Color = Color.web("#f8f9fb")

	/** П.2 ТЗ: чёрная рамка выбранного блока и чёрная выбранная стрелка. */
	val SELECTION_STROKE: Color = Color.web("#000000")
	val BOX_TEXT: Color = Color.web("#202124")
	val CONNECTION: Color = Color.web("#9aa0a6")

	/**
	 * Цвет фактически пройденной стрелки. 0.0 — самое старое прохождение
	 * (синий), 0.5 — середина истории (зелёный), 1.0 — последнее (красный).
	 */
	fun processTraceColor(position: Double): Color =
		Color.hsb(240.0 * (1.0 - position.coerceIn(0.0, 1.0)), 0.82, 0.86)
	val EXIT_LABEL: Color = Color.web("#80868b")
	val START_FILL: Color = Color.web("#34a853")

	const val BADGE_RADIUS = 11.0
	const val PADDING = 40.0
	/** Нижняя граница опущена: у крупных процедур «Вписать» упиралась в неё и схема не помещалась. */
	const val MIN_ZOOM = 0.03
	const val MAX_ZOOM = 3.0
}
