package com.example.auto_2048

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted tests for the terminal-state helpers added to [GameSolver]
 * after the diagnostic found that the native solver was producing
 * garbage grids when a "Game Over" modal was on screen.
 */
class GameSolverTerminalTest {

    @Test fun emptyBoardIsNotTerminal() {
        val grid = List(16) { 0 }
        assertFalse(GameSolver.isTerminal(grid))
        assertFalse(GameSolver.hasNoMoves(grid))
    }

    @Test fun boardWithMergeablePairIsNotTerminal() {
        val grid = listOf(
            2, 2, 4, 8,
            16, 32, 64, 128,
            256, 4, 8, 16,
            2, 4, 8, 16
        )
        assertFalse(GameSolver.hasNoMoves(grid))
        assertFalse(GameSolver.isTerminal(grid))
    }

    @Test fun fullBoardWithoutMergesIsTerminal() {
        val grid = listOf(
            2,    4,    2,    4,
            8,   16,    8,   16,
            32,  64,   32,   64,
            128, 256, 128, 256
        )
        assertTrue(GameSolver.hasNoMoves(grid))
        assertTrue(GameSolver.isTerminal(grid))
    }

    @Test fun fullBoardWithVerticalMergeIsNotTerminal() {
        // Bottom row starts with 32 so column 0 has (row2=32, row3=32)
        // which means a vertical merge is still possible.
        val grid = listOf(
            2,    4,    2,    4,
            8,   16,    8,   16,
            32,  64,   32,   64,
            32, 256, 128, 256
        )
        assertFalse(GameSolver.hasNoMoves(grid))
        assertFalse(GameSolver.isTerminal(grid))
    }

    @Test fun malformedGridIsTreatedAsTerminal() {
        assertTrue(GameSolver.isTerminal(listOf(0, 2, 4)))
        assertTrue(GameSolver.isTerminal(List(20) { 0 }))
    }
}
