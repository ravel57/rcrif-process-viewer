package ru.ravel.rcrifprocessviewer.service

import ru.ravel.rcrifprocessviewer.dto.ActivityType
import ru.ravel.rcrifprocessviewer.dto.ProcessActivity
import ru.ravel.rcrifprocessviewer.util.XmlReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Matches an activity id written by a compiled RU Flow package to an activity in a designer layout.
 * Different CRIF exports can rename an activity while retaining its connector/procedure metadata.
 */
object TraceActivityMatcher {

	data class Match(val activity: ProcessActivity, val exact: Boolean, val score: Double)

	fun find(traceReference: String, activities: List<ProcessActivity>): Match? {
		activities.lastOrNull { it.reference.equals(traceReference, ignoreCase = true) }?.let {
			return Match(it, exact = true, score = 1.0)
		}

		val expectedType = typeOf(traceReference)
		val traceTokens = semanticTokens(traceReference)
		if (traceTokens.size < 2) return null

		val ranked = activities.asSequence()
			.filter { expectedType == ActivityType.UNKNOWN || it.type == expectedType }
			.map { candidate ->
				val candidateTokens = candidateTokens(candidate)
				val covered = traceTokens.count(candidateTokens::contains).toDouble() / traceTokens.size
				val nameSimilarity = similarity(normalize(traceReference), normalize(candidate.reference))
				val score = maxOf(covered, nameSimilarity * 0.88)
				Match(candidate, exact = false, score = score)
			}
			.sortedByDescending(Match::score)
			.take(2)
			.toList()

		val best = ranked.firstOrNull() ?: return null
		val second = ranked.getOrNull(1)
		return best.takeIf {
			it.score >= 0.86 && (second == null || it.score - second.score >= 0.10)
		}
	}

	fun typeOf(reference: String): ActivityType {
		val prefix = reference.substringBefore('_').uppercase()
		return ActivityType.entries.firstOrNull { it.prefix == prefix } ?: ActivityType.UNKNOWN
	}

	private fun candidateTokens(activity: ProcessActivity): Set<String> {
		val key = (activity.dir?.absolutePath ?: "") + "\u0000" + activity.reference
		return TOKEN_CACHE.computeIfAbsent(key) {
			buildSet {
				addAll(semanticTokens(activity.reference))
				candidateMetadata(activity.dir).forEach { addAll(semanticTokens(it)) }
			}
		}
	}

	private fun candidateMetadata(directory: File?): List<String> {
		val properties = directory?.resolve("Properties.xml")?.takeIf(File::isFile) ?: return emptyList()
		val xml = runCatching { XmlReader.readXmlSafe(properties) }.getOrNull() ?: return emptyList()
		return METADATA_TAG.findAll(xml).map { it.groupValues[2] }.filter(String::isNotBlank).toList()
	}

	private fun semanticTokens(value: String): Set<String> = WORD.findAll(splitCamelCase(value))
		.map { it.value.lowercase() }
		.filter { token -> token.length > 1 && token !in IGNORED && token.any(Char::isLetter) }
		.toSet()

	private fun splitCamelCase(value: String): String = value
		.replace(ACRONYM_BOUNDARY, "$1 $2")
		.replace(WORD_BOUNDARY, "$1 $2")

	private fun normalize(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()

	private fun similarity(left: String, right: String): Double {
		if (left.isEmpty() || right.isEmpty()) return 0.0
		val previous = IntArray(right.length + 1) { it }
		for (i in left.indices) {
			var diagonal = previous[0]
			previous[0] = i + 1
			for (j in right.indices) {
				val above = previous[j + 1]
				previous[j + 1] = minOf(
					previous[j + 1] + 1,
					previous[j] + 1,
					diagonal + if (left[i] == right[j]) 0 else 1,
				)
				diagonal = above
			}
		}
		return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
	}

	private val METADATA_TAG = Regex(
		"""<(ConnectorName|ProcedureToCall|MnemonicId)(?:\s[^>]*)?>([^<]+)</\1>""",
		RegexOption.IGNORE_CASE,
	)
	private val ACRONYM_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
	private val WORD_BOUNDARY = Regex("([a-z0-9])([A-Z])")
	private val WORD = Regex("[A-Za-zА-Яа-я0-9]+")
	private val IGNORED = setOf(
		"br", "dm", "dr", "ds", "ep", "fm", "pc", "pr", "se", "sp", "st", "sv", "wa",
		"activity", "definition", "connector", "credit", "cards", "act",
	)
	private val TOKEN_CACHE = ConcurrentHashMap<String, Set<String>>()
}
