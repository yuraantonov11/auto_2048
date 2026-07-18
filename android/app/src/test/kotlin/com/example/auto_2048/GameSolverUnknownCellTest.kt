package com.example.auto_2048

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the -2 / NaN crashes that occurred when the vision
 * pipeline returned -1 ("uncertain") cells and the solver tried to slide or
 * evaluate them. The original `slide()` function merged two adjacent
 * unknowns into -2, which then blew up `LOG_TABLE[-2]` in `evaluate()`.
 *
 * The fixed solver must:
 *   1. Treat -1 cells like empty cells in `slide()` (skip without merging).
 *   2. Treat -1 cells like empty cells in `evaluate()` (no LOG_TABLE lookup).
 *   3. Use log(1) = 0 for non-positive cells in `monotonicity()` so the
 *      score stays finite.
 *   4. Return a valid move (never throw) for any 16-cell input that
 *      contains a mix of 0, positive powers of two and -1 sentinels.
 */
class GameSolverUnknownCellTest {

    /**
     * The exact crash reproducer: two adjacent -1 cells must not be merged
     * into -2 by `simulateMove`. The expected LEFT slide compacts them to
     * the left edge but leaves the row zeroed otherwise.
     */
    @Test fun adjacentUnknownsDoNotMergeIntoNegative() {
        val grid = listOf(
            0, -1, -1, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        val result = GameSolver.predictBoardAfterMove(grid, "LEFT")
        assertNotNull("LEFT should produce a valid board", result)
        // After LEFT the two unknowns sit at indices 0 and 1 - they must
        // stay as -1 (or be zeroed) and MUST NOT become -2.
        for (v in result!!) {
            assertTrue("Cell value must never be negative: $v", v >= 0)
        }
    }

    /** All-uncertain board should still produce a move (the solver picks
     *  the first valid direction and reports it back). */
    @Test fun allUnknownBoardReturnsMove() {
        val grid = List(16) { -1 }
        val move = GameSolver.decideBestMove(grid)
        assertTrue(
            "Solver must return one of the four legal directions, got: $move",
            move in setOf("UP", "DOWN", "LEFT", "RIGHT")
        )
    }

    /** Mixed real + uncertain board - the real tiles must survive the
     *  slide (no spurious merges between a real tile and an unknown). */
    @Test fun realTilesSurviveSlideWithUnknownsAdjacent() {
        val grid = listOf(
            2, -1, 2, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        val result = GameSolver.predictBoardAfterMove(grid, "LEFT")
        assertNotNull(result)
        // First two real 2-tiles must remain at indices 0 and 1.
        // The unknown between them must not collapse them.
        assertEquals(2, result!![0])
        assertTrue("Index 1 must stay non-negative: ${result[1]}", result[1] >= 0)
    }

    /** monotonicity() / evaluate() must never produce NaN even when fed a
     *  grid full of unknowns. We trigger the full decideBestMove path which
     *  calls evaluate() many times internally. */
    @Test fun fullSolveDoesNotThrowOnUnknownBoard() {
        val grid = List(16) { if (it % 3 == 0) -1 else 0 }
        // No assertion on the move - we just need it to complete without
        // throwing ArrayIndexOutOfBoundsException or returning NaN-based
        // garbage.
        val move = GameSolver.decideBestMove(grid)
        assertTrue(move in setOf("UP", "DOWN", "LEFT", "RIGHT"))
    }
}