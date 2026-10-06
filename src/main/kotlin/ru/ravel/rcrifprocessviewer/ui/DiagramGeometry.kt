package ru.ravel.rcrifprocessviewer.ui

import ru.ravel.rcrifprocessviewer.dto.Waypoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Геометрия схемы, синхронизированная с renderer'ом r-crif-layout-merger.
 *
 * В Layout.xml X/Y активности — центр блока. Видимая ширина фиксирована,
 * высота считается отдельно по количеству выходов в [ProcessDiagramPane].
 */
object DiagramGeometry {

	const val BLOCK_WIDTH = 120.0
	const val BLOCK_MIN_HEIGHT = 60.0

	const val PORT_RADIUS = 4.0
	const val OUTPUT_PORT_VERTICAL_SPACING = 12.0
	const val OUTPUT_PORT_EDGE_PADDING = 6.0
	const val EXIT_LABEL_GAP = 4.0
	const val EXIT_LABEL_FONT_SIZE = 8.0

	const val START_SIZE = 24.0

	/** Короткий прямой участок за портом до первого/последнего поворота. */
	const val PORT_STUB = 14.0

	/** Минимальный зазор между трассой стрелки и телом любого блока. */
	const val CONNECTION_BLOCK_CLEARANCE = 10.0

	private const val ROUTE_BEND_PENALTY = 12.0
	private const val ROUTE_LOCAL_SEARCH_MARGIN = 120.0
	private const val ROUTE_LOCAL_OBSTACLE_LIMIT = 8
	private const val ROUTE_ESCAPE_MARGIN = 24.0

	const val ARROW_SIZE = 8.0

	/** Полная ширина stroke-only hit-зоны стрелки в экранных пикселях. */
	const val CONNECTION_HIT_WIDTH = 5.0

	fun snap(value: Double): Double = value.roundToInt().toDouble()

	data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
		val centerX: Double get() = x + w / 2
		val centerY: Double get() = y + h / 2
		val right: Double get() = x + w
		val bottom: Double get() = y + h

		fun contains(px: Double, py: Double): Boolean = px in x..right && py in y..bottom

		fun intersects(other: Rect): Boolean =
			x < other.right && right > other.x && y < other.bottom && bottom > other.y

		fun expanded(padding: Double): Rect = Rect(
			x = x - padding,
			y = y - padding,
			w = w + padding * 2.0,
			h = h + padding * 2.0,
		)
	}

	data class Point(val x: Double, val y: Double)

	/**
	 * Стабильная ортогональная трасса между уже вычисленными line-anchor'ами.
	 *
	 * В отличие от старой версии сторона входа/выхода здесь не пересчитывается
	 * по положению split'ов. Поэтому при перетаскивании стрелки её концы больше
	 * не прыгают между верхом/низом/левым/правым краем блока и не рождают
	 * длинные «артефактные» сегменты.
	 *
	 * [source] всегда находится справа от выходного порта, [target] — слева от
	 * центра входа. Сохранённые DiagramSplit остаются обязательными точками.
	 */
	fun route(
		source: Point,
		target: Point,
		waypoints: List<Waypoint>,
		backwardDetourY: Double,
	): List<Point> {
		val sourceEscape = Point(source.x + PORT_STUB, source.y)
		val targetEscape = Point(target.x - PORT_STUB, target.y)
		val splitPoints = waypoints.map { Point(it.x, it.y) }

		val result = mutableListOf<Point>()
		result += source
		appendPoint(result, sourceEscape)

		if (splitPoints.isEmpty()) {
			if (sourceEscape.x <= targetEscape.x) {
				val middleX = (sourceEscape.x + targetEscape.x) / 2.0
				appendPoint(result, Point(middleX, sourceEscape.y))
				appendPoint(result, Point(middleX, targetEscape.y))
			} else {
				// Обратная связь: уходим над обоими блоками, чтобы горизонтальный
				// участок не проходил сквозь target/source.
				appendPoint(result, Point(sourceEscape.x, backwardDetourY))
				appendPoint(result, Point(targetEscape.x, backwardDetourY))
			}
			appendPoint(result, targetEscape)
			appendPoint(result, target)
			return dropCollinear(result)
		}

		// К первому split выходим горизонтально. Внутренние split'ы сохраняем;
		// если пользователь сместил их так, что появился диагональный отрезок,
		// добавляем ровно один elbow, продолжая направление предыдущего сегмента.
		appendOrthogonal(result, splitPoints.first(), horizontalFirst = true)
		for (index in 1 until splitPoints.size) {
			val horizontalFirst = lastSegmentIsHorizontal(result)
			appendOrthogonal(result, splitPoints[index], horizontalFirst)
		}

		// Последний подход к входу обязан закончиться горизонтальным сегментом
		// слева направо, как в r-crif-layout-merger.
		appendOrthogonal(result, targetEscape, horizontalFirst = false)
		appendPoint(result, target)
		return dropCollinear(result)
	}

	/**
	 * Динамическая трассировка между портами, повторяющая идею
	 * r-crif-layout-merger: сначала выводим линию за границу source/target,
	 * затем строим Manhattan-маршрут с зазором от всех блоков.
	 *
	 * Используется при перемещении блока и как fallback, когда сохранённые
	 * DiagramSplit приводят стрелку сквозь какой-либо блок.
	 */
	fun routeAvoidingBlocks(
		source: Point,
		target: Point,
		sourceBox: Rect,
		targetBox: Rect,
		blockBoxes: Collection<Rect>,
	): List<Point>? {
		val sourceEscape = Point(
			x = ceil(sourceBox.right + CONNECTION_BLOCK_CLEARANCE),
			y = round(source.y),
		)
		val targetEscape = Point(
			x = floor(targetBox.x - CONNECTION_BLOCK_CLEARANCE),
			y = round(target.y),
		)
		val obstacles = blockBoxes.map { it.expanded(CONNECTION_BLOCK_CLEARANCE) }
		val middle = routeAvoidingObstacles(sourceEscape, targetEscape, obstacles) ?: return null

		return dropCollinear(
			buildList {
				add(Point(round(source.x), round(source.y)))
				add(sourceEscape)
				addAll(middle)
				add(targetEscape)
				add(Point(round(target.x), round(target.y)))
			}.let(::dropConsecutiveDuplicates)
		)
	}

	/** True, если хотя бы один сегмент проходит через внутренность блока. */
	fun crossesAnyBlock(points: List<Point>, blockBoxes: Collection<Rect>): Boolean =
		points.zipWithNext().any { (a, b) ->
			blockBoxes.any { block -> segmentCrossesInterior(a, b, block) }
		}

	private fun routeAvoidingObstacles(
		start: Point,
		end: Point,
		obstacles: List<Rect>,
	): List<Point>? {
		val snappedStart = Point(round(start.x), round(start.y))
		val snappedEnd = Point(round(end.x), round(end.y))
		if (samePoint(snappedStart, snappedEnd)) return listOf(snappedStart, snappedEnd)

		fun normalized(points: List<Point>): List<Point> =
			dropCollinear(
				dropConsecutiveDuplicates(
					points.map { Point(round(it.x), round(it.y)) }
				)
			)

		fun score(points: List<Point>): Double {
			val distance = points.zipWithNext().sumOf { (a, b) ->
				abs(a.x - b.x) + abs(a.y - b.y)
			}
			return distance + (points.size - 2).coerceAtLeast(0) * ROUTE_BEND_PENALTY
		}

		val candidates = ArrayList<List<Point>>()
		fun addCandidate(points: List<Point>) {
			val candidate = normalized(points)
			if (candidate.size >= 2 && polylineAvoids(candidate, obstacles)) {
				candidates += candidate
			}
		}
		fun addCandidate(vararg points: Point) = addCandidate(points.toList())

		// Самые дешёвые варианты — два dog-leg и две центральные трассы.
		addCandidate(snappedStart, Point(snappedEnd.x, snappedStart.y), snappedEnd)
		addCandidate(snappedStart, Point(snappedStart.x, snappedEnd.y), snappedEnd)

		val midX = round((snappedStart.x + snappedEnd.x) / 2.0)
		val midY = round((snappedStart.y + snappedEnd.y) / 2.0)
		addCandidate(
			snappedStart,
			Point(midX, snappedStart.y),
			Point(midX, snappedEnd.y),
			snappedEnd,
		)
		addCandidate(
			snappedStart,
			Point(snappedStart.x, midY),
			Point(snappedEnd.x, midY),
			snappedEnd,
		)

		if (candidates.isNotEmpty()) return candidates.minBy(::score)
		if (obstacles.isEmpty()) return listOf(snappedStart, snappedEnd)

		val localSearchBounds = Rect(
			x = min(snappedStart.x, snappedEnd.x) - ROUTE_LOCAL_SEARCH_MARGIN,
			y = min(snappedStart.y, snappedEnd.y) - ROUTE_LOCAL_SEARCH_MARGIN,
			w = abs(snappedEnd.x - snappedStart.x) + ROUTE_LOCAL_SEARCH_MARGIN * 2.0,
			h = abs(snappedEnd.y - snappedStart.y) + ROUTE_LOCAL_SEARCH_MARGIN * 2.0,
		)
		val localObstacles = obstacles.asSequence()
			.filter { it.intersects(localSearchBounds) }
			.take(ROUTE_LOCAL_OBSTACLE_LIMIT)
			.toList()

		for (obstacle in localObstacles) {
			for (x in listOf(floor(obstacle.x), ceil(obstacle.right))) {
				addCandidate(
					snappedStart,
					Point(x, snappedStart.y),
					Point(x, snappedEnd.y),
					snappedEnd,
				)
			}
			for (y in listOf(floor(obstacle.y), ceil(obstacle.bottom))) {
				addCandidate(
					snappedStart,
					Point(snappedStart.x, y),
					Point(snappedEnd.x, y),
					snappedEnd,
				)
			}
		}

		if (candidates.isNotEmpty()) return candidates.minBy(::score)

		val outerMinX = floor(obstacles.minOf { it.x } - ROUTE_ESCAPE_MARGIN)
		val outerMinY = floor(obstacles.minOf { it.y } - ROUTE_ESCAPE_MARGIN)
		val outerMaxX = ceil(obstacles.maxOf { it.right } + ROUTE_ESCAPE_MARGIN)
		val outerMaxY = ceil(obstacles.maxOf { it.bottom } + ROUTE_ESCAPE_MARGIN)

		data class Escape(val side: Int, val point: Point)
		fun escapePoints(point: Point): List<Escape> = buildList {
			fun addEscape(side: Int, escape: Point) {
				if (polylineAvoids(listOf(point, escape), obstacles)) add(Escape(side, escape))
			}
			addEscape(0, Point(outerMinX, point.y)) // left
			addEscape(1, Point(outerMaxX, point.y)) // right
			addEscape(2, Point(point.x, outerMinY)) // top
			addEscape(3, Point(point.x, outerMaxY)) // bottom
		}

		fun perimeterConnect(from: Escape, to: Escape): List<List<Point>> {
			val a = from.point
			val b = to.point
			if (abs(a.x - b.x) < EPS || abs(a.y - b.y) < EPS) return listOf(listOf(a, b))

			val fromVertical = from.side == 0 || from.side == 1
			val toVertical = to.side == 0 || to.side == 1
			return when {
				fromVertical && !toVertical -> listOf(listOf(a, Point(a.x, b.y), b))
				!fromVertical && toVertical -> listOf(listOf(a, Point(b.x, a.y), b))
				fromVertical && toVertical -> listOf(
					listOf(a, Point(a.x, outerMinY), Point(b.x, outerMinY), b),
					listOf(a, Point(a.x, outerMaxY), Point(b.x, outerMaxY), b),
				)
				else -> listOf(
					listOf(a, Point(outerMinX, a.y), Point(outerMinX, b.y), b),
					listOf(a, Point(outerMaxX, a.y), Point(outerMaxX, b.y), b),
				)
			}
		}

		for (startEscape in escapePoints(snappedStart)) {
			for (endEscape in escapePoints(snappedEnd)) {
				for (outsidePart in perimeterConnect(startEscape, endEscape)) {
					addCandidate(
						buildList {
							add(snappedStart)
							addAll(outsidePart)
							add(snappedEnd)
						}
					)
				}
			}
		}

		return candidates.minByOrNull(::score)
	}

	private fun polylineAvoids(points: List<Point>, obstacles: List<Rect>): Boolean =
		points.zipWithNext().all { (a, b) ->
			obstacles.none { obstacle -> segmentCrossesInterior(a, b, obstacle) }
		}

	private fun segmentCrossesInterior(a: Point, b: Point, obstacle: Rect): Boolean {
		if (abs(a.x - b.x) < EPS) {
			if (a.x <= obstacle.x || a.x >= obstacle.right) return false
			val minY = min(a.y, b.y)
			val maxY = max(a.y, b.y)
			return maxY > obstacle.y && minY < obstacle.bottom
		}
		if (abs(a.y - b.y) < EPS) {
			if (a.y <= obstacle.y || a.y >= obstacle.bottom) return false
			val minX = min(a.x, b.x)
			val maxX = max(a.x, b.x)
			return maxX > obstacle.x && minX < obstacle.right
		}

		// Маршруты должны быть строго ортогональными.
		return true
	}

	private fun dropConsecutiveDuplicates(points: List<Point>): List<Point> {
		if (points.size < 2) return points
		return buildList {
			for (point in points) {
				if (lastOrNull()?.let { samePoint(it, point) } != true) add(point)
			}
		}
	}

	private fun samePoint(a: Point, b: Point): Boolean =
		abs(a.x - b.x) < EPS && abs(a.y - b.y) < EPS

	private fun appendOrthogonal(
		result: MutableList<Point>,
		target: Point,
		horizontalFirst: Boolean,
	) {
		val current = result.last()
		val sameX = abs(target.x - current.x) < EPS
		val sameY = abs(target.y - current.y) < EPS
		when {
			sameX || sameY -> appendPoint(result, target)
			horizontalFirst -> {
				appendPoint(result, Point(target.x, current.y))
				appendPoint(result, target)
			}
			else -> {
				appendPoint(result, Point(current.x, target.y))
				appendPoint(result, target)
			}
		}
	}

	private fun lastSegmentIsHorizontal(points: List<Point>): Boolean {
		if (points.size < 2) return true
		val a = points[points.lastIndex - 1]
		val b = points.last()
		return abs(a.y - b.y) < EPS
	}

	private fun appendPoint(result: MutableList<Point>, point: Point) {
		val previous = result.lastOrNull()
		if (previous == null || abs(previous.x - point.x) >= EPS || abs(previous.y - point.y) >= EPS) {
			result += point
		}
	}

	private fun dropCollinear(points: List<Point>): List<Point> {
		if (points.size < 3) return points
		val result = mutableListOf(points.first())
		for (index in 1 until points.lastIndex) {
			val previous = result.last()
			val current = points[index]
			val next = points[index + 1]
			val collinearX = abs(previous.x - current.x) < EPS && abs(current.x - next.x) < EPS
			val collinearY = abs(previous.y - current.y) < EPS && abs(current.y - next.y) < EPS
			if (!collinearX && !collinearY) {
				result += current
			}
		}
		result += points.last()
		return result
	}

	fun boundsOf(points: List<Point>): Rect {
		if (points.isEmpty()) return Rect(0.0, 0.0, 0.0, 0.0)
		val minX = points.minOf { it.x }
		val minY = points.minOf { it.y }
		return Rect(minX, minY, points.maxOf { it.x } - minX, points.maxOf { it.y } - minY)
	}

	private const val EPS = 1e-6
}
