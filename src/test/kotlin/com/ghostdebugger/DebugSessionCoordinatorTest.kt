package com.ghostdebugger

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DebugSessionCoordinatorTest : BasePlatformTestCase() {

    @Test
    fun testExtractVariableName() {
        val coordinator = DebugSessionCoordinator(project)

        // TS/JS null safety regex patterns
        assertEquals("user", coordinator.extractVariableName(
            "Null reference: user may be null",
            "Variable 'user' may be null or undefined when accessing property. This is initialized as null and accessed without a null check."
        ))

        // Kotlin null safety patterns
        assertEquals("address", coordinator.extractVariableName(
            "Nullable 'address' accessed without a null check",
            "Expression 'address' has a nullable type at this access. Use ?., a null guard, !!, or an Elvis fallback."
        ))

        // Generic fallback/other title formats
        assertEquals("data", coordinator.extractVariableName(
            "Null reference: data",
            "Data is nullable"
        ))

        // State-before-init patterns
        assertEquals("count", coordinator.extractVariableName(
            "State read before init: count",
            "Variable 'count' read before assignment in constructor"
        ))

        assertEquals("score", coordinator.extractVariableName(
            "read before assignment: score",
            "Field 'score' is read before being assigned"
        ))

        assertNull(coordinator.extractVariableName("Compilation error", "Syntax is broken"))
    }
}
