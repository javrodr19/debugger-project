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

        // State before init patterns (JVM / JS / TS)
        assertEquals("count", coordinator.extractVariableName(
            "Variable 'count' read before assignment",
            "Variable 'count' is accessed before it is assigned a value."
        ))

        assertEquals("value", coordinator.extractVariableName(
            "Field 'value' accessed before initialization",
            "Field 'value' is accessed before it has been initialized."
        ))

        assertNull(coordinator.extractVariableName("Compilation error", "Syntax is broken"))
    }
}
