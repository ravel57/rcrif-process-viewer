package ru.ravel.rcrifprocessviewer.ui

import javafx.scene.image.Image
import ru.ravel.rcrifprocessviewer.dto.ActivityType

/**
 * PNG-иконки блоков по типу активности.
 *
 * Файлы лежат в ресурсах как `/icons/<префикс типа>.png` (`ds.png`, `br.png`, ...).
 * Нет файла — у блока нет иконки. Одна [Image] на тип делится всеми блоками схемы.
 */
object ActivityIcons {

	/** Экранный размер иконки в блоке при масштабе 1. */
	const val SIZE = 16.0

	/** Отступ иконки от края блока. */
	const val PADDING = 4.0

	/**
	 * Схема масштабируется аффинным Scale, поэтому растр грузится с запасом:
	 * при приближении до 4× картинка ещё не мылится.
	 */
	private const val RESOLUTION_FACTOR = 4.0

	private val cache = mutableMapOf<ActivityType, Image?>()

	fun imageFor(type: ActivityType): Image? = cache.getOrPut(type) { load(type) }

	private fun load(type: ActivityType): Image? {
		if (type.prefix.isEmpty()) {
			return null
		}
		val stream = ActivityIcons::class.java.getResourceAsStream("/icons/${type.prefix.lowercase()}.png") ?: return null
		return stream.use {
			Image(it, SIZE * RESOLUTION_FACTOR, SIZE * RESOLUTION_FACTOR, true, true)
		}.takeUnless { it.isError }
	}
}
