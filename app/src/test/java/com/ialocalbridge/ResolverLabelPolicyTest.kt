package com.ialocalbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolverLabelPolicyTest {
    @Test fun choosesOnlyUniqueUncontestedSystemLabel() {
        assertEquals(1, ResolverLabelPolicy.uniqueTrustedIndex(setOf("Files"), setOf("Other"), listOf("Other", "Files")))
        assertNull(ResolverLabelPolicy.uniqueTrustedIndex(setOf("Files"), setOf("Files"), listOf("Files")))
        assertNull(ResolverLabelPolicy.uniqueTrustedIndex(setOf("Files"), emptySet(), listOf("Files", "Files")))
        assertNull(ResolverLabelPolicy.uniqueTrustedIndex(emptySet(), emptySet(), listOf("Files")))
        assertNull(ResolverLabelPolicy.uniqueTrustedIndex(setOf(""), emptySet(), listOf("")))
        assertNull(ResolverLabelPolicy.uniqueTrustedIndex(setOf("Files"), emptySet(), listOf("Other")))
    }
}
