package com.vyro.core.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase12LongSessionStressTest {

    @Test
    fun `long session 200-step edit render undo redo and bounded cache churn completes cleanly without drift or overflow`() {
        val report = ProductionHardeningVerifier.runLongSessionTimelineAndCacheStress(
            editIterations = 200,
            cachePutIterations = 500,
        )
        assertTrue(report.summary, report.passed)
        assertEquals(200, report.stressCyclesPassed)
        assertTrue(report.timelineOperationsVerified >= 200)
        assertTrue(report.cacheBoundedWithinBudget)
        assertEquals(0, report.leakedSessions)
    }

    @Test
    fun `device performance tier classification adapts across low mid and flagship RAM hardware`() {
        assertEquals(
            DevicePerformanceTier.LOW_MEMORY,
            DevicePerformanceTier.classify(totalRamMb = 2048L, isLowRamDevice = true),
        )
        assertEquals(
            DevicePerformanceTier.LOW_MEMORY,
            DevicePerformanceTier.classify(totalRamMb = 3072L, isLowRamDevice = false),
        )
        assertEquals(
            DevicePerformanceTier.MID_RANGE,
            DevicePerformanceTier.classify(totalRamMb = 4096L, isLowRamDevice = false),
        )
        assertEquals(
            DevicePerformanceTier.HIGH_PERFORMANCE,
            DevicePerformanceTier.classify(totalRamMb = 8192L, isLowRamDevice = false),
        )
    }

    @Test
    fun `play policy audit rejects broad storage permissions and oversized titles while accepting scoped config`() {
        val (cleanOk, cleanSummary) = ProductionHardeningVerifier.auditPolicyCompliance(
            declaredPermissions = listOf("android.permission.INTERNET"),
            appTitle = "VYRO",
            r8RulesConfigured = true,
        )
        assertTrue(cleanSummary, cleanOk)

        val (blockedMediaOk, _) = ProductionHardeningVerifier.auditPolicyCompliance(
            declaredPermissions = listOf("android.permission.READ_MEDIA_VIDEO"),
            appTitle = "VYRO",
            r8RulesConfigured = true,
        )
        assertFalse(blockedMediaOk)

        val (blockedLegacyOk, _) = ProductionHardeningVerifier.auditPolicyCompliance(
            declaredPermissions = listOf("android.permission.READ_EXTERNAL_STORAGE"),
            appTitle = "VYRO",
            r8RulesConfigured = true,
        )
        assertFalse(blockedLegacyOk)

        val (longTitleOk, _) = ProductionHardeningVerifier.auditPolicyCompliance(
            declaredPermissions = emptyList(),
            appTitle = "VYRO Super Pro Best #1 Video Editor App Free Forever",
            r8RulesConfigured = true,
        )
        assertFalse(longTitleOk)

        val (missingR8Ok, _) = ProductionHardeningVerifier.auditPolicyCompliance(
            declaredPermissions = emptyList(),
            appTitle = "VYRO",
            r8RulesConfigured = false,
        )
        assertFalse(missingR8Ok)
    }
}
