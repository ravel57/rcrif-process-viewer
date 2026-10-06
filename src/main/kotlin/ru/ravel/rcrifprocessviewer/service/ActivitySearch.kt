package ru.ravel.rcrifprocessviewer.service

import ru.ravel.rcrifprocessviewer.dto.ProcessActivity

/**
 * П.1 ТЗ: поиск блока по части названия.
 *
 * Поиск нечувствителен к регистру и умеет игнорировать разделители/лишние
 * символы между введёнными буквами и цифрами. Например, `fm7100` находит
 * `FM_0_7100_Preapprove`, а `0101Mapping` — `DM_01_01_MappingA`.
 *
 * Если запрос сам по себе является корректной регуляркой, она тоже применяется —
 * можно писать, например, `DM_01_.*Mapping[AB]`.
 */
object ActivitySearch {

	/** Короче — слишком много шума, поиск не запускаем. */
	const val MIN_QUERY_LENGTH = 2

	fun search(query: String, activities: List<ProcessActivity>): List<ProcessActivity> {
		val matcher = compile(query) ?: return emptyList()
		return activities
			.mapNotNull { activity ->
				val rank = matcher.rank(activity.reference)
				if (rank == null) null else rank to activity
			}
			.sortedWith(compareBy({ it.first }, { it.second.reference }))
			.map { it.second }
	}

	fun compile(query: String): Matcher? {
		val trimmed = query.trim()
		if (trimmed.length < MIN_QUERY_LENGTH) {
			return null
		}
		val normalized = normalize(trimmed)
		// Пользователь мог ввести настоящую регулярку — пробуем скомпилировать как есть.
		val explicit = runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull()
		if (normalized.isEmpty() && explicit == null) {
			return null
		}
		return Matcher(trimmed, normalized, explicit)
	}

	class Matcher(
		private val raw: String,
		private val normalizedQuery: String,
		private val explicit: Regex?,
	) {

		/**
		 * Меньше — точнее:
		 * 0 — обычное case-insensitive вхождение;
		 * 1 — вхождение после удаления разделителей;
		 * 2 — символы запроса встречаются в том же порядке с пропусками;
		 * 3 — явная пользовательская регулярка.
		 */
		fun rank(reference: String): Int? {
			if (reference.contains(raw, ignoreCase = true)) return 0

			if (normalizedQuery.isNotEmpty()) {
				val normalizedReference = normalize(reference)
				if (normalizedReference.contains(normalizedQuery)) return 1
				if (isSubsequence(normalizedQuery, normalizedReference)) return 2
			}

			if (explicit?.containsMatchIn(reference) == true) return 3
			return null
		}

		fun matches(reference: String): Boolean = rank(reference) != null
	}

	private fun normalize(value: String): String =
		value.asSequence()
			.filter { it.isLetterOrDigit() }
			.joinToString(separator = "")
			.lowercase()

	private fun isSubsequence(needle: String, haystack: String): Boolean {
		if (needle.isEmpty()) return false
		var index = 0
		for (char in haystack) {
			if (char == needle[index]) {
				index++
				if (index == needle.length) return true
			}
		}
		return false
	}
}
