package ru.ravel.rcrifprocessviewer.debug

import java.time.Instant

/** Шаг отладчика xslt-sandbox: активность выполнена по кнопке «next activity». */
data class DebugStep(
	/** Папка процедуры: MainFlow или имя из Procedures. */
	val procedure: String,
	/** Выполненная активность (ReferenceName). */
	val activity: String,
	val mode: String,
	val exit: String?,
	/** Активность, на которую перешёл отладчик; null — шаг был последним. */
	val next: String?,
	val docsIn: String?,
	val docsOut: String?,
	val receivedAt: Instant = Instant.now(),
)

/**
 * Все полученные шаги отладки. Потокобезопасно: пишет поток сокета, читает JavaFX.
 * Активность считается «отлаженной» по паре (процедура, имя), без учёта регистра.
 */
class DebugSteps {

	private val steps = ArrayList<DebugStep>()

	@Synchronized
	fun add(step: DebugStep) {
		steps += step
	}

	@Synchronized
	fun clear() {
		steps.clear()
	}

	/** Шаги конкретной активности в порядке получения. */
	@Synchronized
	fun stepsFor(procedure: String, activity: String): List<DebugStep> =
		steps.filter { keyOf(it.procedure, it.activity) == keyOf(procedure, activity) }

	/** Ключи [keyOf] всех активностей, по которым есть шаги. */
	@Synchronized
	fun markKeys(): Set<String> = steps.mapTo(HashSet()) { keyOf(it.procedure, it.activity) }

	companion object {
		fun keyOf(procedure: String, activity: String): String =
			procedure.trim().lowercase() + "/" + activity.trim().lowercase()
	}
}
