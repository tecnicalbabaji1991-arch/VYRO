package com.vyro.platform.media

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.CacheAndProxyManagerContract
import com.vyro.core.contracts.DevicePerformanceTier
import com.vyro.core.contracts.ExportResolutionPreset
import com.vyro.core.contracts.HardeningDiagnosticsSnapshot
import com.vyro.core.contracts.MemoryPressureLevel
import com.vyro.core.contracts.ProductionHardeningSuiteReport
import com.vyro.core.contracts.ProductionHardeningVerifier
import com.vyro.core.contracts.RuntimeHardeningMonitorContract
import com.vyro.core.contracts.ThermalStatusLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors device performance tier, thermal throttling, battery saver mode,
 * and Play policy compliance, automatically adapting cache & proxy pressure rules.
 */
class AndroidRuntimeHardeningMonitor(
    context: Context,
    private val cacheManager: CacheAndProxyManagerContract,
    private val r8RulesConfiguredProvider: () -> Boolean = { true },
) : RuntimeHardeningMonitorContract {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val released = AtomicBoolean(false)

    private val _snapshot = MutableStateFlow(HardeningDiagnosticsSnapshot())
    override val snapshot: StateFlow<HardeningDiagnosticsSnapshot> = _snapshot.asStateFlow()

    private var powerManager: PowerManager? = null
    private var thermalListener: Any? = null

    init {
        powerManager = runCatching {
            appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull()
        registerThermalListenerIfSupported()
        refreshSystemTelemetry()
        evaluateManifestCompliance()
    }

    override fun refreshSystemTelemetry() {
        val am = runCatching {
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        val memInfo = ActivityManager.MemoryInfo()
        val hasMemInfo = am != null && runCatching { am.getMemoryInfo(memInfo) }.isSuccess
        val totalMb = if (hasMemInfo && memInfo.totalMem > 0L) memInfo.totalMem / (1024L * 1024L) else 4096L
        val availMb = if (hasMemInfo && memInfo.availMem > 0L) memInfo.availMem / (1024L * 1024L) else 2048L
        val isLowRam = am?.isLowRamDevice == true || (hasMemInfo && memInfo.lowMemory)
        val tier = DevicePerformanceTier.classify(totalMb, isLowRam)

        val pm = powerManager
        val isPowerSave = pm?.isPowerSaveMode == true
        val thermalStatus = if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { ThermalStatusLevel.fromAndroidThermalStatus(pm.currentThermalStatus) }
                .getOrDefault(_snapshot.value.thermalStatus)
        } else {
            _snapshot.value.thermalStatus
        }

        applyAdaptationState(
            deviceTier = tier,
            totalRamMb = totalMb,
            availableRamMb = availMb,
            isLowRamDevice = isLowRam,
            thermalStatus = thermalStatus,
            isPowerSaveMode = isPowerSave,
            incrementThrottleCount = false,
        )

        if (isLowRam) {
            cacheManager.onTrimMemory(MemoryPressureLevel.MODERATE)
        }
    }

    override fun onThermalStatusChanged(level: ThermalStatusLevel) {
        val current = _snapshot.value
        val throttled = level.severityRank >= ThermalStatusLevel.SEVERE.severityRank
        applyAdaptationState(
            deviceTier = current.deviceTier,
            totalRamMb = current.totalRamMb,
            availableRamMb = current.availableRamMb,
            isLowRamDevice = current.isLowRamDevice,
            thermalStatus = level,
            isPowerSaveMode = current.isPowerSaveMode,
            incrementThrottleCount = throttled,
        )
        when (level) {
            ThermalStatusLevel.SEVERE -> {
                cacheManager.onTrimMemory(MemoryPressureLevel.LOW)
                VyroLogger.log(
                    LogSeverity.WARN,
                    "platform:android-media",
                    "Thermal SEVERE: trimmed caches and throttled preview cap to 30fps.",
                    "thermal_guard",
                    EngineErrorCode.OK,
                )
            }
            ThermalStatusLevel.CRITICAL -> {
                cacheManager.onTrimMemory(MemoryPressureLevel.CRITICAL)
                VyroLogger.log(
                    LogSeverity.WARN,
                    "platform:android-media",
                    "Thermal CRITICAL: cleared non-essential caches and recommended proxy/720p cap.",
                    "thermal_guard",
                    EngineErrorCode.OK,
                )
            }
            else -> Unit
        }
    }

    override fun onPowerSaveModeChanged(enabled: Boolean) {
        val current = _snapshot.value
        applyAdaptationState(
            deviceTier = current.deviceTier,
            totalRamMb = current.totalRamMb,
            availableRamMb = current.availableRamMb,
            isLowRamDevice = current.isLowRamDevice,
            thermalStatus = current.thermalStatus,
            isPowerSaveMode = enabled,
            incrementThrottleCount = enabled,
        )
    }

    override fun evaluatePolicyCompliance(
        declaredPermissions: List<String>,
        appTitle: String,
        r8RulesConfigured: Boolean,
    ): Boolean {
        val (compliant, summary) = ProductionHardeningVerifier.auditPolicyCompliance(
            declaredPermissions = declaredPermissions,
            appTitle = appTitle,
            r8RulesConfigured = r8RulesConfigured,
        )
        _snapshot.update {
            it.copy(
                playPolicyCompliant = compliant,
                policyComplianceSummary = summary,
            )
        }
        return compliant
    }

    override fun recordStressSuiteResult(report: ProductionHardeningSuiteReport) {
        _snapshot.update {
            it.copy(
                totalStressCyclesPassed = it.totalStressCyclesPassed + report.stressCyclesPassed,
                lastStressSummary = report.summary,
            )
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val listener = thermalListener
        val pm = powerManager
        if (listener != null && pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            listener is PowerManager.OnThermalStatusChangedListener
        ) {
            runCatching { pm.removeThermalStatusListener(listener) }
        }
        thermalListener = null
    }

    private fun evaluateManifestCompliance() {
        val declared = runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
            }
            pkgInfo.requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val label = runCatching {
            appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
        }.getOrDefault("VYRO")

        evaluatePolicyCompliance(
            declaredPermissions = declared,
            appTitle = label,
            r8RulesConfigured = r8RulesConfiguredProvider(),
        )
    }

    private fun registerThermalListenerIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = powerManager ?: return
        runCatching {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                onThermalStatusChanged(ThermalStatusLevel.fromAndroidThermalStatus(status))
            }
            pm.addThermalStatusListener(listener)
            thermalListener = listener
        }
    }

    private fun applyAdaptationState(
        deviceTier: DevicePerformanceTier,
        totalRamMb: Long,
        availableRamMb: Long,
        isLowRamDevice: Boolean,
        thermalStatus: ThermalStatusLevel,
        isPowerSaveMode: Boolean,
        incrementThrottleCount: Boolean,
    ) {
        synchronized(lock) {
            val recommendProxy = isLowRamDevice ||
                deviceTier == DevicePerformanceTier.LOW_MEMORY ||
                thermalStatus.severityRank >= ThermalStatusLevel.MODERATE.severityRank ||
                isPowerSaveMode

            val maxPreset = when {
                thermalStatus.severityRank >= ThermalStatusLevel.SEVERE.severityRank -> ExportResolutionPreset.HD_720P
                deviceTier == DevicePerformanceTier.LOW_MEMORY -> ExportResolutionPreset.HD_720P
                isPowerSaveMode || thermalStatus == ThermalStatusLevel.MODERATE -> ExportResolutionPreset.FHD_1080P
                else -> deviceTier.defaultMaxExportPreset
            }

            val fpsCap = when {
                thermalStatus == ThermalStatusLevel.CRITICAL -> 24
                thermalStatus == ThermalStatusLevel.SEVERE || isPowerSaveMode -> 30
                deviceTier == DevicePerformanceTier.LOW_MEMORY -> 30
                else -> 60
            }

            _snapshot.update { prev ->
                prev.copy(
                    deviceTier = deviceTier,
                    totalRamMb = totalRamMb,
                    availableRamMb = availableRamMb,
                    isLowRamDevice = isLowRamDevice,
                    thermalStatus = thermalStatus,
                    isPowerSaveMode = isPowerSaveMode,
                    recommendedMaxExportPreset = maxPreset,
                    adaptiveProxyRecommended = recommendProxy,
                    recommendedPreviewFpsCap = fpsCap,
                    thermalThrottleEvents = if (incrementThrottleCount) prev.thermalThrottleEvents + 1L else prev.thermalThrottleEvents,
                )
            }
        }
    }
}
