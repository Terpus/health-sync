package com.healthsync

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var healthManager: HealthConnectManager
    private lateinit var statusText: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        lifecycleScope.launch {
            if (healthManager.hasPermissions()) {
                updateStatus("Health Connect connected.")
            } else if (granted.isNotEmpty()) {
                updateStatus(
                    "Health Connect partially connected. Some health fields may show blank."
                )
            } else {
                updateStatus("Health Connect permissions are still off. Opening Health Connect settings...")
                openHealthConnectPermissions()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SyncDiagnostics.installCrashHandler(applicationContext)
        healthManager = HealthConnectManager(applicationContext)
        statusText = findViewById(R.id.statusText)
        findViewById<ViewGroup>(R.id.contentStack).scheduleLayoutAnimation()
        findViewById<View>(R.id.contentRoot).animate()
            .alpha(1f)
            .setDuration(260)
            .start()

        refreshStatusDisplay()

        // Migrate an already-running auto-sync schedule from older app versions
        // (15 min) to the current lower-frequency schedule without requiring the
        // user to toggle auto sync again after updating the APK.
        lifecycleScope.launch {
            runCatching {
                SyncWorker.refreshScheduleIfAlreadyActive(applicationContext)
            }.onSuccess { wasActive ->
                if (wasActive) {
                    SyncDiagnostics.log(
                        applicationContext,
                        "Existing auto-sync schedule refreshed to 2h + battery-not-low"
                    )
                }
            }.onFailure { error ->
                SyncDiagnostics.error(applicationContext, "refresh_auto_sync_schedule", error)
            }
        }

        findViewById<Button>(R.id.btnConnectHealth).setOnClickListener {
            lifecycleScope.launch {
                when (healthManager.availability()) {
                    HealthConnectManager.Availability.AVAILABLE -> {
                        try {
                            if (!healthManager.hasPermissions()) {
                                healthPermissionLauncher.launch(healthManager.permissions)
                            } else {
                                updateStatus("Health Connect already connected.")
                            }
                        } catch (e: Exception) {
                            updateStatus("Health Connect failed: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                    HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> {
                        openHealthConnectInstall()
                    }
                    HealthConnectManager.Availability.UNAVAILABLE -> {
                        updateStatus(
                            "Health Connect is not available on this phone. " +
                            "Update Android and Google Play services, then try again."
                        )
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnConnectDrive).setOnClickListener {
            try {
                updateStatus("Choose Google Drive and save as health_data.json.")
                @Suppress("DEPRECATION")
                startActivityForResult(createDriveFileIntent(), RC_DRIVE_FILE)
            } catch (e: ActivityNotFoundException) {
                updateStatus("No file picker found. Install Google Drive and try again.")
            }
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            runManualSync(toDrive = true)
        }

        findViewById<Button>(R.id.btnExportLocal).setOnClickListener {
            runManualSync(toDrive = false)
        }

        findViewById<Button>(R.id.btnSchedule).setOnClickListener {
            lifecycleScope.launch {
                if (!healthManager.hasPermissions()) {
                    updateStatus("Connect Health Connect before starting auto sync.")
                    openHealthConnectPermissions()
                    return@launch
                }
                if (!DriveClient.hasFile(this@MainActivity)) {
                    updateStatus("Connect Google Drive before starting auto sync.")
                    return@launch
                }
                SyncWorker.schedule(this@MainActivity)
                SyncWorker.runOnce(this@MainActivity)
                requestNotificationPermissionIfNeeded()
                updateStatus(
                    "Auto sync active. Syncing once now, then Android will run background sync " +
                        "about every 2 hours when connected and battery is not low."
                )
            }
        }
    }

    @Deprecated("Uses legacy activity result API for document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_DRIVE_FILE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    DriveClient.saveFileUri(this, uri, data.flags)
                    updateStatus(
                        "Google Drive file connected.\n" +
                            DriveClient.describeFileAccess(this)
                    )
                    refreshStatusDisplay()
                } catch (t: Throwable) {
                    SyncDiagnostics.error(applicationContext, "save_drive_uri", t)
                    updateStatus(
                        "Drive connection failed: ${t.javaClass.simpleName}: " +
                            "${t.message ?: "<no message>"}"
                    )
                }
            } else {
                updateStatus("Google Drive file selection cancelled.")
            }
        }
    }

    private fun runManualSync(toDrive: Boolean) {
        lifecycleScope.launch {
            if (!healthManager.hasPermissions()) {
                updateStatus("Step 1: Connect Health Connect first. Opening Health Connect settings...")
                openHealthConnectPermissions()
                return@launch
            }
            if (toDrive && !DriveClient.hasFile(this@MainActivity)) {
                updateStatus(
                    "Step 2: Connect Google Drive again. " +
                        "The saved document URI is missing or no longer has persisted write access."
                )
                return@launch
            }

            val appContext = applicationContext
            val mode = if (toDrive) "manual-drive" else "manual-local"
            SyncDiagnostics.start(appContext, mode)
            updateStatus(
                if (toDrive) "Syncing to Google Drive..."
                else "Testing Health Connect export to local Downloads..."
            )

            var stage = "starting"
            try {
                val (snapshot, result) = withContext(Dispatchers.IO) {
                    if (toDrive) {
                        stage = "drive_access"
                        SyncDiagnostics.log(
                            appContext,
                            "Drive access before sync: ${DriveClient.describeFileAccess(appContext)}"
                        )
                    }

                    stage = "health_connect_extraction"
                    SyncDiagnostics.memory(appContext, "before extraction")
                    val extracted = healthManager.readTodaySnapshot { message ->
                        SyncDiagnostics.log(appContext, message)
                        runOnUiThread {
                            statusText.text = buildString {
                                appendLine(
                                    if (toDrive) "Syncing to Google Drive..."
                                    else "Testing local export..."
                                )
                                append(message)
                            }
                        }
                    }
                    SyncDiagnostics.memory(appContext, "after extraction")

                    val rawRecordCount = extracted.rawRecords.values.sumOf { it.size }
                    val rawTypeCount = extracted.rawRecords.count { it.value.isNotEmpty() }
                    SyncDiagnostics.log(
                        appContext,
                        "extraction summary: rawRecords=$rawRecordCount rawTypes=$rawTypeCount " +
                            "errors=${extracted.extractionErrors.size}"
                    )

                    stage = if (toDrive) "drive_serialization_write" else "local_serialization_write"
                    val written = if (toDrive) {
                        DriveClient.syncSnapshot(appContext, extracted)
                    } else {
                        DriveClient.exportLocalDownload(appContext, extracted)
                    }
                    SyncDiagnostics.memory(appContext, "after write")
                    extracted to written
                }

                val rawRecordCount = snapshot.rawRecords.values.sumOf { it.size }
                val rawTypeCount = snapshot.rawRecords.count { it.value.isNotEmpty() }
                val destination = if (toDrive) "Google Drive" else result.destination
                updateStatus(
                    "Export complete: $destination\n" +
                        "Bytes: ${result.bytesWritten}\n" +
                        "Steps: ${snapshot.steps ?: "--"}\n" +
                        "Weight: ${JournalHealthSummaryBuilder.latestWeightKg(snapshot)?.let { "$it kg" } ?: "--"}\n" +
                        "HR: ${snapshot.heartRateAvg ?: "--"} bpm\n" +
                        "Calories: ${snapshot.caloriesTotal ?: "--"} kcal\n" +
                        "Sleep: ${snapshot.sleepDurationMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "--"}\n" +
                        "Raw: $rawRecordCount records / $rawTypeCount types / 7 days\n" +
                        "Raw sync: ${snapshot.rawSyncMode}; changes=${snapshot.rawChangesApplied}; " +
                        "backfilled types=${snapshot.rawBackfilledTypes}\n" +
                        "Diagnostics: ${SyncDiagnostics.file(appContext).absolutePath}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                SyncDiagnostics.error(appContext, stage, t)
                updateStatus(
                    "Sync failed during $stage\n" +
                        "${t.javaClass.name}: ${t.message ?: "<no message>"}\n" +
                        "Diagnostics: ${SyncDiagnostics.file(appContext).absolutePath}"
                )
            }
        }
    }

    private fun updateStatus(message: String) {
        statusText.text = message
        statusText.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_slide_in))
    }

    private fun refreshStatusDisplay() {
        val hasDriveFile = DriveClient.hasFile(this)
        lifecycleScope.launch {
            val healthAvailability = healthManager.availability()
            val hasHealth = runCatching { healthManager.hasPermissions() }.getOrDefault(false)
            statusText.text = buildString {
                appendLine("Health Connect: ${healthStatusText(healthAvailability, hasHealth)}")
                appendLine("Google Drive: ${if (hasDriveFile) "File connected" else "Tap button below"}")
                if (hasHealth && hasDriveFile) {
                    appendLine("\nReady to sync. Tap 'Sync Now' or 'Start Auto Sync'.")
                }
            }
        }
    }

    private fun openHealthConnectInstall() {
        updateStatus("Health Connect needs to be installed or updated. Opening Play Store...")
        try {
            startActivity(healthManager.installOrUpdateIntent())
        } catch (e: ActivityNotFoundException) {
            updateStatus("Open Play Store and install or update Health Connect, then try again.")
        }
    }

    private fun openHealthConnectPermissions() {
        try {
            startActivity(healthManager.managePermissionsIntent())
        } catch (e: ActivityNotFoundException) {
            updateStatus(
                "Open Health Connect settings manually:\n" +
                "Settings > Security & privacy > Privacy > Health Connect > App permissions > Health Sync"
            )
        }
    }

    private fun healthStatusText(
        availability: HealthConnectManager.Availability,
        hasPermissions: Boolean
    ): String {
        return when {
            hasPermissions -> "Connected"
            availability == HealthConnectManager.Availability.AVAILABLE -> "Tap button below"
            availability == HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> {
                "Install or update required"
            }
            else -> "Unavailable on this phone"
        }
    }

    private fun createDriveFileIntent(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "health_data.json")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    companion object {
        private const val RC_DRIVE_FILE = 100
    }
}
