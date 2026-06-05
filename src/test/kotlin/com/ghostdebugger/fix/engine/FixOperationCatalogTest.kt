package com.ghostdebugger.fix.engine

import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.starProjectedType

class FixOperationCatalogTest {
    @Test fun everySealedOperationHasExactlyOneCatalogEntry() {
        val registered = FixOperation::class.sealedSubclasses
            .map { serializer(it.starProjectedType).descriptor.serialName }
            .toSet()
        assertEquals(registered, FixOperationCatalog.serialNames())
    }

    @Test fun entriesAreNonEmptyAndTypePrefixed() {
        assertEquals(15, FixOperationCatalog.entries.size)
        FixOperationCatalog.entries.forEach { assertEquals(true, it.trimStart().startsWith("{\"type\":\"")) }
    }
}
