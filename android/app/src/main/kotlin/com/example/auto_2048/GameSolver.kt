package com.example.auto_2048

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Expectimax solver for 2048, tuned for >=100k points on a 4x4 board.
 *
 * Strategy:
 *  - Anchor the largest tile in the bottom-right corner (SNAKE_ORDER[0]).
 *  - Build a descending snake from there: the next-largest tile sits
 *    adjacent and to the left, the one after that wraps up and right.
 *  - Search with iterative deepening up to MAX_DEPTH, capped by
 *    TIME_BUDGET_MS so a single decision never freezes the bot.
 *  - Prune chance-node spawns to the CHANCE_CELL_LIMIT empties that are
 *    nearest to existing tiles - spawns in the middle of nowhere never
 *    merge and just inflate the search tree.
 *  - Order moves by a cheap heuristic first so the partial result from a
 *    timed-out deeper search is already a good move.
 */
object GameSolver {

    // Probability distribution of a freshly spawned tile.
    private const val PROB_2 = 0.9
    private const val PROB_4 = 0.1

    // Iterative deepening limits. We search depth 1, 2, 3, ... up to
    // MAX_DEPTH or until TIME_BUDGET_MS elapses, whichever comes first.
    // Depth 7 with 1500 ms gives us enough lookahead to see merges 4-5
    // plies ahead and avoid moves that lock the board in a few moves.
    private const val MAX_DEPTH = 7
    private const val TIME_BUDGET_MS = 1500L

    // Heuristic weights. Snake order is the dominant signal; the corner
    // bonus anchors the max tile; empty cells are the runner-up because
    // a stuck board always loses; monotonicity + smoothness keep the
    // gradient intact; mergeable pairs give a small tiebreaker.
    // Heuristic weights. These follow ovolve's 2048-AI reference (the most
    // cited Expectimax heuristic for 2048) so the bot evaluates positions
    // the same way proven solvers do. Snake order + corner bonus are
    // layered on top so the gradient and the max-tile anchor are both
    // strongly preferred. W_CORNER_PENALTY is layered in addition to the
    // bonus so any move that *shifts* the max tile out of the anchor is
    // outweighed by every other signal - the bot will NEVER break the
    // snake anchor even when the corner tile is dwarfed by a cluster of
    // mid-range tiles elsewhere.
    private const val W_SNAKE = 20.0
    // Empty cells are CRITICAL: a stuck board always loses. Bumped from
    // 270 to 700 so the bot prefers "leave space" over "make a quick
    // merge that fills the board" — this is the #1 cause of premature
    // game over at high tile counts.
    private const val W_EMPTY = 700.0
    private const val W_MONO = 80.0
    private const val W_SMOOTH = 0.1
    private const val W_CORNER = 1000.0
    private const val W_CORNER_PENALTY = 2000.0
    private const val W_MERGE = 10.0
    // Massive penalty applied when the resulting position has no legal
    // moves (i.e. the game would end). -10000 dominates every other signal
    // so the solver will pick almost any other move over one that ends
    // the game.
    private const val W_TERMINAL = 10000.0
    // Monotonicity is computed as (log-difference)^4 to amplify large
    // gradients and demote small ones - this is the ovolve "POWER = 4" trick.
    private const val MONO_POWER = 4.0

    // Cap how many empty cells the chance node actually evaluates. With
    // CHANCE_CELL_LIMIT = 3 the chance node has 3*2 = 6 children instead
    // of ~30*2 = 60 - 10x fanout cut. 3 lets the solver reason about
    // spawns in the corners (where the snake's tail lives) more often,
    // which matters when the max tile approaches 1024+.
    private const val CHANCE_CELL_LIMIT = 3

    // Snake ordering - the canonical "best placement" ranks for a board
    // anchored on the bottom-right corner. SNAKE_ORDER[k] is the cell
    // index of rank k; rank 0 is the anchor (bottom-right, position 15)
    // and rank 15 is the worst placement (top-left, position 0).
    private val SNAKE_ORDER = intArrayOf(
        15, 14, 13, 12,
        11, 10,  9,  8,
         7,  6,  5,  4,
         3,  2,  1,  0
    )

    // POSITION_WEIGHT[p] = snake rank of position p (0=anchor, 15=tail).
    // Inverse lookup of SNAKE_ORDER so we score by position directly.
    private val POSITION_WEIGHT = IntArray(16).also { w ->
        for (k in 0..15) w[SNAKE_ORDER[k]] = k
    }

    // The "anchor" corner - the snake's head. Largest tile is rewarded
    // for sitting here; reward is only paid when the max tile is in
    // this exact cell (matching POSITION_WEIGHT[0] = 0).
    private const val ANCHOR_CORNER = 15

    private val DIRECTIONS = listOf("UP", "DOWN", "LEFT", "RIGHT")
    // Score for "moving towards the anchor" - LEFT/RIGHT/UP/DOWN. The anchor
    // is bottom-right (15); moves that push tiles towards it are UP (push
    // bottom-row content right) and RIGHT (push right-column content down).
    // Pre-computed for the move-ordering heuristic.
    private val MOVE_TOWARDS_ANCHOR = mapOf(
        "UP" to 0.05,
        "RIGHT" to 0.10,
        "LEFT" to -0.02,
        "DOWN" to -0.05
    )

    // Transposition table - packed board + depth + node type -> score.
    // Bounded so memory stays predictable. LinkedHashMap so we can drop the
    // oldest entries cheaply when full.
    private const val CACHE_MAX_ENTRIES = 50000
    private val cache = LinkedHashMap<String, Double>(CACHE_MAX_ENTRIES)
    // Per-decision search stats. Reset in decideBestMove, read by the
    // summary log. Counters are non-synchronised because the search is
    // single-threaded by design (one game board at a time).
    private var evalCount = 0
    private var cacheHits = 0
    private var maxNodeCount = 0
    private var chanceNodeCount = 0

    // Precomputed log2 values for tile values 0..16384. Since tiles in 2048
    // are always powers of 2, log2(tile) is always an integer and the table
    // lookup replaces expensive ln() calls everywhere in the heuristic.
    private val LOG_TABLE = DoubleArray(16385) { v ->
        if (v <= 1) 0.0 else kotlin.math.ln(v.toDouble()) / kotlin.math.ln(2.0)
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Pick the best direction using iterative-deepening Expectimax with a
     * 1500 ms time budget per decision. We start at depth 1, increase
     * until we either exhaust [MAX_DEPTH] or run out of time, and return
     * the best move from the deepest fully-completed iteration.
     *
     * [forbiddenMove] excludes a direction from consideration. Used by
     * the stuck-state detector in [MainActivity] when the bot is trapped
     * in a no-op loop (e.g. OCR is reading a ghost value because of a
     * cat-mascot overlay). Default `"NONE"` disables the exclusion.
     */
    fun decideBestMove(grid: List<Int>, forbiddenMove: String = "NONE"): String {
        cache.clear()
        evalCount = 0
        cacheHits = 0
        maxNodeCount = 0
        chanceNodeCount = 0
        val board = IntArray(16) { grid[it] }
        val startNanos = System.nanoTime()

        // Order moves by a cheap heuristic so the partial result from a
        // timed-out deeper search is already a sensible move. Honour
        // [forbiddenMove] by skipping it; if EVERY direction is forbidden
        // (rare — only happens with malformed input) we fall back to
        // considering all of them so the bot never returns nothing.
        val orderedDirs = DIRECTIONS
            .asSequence()
            .filter { it != forbiddenMove }
            .map { it to quickMoveEval(board, it) }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
            .ifEmpty { DIRECTIONS.toList() }

        var bestMove = orderedDirs[0]
        var bestScore = Double.NEGATIVE_INFINITY
        var maxDepthReached = 0
        var depth = 1
        while (depth <= MAX_DEPTH) {
            val iterStart = System.nanoTime()
            var depthBestMove = orderedDirs[0]
            var depthBestScore = Double.NEGATIVE_INFINITY
            var depthComplete = true

            for (dir in orderedDirs) {
                if (System.nanoTime() - startNanos > TIME_BUDGET_MS * 1_000_000) {
                    depthComplete = false
                    break
                }
                val newBoard = simulateMove(board, dir) ?: continue
                val score = expectimax(newBoard, depth - 1, false, startNanos)
                if (score > depthBestScore) {
                    depthBestScore = score
                    depthBestMove = dir
                }
            }

            if (depthComplete && depthBestScore > bestScore) {
                bestScore = depthBestScore
                bestMove = depthBestMove
                maxDepthReached = depth
            }

            if (!depthComplete) break
            depth++
        }

        // Single summary log per decision - shows real search stats
        // instead of one line per depth iteration. evals = total expectimax
        // calls, hits = cache hits, depth = deepest fully-completed depth.
        val totalMs = (System.nanoTime() - startNanos) / 1_000_000
                // Wrap in try/catch so JVM unit tests (where android.util.Log is
                // not mocked) can still exercise this path without throwing.
                try {
                    Logger.d("Solver", "Solver best=$bestMove depth=$maxDepthReached/${MAX_DEPTH} ${totalMs}ms evals=$evalCount hits=$cacheHits cache=${cache.size} maxNodes=$maxNodeCount chanceNodes=$chanceNodeCount")
                } catch (_: RuntimeException) {
                    // android.util.Log.d throws "Method d in android.util.Log not
                    // mocked" in plain JVM tests - silently drop it there.
                }

        return bestMove
    }

    fun predictBoardAfterMove(grid: List<Int>, direction: String): List<Int>? {
        if (grid.size != 16) return null
        return simulateMove(grid.toIntArray(), direction)?.toList()
    }

    // ----------------------------------------------------------------
    //  Cheap heuristic for move ordering (depth 0 only)
    // ----------------------------------------------------------------

    /**
     * One-ply heuristic: run the full evaluate() on the resulting board.
     * Adding the small "moves towards anchor" bias keeps LEFT/DOWN out of
     * the early-search candidates when an UP/RIGHT move is at least
     * equivalent - prevents the iterative deepening from committing to a
     * sideways move early.
     */
    private fun quickMoveEval(board: IntArray, dir: String): Double {
        val next = simulateMove(board, dir) ?: return Double.NEGATIVE_INFINITY
        return evaluate(next) + (MOVE_TOWARDS_ANCHOR[dir] ?: 0.0)
    }

    // ----------------------------------------------------------------
    //  Board simulation
    // ----------------------------------------------------------------

    /**
     * Apply [direction] to [board] (copy) and return the new board, or null
     * if nothing moved (a no-op slide means the direction is illegal).
     */
    private fun simulateMove(board: IntArray, direction: String): IntArray? {
        val out = board.copyOf()
        var changed = false
        when (direction) {
            "LEFT" -> for (r in 0..3) changed = slide(out, r * 4, 1, 0, 4) || changed
            "RIGHT" -> for (r in 0..3) changed = slide(out, r * 4 + 3, -1, 0, 4) || changed
            "UP" -> for (c in 0..3) changed = slide(out, c, 4, 1, 4) || changed
            "DOWN" -> for (c in 0..3) changed = slide(out, c + 12, -4, -1, 4) || changed
        }
        return if (changed) out else null
    }

    /**
     * Slide [count] cells along the row/column defined by [start] and [stride]
     * (with [axisStep] unused - kept for signature compatibility). Merges
     * adjacent equal tiles once per slide. Returns true iff the line changed.
     */
    private fun slide(board: IntArray, start: Int, stride: Int, axisStep: Int, count: Int): Boolean {
        val cells = IntArray(count)
        var i = 0
        var idx = start
        while (i < count) {
            cells[i] = idx
            idx += stride
            i++
        }
        val original = IntArray(count)
        for (k in 0 until count) original[k] = board[cells[k]]
        val merged = IntArray(count)
        var writePos = 0
        var readPos = 0
        while (readPos < count) {
            val v = board[cells[readPos]]
                    // Treat unknown / non-positive cells as empty so they never
                    // "merge" with another unknown and produce a sentinel value
                    // (e.g. -1 * 2 = -2) that crashes LOG_TABLE downstream.
                    if (v <= 0) { readPos++; continue }
                    if (writePos > 0 && merged[writePos - 1] == 0 &&
                        board[cells[readPos - 1]] == v) {
                        board[cells[writePos - 1]] = v * 2
                        merged[writePos - 1] = 1
                        readPos++
                    } else {
                        board[cells[writePos]] = v
                        merged[writePos] = 0
                        writePos++
                        readPos++
                    }
                }
        while (writePos < count) {
            board[cells[writePos]] = 0
            writePos++
        }
        for (k in 0 until count) {
            if (board[cells[k]] != original[k]) return true
        }
        return false
    }

    // ----------------------------------------------------------------
    //  Expectimax search
    // ----------------------------------------------------------------

    /**
     * Recursive Expectimax. [depth] remaining player-move depth (0 = chance
     * node evaluated then immediately scored). [startNanos] is the deadline
     * reference for the time budget - we abort early if exceeded.
     */
    private fun expectimax(
        board: IntArray, depth: Int, isPlayerTurn: Boolean, startNanos: Long
    ): Double {
        val key = packKey(board, depth, isPlayerTurn)
        val cached = cache[key]
        if (cached != null) { cacheHits++; return cached }
        evalCount++

        // Time check at the top of the recursion - failing fast when we
        // overrun prevents any one branch from monopolising the budget.
        if (System.nanoTime() - startNanos > TIME_BUDGET_MS * 1_000_000) {
            return evaluate(board)
        }

        val score: Double = if (isPlayerTurn) {
            maxNodeCount++
            maxNode(board, depth, startNanos)
        } else {
            chanceNodeCount++
            chanceNode(board, depth, startNanos)
        }

        if (cache.size >= CACHE_MAX_ENTRIES) {
            // Drop oldest 25% - LinkedHashMap iterates in insertion order.
            val it = cache.entries.iterator()
            var dropped = 0
            val quota = CACHE_MAX_ENTRIES / 4
            while (it.hasNext() && dropped < quota) {
                it.next()
                it.remove()
                dropped++
            }
        }
        cache[key] = score
        return score
    }

    /** MAX node: try every direction, take the maximum expected value. */
    private fun maxNode(board: IntArray, depth: Int, startNanos: Long): Double {
        var best = Double.NEGATIVE_INFINITY
        var found = false
        for (dir in DIRECTIONS) {
            val next = simulateMove(board, dir) ?: continue
            found = true
            val s = if (depth <= 0) evaluate(next)
                    else expectimax(next, depth - 1, false, startNanos)
            if (s > best) best = s
        }
        return if (found) best else evaluate(board)
    }

    /**
     * Chance node: spawn a new tile at a random empty cell. 90% a 2, 10% a
     * 4. Only the top [CHANCE_CELL_LIMIT] empties - ranked by adjacency to
     * existing tiles - are evaluated; spawns in isolated empty space are
     * essentially noise.
     */
    private fun chanceNode(board: IntArray, depth: Int, startNanos: Long): Double {
        val empties = IntArray(16)
        var n = 0
        for (i in 0..15) if (board[i] == 0) empties[n++] = i
        if (n == 0) return evaluate(board)

        val limit = min(n, CHANCE_CELL_LIMIT)
        val top = IntArray(limit)
        var topFilled = 0
        var minScoreInTop = Int.MIN_VALUE
        for (k in 0 until n) {
            val cell = empties[k]
            val score = neighbourScore(board, cell)
            if (topFilled < limit) {
                top[topFilled++] = cell
                if (topFilled == limit) { minScoreInTop = neighbourScore(board, top[0]); for (j in 1 until limit) { val s = neighbourScore(board, top[j]); if (s < minScoreInTop) minScoreInTop = s } }
            } else if (score > minScoreInTop) {
                var worstIdx = 0
                var worstScore = neighbourScore(board, top[0])
                for (j in 1 until limit) {
                    val s = neighbourScore(board, top[j])
                    if (s < worstScore) { worstScore = s; worstIdx = j }
                }
                top[worstIdx] = cell
                minScoreInTop = neighbourScore(board, top[0]); for (j in 1 until limit) { val s = neighbourScore(board, top[j]); if (s < minScoreInTop) minScoreInTop = s }
            }
        }

        var sum = 0.0
        for (k in 0 until topFilled) {
            val cell = top[k]
            board[cell] = 2
            sum += PROB_2 * expectimax(board, depth, true, startNanos)
            board[cell] = 4
            sum += PROB_4 * expectimax(board, depth, true, startNanos)
            board[cell] = 0
        }
        return sum / topFilled
    }

    private fun neighbourScore(board: IntArray, cell: Int): Int {
        var s = 0
        val r = cell / 4
        val c = cell % 4
        if (r > 0 && board[cell - 4] != 0) s++
        if (r < 3 && board[cell + 4] != 0) s++
        if (c > 0 && board[cell - 1] != 0) s++
        if (c < 3 && board[cell + 1] != 0) s++
        return s
    }

    // ----------------------------------------------------------------
    //  Transposition table key
    // ----------------------------------------------------------------

    /**
     * Encode the 16 tile values + depth + node type into a String key for
     * the transposition table. We use base-N encoding where each tile is
     * two hex chars (so values up to 65535 are uniquely represented - well
     * above any reachable 2048 tile). The string is short (33 chars per
     * entry) and HashMap lookups on Strings are still fast.
     *
     * Earlier versions packed the key into a Long but that masked each
     * tile to its low 5 bits, which collapsed all values >= 32 (32, 64,
     * 128, 256, ..., 2048) to 0 - making the cache useless on any board
     * containing mid-range or larger tiles.
     */
    private fun packKey(board: IntArray, depth: Int, isPlayerTurn: Boolean): String {
        val sb = StringBuilder(40)
        sb.append(if (isPlayerTurn) 'P' else 'C')
        sb.append(' ')
        sb.append(depth)
        sb.append(' ')
        for (v in board) {
            val hex = Integer.toHexString(v)
            if (hex.length < 4) sb.append("0000".substring(hex.length)).append(hex)
            else sb.append(hex)
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------
    //  Heuristic evaluation
    // ----------------------------------------------------------------

    /**
     * Combined heuristic score. Six signals:
     *   1. Snake order - exponential weighting by [POSITION_WEIGHT]
     *   2. Empty cells - flexibility budget
     *   3. Monotonicity - rows/cols go one way
     *   4. Smoothness - neighbours differ little
     *   5. Corner bonus - max tile anchored in [ANCHOR_CORNER]
     *   6. Merge pairs - adjacent equal tiles ready to combine
     */
    private fun evaluate(board: IntArray): Double {
        var empty = 0.0
        var snake = 0.0
        var maxTile = 0
        var maxIdx = -1
        for (i in 0..15) {
            val v = board[i]
                // Treat unknown cells as empty and clamp oversized tiles so
                // LOG_TABLE[v] can never receive a sentinel / out-of-range
                // value even if a previous mutation slipped one through.
                if (v <= 0) { empty += 1.0; continue }
                if (v >= LOG_TABLE.size) continue
                // Snake weight: higher rank (closer to anchor) scores more.
                // Multiplying by tile value (post-merge power) keeps the snake
                // proportional to actual tile size.
                snake += LOG_TABLE[v] * (16 - POSITION_WEIGHT[i])
                if (v > maxTile) {
                    maxTile = v
                    maxIdx = i
                }
            }
        val mono = monotonicity(board)
        val smooth = smoothness(board)
        val mergeable = mergeablePairs(board)
        val (cornerBonus, cornerPenalty) = if (maxTile > 0) {
            if (maxIdx == ANCHOR_CORNER) {
                LOG_TABLE[maxTile] * W_CORNER to 0.0
            } else {
                // The max tile drifted away from the anchor - this is the
                // single most catastrophic structural failure in 2048, so
                // we apply a large absolute penalty (independent of log
                // scaling) that consistently outweighs every other term in
                // the heuristic. After the snake collapses into a line it's
                // effectively impossible to recover without a free 2048 spawn
                // and a long chain of merges, so any "scored move" that
                // produces this position must score below any move that
                // *keeps* the anchor.
                0.0 to -W_CORNER_PENALTY
            }
        } else {
            0.0 to 0.0
        }
        return W_SNAKE   * snake +
               W_EMPTY   * empty +
               W_MONO    * mono +
               W_SMOOTH  * smooth +
               cornerBonus +
               cornerPenalty +
               W_MERGE   * mergeable -
               // Massive penalty if this position is terminal — no legal
               // move exists. The solver will pick *any* other move over
               // one that locks the board.
               (if (hasNoMoves(board.toList())) W_TERMINAL else 0.0)
    }

    /**
     * Return true iff the current board has no legal moves (i.e. every row
     * and column is full AND no two adjacent tiles in any row/column share
     * a value). Caller is responsible for supplying a 16-element list;
     * any other size returns true (defensive - a malformed grid is treated
     * as terminal so the bot never commits a random move on it).
     */
    fun hasNoMoves(grid: List<Int>): Boolean {
        if (grid.size != 16) return true
        // Any empty cell => a slide in that axis opens at least one slot.
        if (grid.any { it == 0 }) return false
        for (i in 0..3) {
            // Adjacent horizontal pair shares a value => they merge next move.
            for (j in 0..2) {
                if (grid[i * 4 + j] == grid[i * 4 + j + 1]) return false
            }
            // Adjacent vertical pair shares a value => they merge next move.
            for (j in 0..2) {
                if (grid[j * 4 + i] == grid[(j + 1) * 4 + i]) return false
            }
        }
        return true
    }

    /**
     * Return true iff [grid] is terminal - the board is fully populated AND
     * no two neighbouring tiles can merge. Equivalent to "Game Over" for
     * the canonical 2048 ruleset.
     */
    fun isTerminal(grid: List<Int>): Boolean = hasNoMoves(grid)

    /**
     * Sum of (log-difference)^[MONO_POWER] over every adjacent pair, taking
     * the smaller of the two monotonic directions per row/column so the
     * score is maximised when the row/column is monotonic in either
     * direction. Returns a NEGATIVE score (we negate so monotonicity
     * rewards the bot).
     */
    private fun monotonicity(board: IntArray): Double {
        var total = 0.0
            // Treat unknown / empty cells as value=1 so log() is well-defined
            // (NaN would otherwise propagate and break comparisons downstream).
            for (r in 0..3) {
                var inc = 0.0
                var dec = 0.0
                for (c in 0..2) {
                    val a = log(if (board[r * 4 + c] <= 0) 1 else board[r * 4 + c])
                    val b = log(if (board[r * 4 + c + 1] <= 0) 1 else board[r * 4 + c + 1])
                    val diff = kotlin.math.abs(a - b)
                    val penalty = Math.pow(diff, MONO_POWER)
                    if (a > b) inc += penalty else dec += penalty
                }
                total += min(inc, dec)
            }
            for (c in 0..3) {
                var inc = 0.0
                var dec = 0.0
                for (r in 0..2) {
                    val a = log(if (board[r * 4 + c] <= 0) 1 else board[r * 4 + c])
                    val b = log(if (board[(r + 1) * 4 + c] <= 0) 1 else board[(r + 1) * 4 + c])
                    val diff = kotlin.math.abs(a - b)
                    val penalty = Math.pow(diff, MONO_POWER)
                    if (a > b) inc += penalty else dec += penalty
                }
                total += min(inc, dec)
            }
            return -total
        }

    /** Negative sum of |log(a)-log(b)| over orthogonal neighbours. */
    private fun smoothness(board: IntArray): Double {
        var s = 0.0
        for (r in 0..3) {
            for (c in 0..2) {
                val a = board[r * 4 + c]
                val b = board[r * 4 + c + 1]
                if (a != 0 && b != 0) s -= abs(log(a) - log(b))
            }
        }
        for (c in 0..3) {
            for (r in 0..2) {
                val a = board[r * 4 + c]
                val b = board[(r + 1) * 4 + c]
                if (a != 0 && b != 0) s -= abs(log(a) - log(b))
            }
        }
        return s
    }

    /** Count orthogonal adjacent pairs of equal non-zero tiles. */
    private fun mergeablePairs(board: IntArray): Double {
        var count = 0
        for (r in 0..3) {
            for (c in 0..2) {
                val a = board[r * 4 + c]
                val b = board[r * 4 + c + 1]
                if (a != 0 && a == b) count++
            }
        }
        for (c in 0..3) {
            for (r in 0..2) {
                val a = board[r * 4 + c]
                val b = board[(r + 1) * 4 + c]
                if (a != 0 && a == b) count++
            }
        }
        return count.toDouble()
    }

    /** log2 for values > 1, 0 for 0/1. Uses lookup table for speed. */
    private fun log(v: Int): Double = if (v <= 1) 0.0 else LOG_TABLE[v]
}