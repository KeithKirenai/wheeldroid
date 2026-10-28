package com.wiicompiled.mkw

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wiicompiled.mkw.databinding.ActivityMainBinding
import com.wiicompiled.mkw.extractor.WiiDiscExtractor
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val driverPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val result = CustomDriverManager.installDriverZip(this, uri)
            result.onSuccess { driver ->
                Toast.makeText(this, "Installed driver: ${driver.name}", Toast.LENGTH_SHORT).show()
                CustomDriverManager.setActiveDriver(this, driver.id)
                refreshDriversList()
            }.onFailure { err ->
                Toast.makeText(this, "Failed to install driver: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkPermissionsAndData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateLogsButtonSize()

        setupConfigOptions()
        refreshDriversList()
        binding.btnInstallDriver.setOnClickListener { driverPickerLauncher.launch("application/zip") }
        checkPermissionsAndData()

        // Auto-install prompt skipped to streamline main menu
        // User can tap "Install Game Disc" button anytime

        binding.btnInstallGameData.setOnClickListener {
            installLauncher.launch(Intent(this, InstallActivity::class.java))
        }

        binding.launchBtn.setOnClickListener {
            launchGame()
        }

        binding.btnRemapper.setOnClickListener {
            startActivity(Intent(this, RemapperActivity::class.java))
        }

        binding.btnEditTouchLayout.setOnClickListener {
            startActivity(Intent(this, TouchControlsEditorActivity::class.java))
        }

        binding.exportLogsBtn.setOnClickListener {
            exportLogs()
        }

        binding.btnClearLogs.setOnClickListener {
            val logsDir = File(filesDir, "WiiCompiled/Logs")
            val currentBytes = getFolderSize(logsDir)

            if (currentBytes == 0L) {
                Toast.makeText(this, "Logs are already empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Clear Crash Logs?")
                .setMessage("This will delete all stored core dumps and diagnostic logs (${formatSize(currentBytes)}). Saved games and settings are untouched.")
                .setPositiveButton("Clear") { _, _ ->
                    if (logsDir.exists()) {
                        logsDir.deleteRecursively()
                        logsDir.mkdirs()
                    }
                    updateLogsButtonSize()
                    Toast.makeText(this, "Crash logs deleted!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnDeleteGameData.setOnClickListener {
            confirmDeleteGameData()
        }

        binding.btnWipeSaveData.setOnClickListener {
            wipeSaveData()
        }

        binding.btnAdvancedSettings.setOnClickListener {
            showAdvancedSettingsDialog()
        }
    }

    private fun refreshDriversList() {
        val drivers = CustomDriverManager.listDrivers(this)
        val activeDriver = CustomDriverManager.getActiveDriver(this)

        val driverOptions = mutableListOf("System Default (Vulkan)")
        val driverIds = mutableListOf(CustomDriverManager.DEFAULT_SYSTEM_DRIVER_ID)

        drivers.forEach { d ->
            driverOptions.add("${d.name} (${d.version})")
            driverIds.add(d.id)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, driverOptions)
        binding.spinnerCustomDriver.adapter = adapter

        val selectedIdx = if (activeDriver != null) {
            val found = driverIds.indexOf(activeDriver.id)
            if (found >= 0) found else 0
        } else {
            0
        }

        binding.spinnerCustomDriver.setSelection(selectedIdx)
        binding.textCurrentDriver.text = if (activeDriver != null) {
            "${activeDriver.name} (${activeDriver.version})"
        } else {
            "System Default"
        }

        binding.spinnerCustomDriver.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosenId = driverIds[position]
                CustomDriverManager.setActiveDriver(this@MainActivity, chosenId)
                val chosen = CustomDriverManager.getActiveDriver(this@MainActivity)
                binding.textCurrentDriver.text = if (chosen != null) {
                    "${chosen.name} (${chosen.version})"
                } else {
                    "System Default"
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun showAdvancedSettingsDialog() {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_advanced_settings, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val spinnerScalingFilter = dialogView.findViewById<android.widget.Spinner>(R.id.dialogSpinnerScalingFilter)
        val layoutFsrSharpness = dialogView.findViewById<View>(R.id.dialogLayoutFsrSharpness)
        val sliderFsrSharpness = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.dialogSliderFsrSharpness)
        val textFsrSharpness = dialogView.findViewById<android.widget.TextView>(R.id.dialogTextFsrSharpness)

        val switchWidescreen = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchWidescreen)
        val switchExtendToNotch = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchExtendToNotch)
        val switchSkipUnreadyPipelines = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchSkipUnreadyPipelines)
        val switchDisableCopyFilter = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchDisableCopyFilter)
        val switchDisableBloom = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchDisableBloom)
        val switchSustainedPerf = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchSustainedPerf)
        val switchForce30Fps = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchForce30Fps)
        val switchAudioMixer = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchAudioMixer)
        val switchTouchControls = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchTouchControls)
        val switchTiltControls = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchTiltControls)
        val switchAudioMuted = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dialogSwitchAudioMuted)
        val btnClose = dialogView.findViewById<View>(R.id.dialogBtnClose)

        val filterOptions = arrayOf(
            "Off — Hardware / DPU Scale (Low-End)",
            "Bilinear (Standard)",
            "Nearest Neighbor (Integer / Sharp)",
            "Bicubic (Catmull-Rom)",
            "AMD FSR 1.0 (Eden / FidelityFX)"
        )
        val filterAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, filterOptions)
        spinnerScalingFilter.adapter = filterAdapter
        val savedFilterIdx = prefs.getInt("scaling_filter_idx", 0)
        spinnerScalingFilter.setSelection(savedFilterIdx.coerceIn(0, filterOptions.size - 1))

        val fsrSharpness = prefs.getInt("fsr_sharpness", 80)
        sliderFsrSharpness.value = fsrSharpness.toFloat()
        textFsrSharpness.text = "$fsrSharpness%"
        layoutFsrSharpness.visibility = if (savedFilterIdx == 4) View.VISIBLE else View.GONE
        sliderFsrSharpness.addOnChangeListener { _, value, _ ->
            textFsrSharpness.text = "${value.toInt()}%"
        }

        switchWidescreen.isChecked = prefs.getBoolean("widescreen", true)
        switchExtendToNotch.isChecked = prefs.getBoolean("extend_to_notch", true)
        switchSkipUnreadyPipelines.isChecked = prefs.getBoolean("skip_unready_pipelines", true)
        switchDisableCopyFilter.isChecked = prefs.getBoolean("disable_copy_filter", true)
        switchDisableBloom.isChecked = prefs.getBoolean("disable_bloom", true)
        switchSustainedPerf.isChecked = prefs.getBoolean("sustained_perf", true)
        switchForce30Fps.isChecked = prefs.getBoolean("force_30fps", false)
        switchAudioMixer.isChecked = prefs.getBoolean("audio_mixer", true)
        switchTouchControls.isChecked = prefs.getBoolean("touch_controls", true)
        switchTiltControls.isChecked = prefs.getBoolean("tilt_controls", true)
        switchAudioMuted.isChecked = prefs.getBoolean("audio_muted", false)

        fun saveAdvanced() {
            val selectedFilter = spinnerScalingFilter.selectedItemPosition
            prefs.edit()
                .putInt("scaling_filter_idx", selectedFilter)
                .putBoolean("low_end_present", selectedFilter == 0)
                .putInt("fsr_sharpness", sliderFsrSharpness.value.toInt())
                .putBoolean("widescreen", switchWidescreen.isChecked)
                .putBoolean("extend_to_notch", switchExtendToNotch.isChecked)
                .putBoolean("skip_unready_pipelines", switchSkipUnreadyPipelines.isChecked)
                .putBoolean("disable_copy_filter", switchDisableCopyFilter.isChecked)
                .putBoolean("disable_bloom", switchDisableBloom.isChecked)
                .putBoolean("sustained_perf", switchSustainedPerf.isChecked)
                .putBoolean("force_30fps", switchForce30Fps.isChecked)
                .putBoolean("audio_mixer", switchAudioMixer.isChecked)
                .putBoolean("touch_controls", switchTouchControls.isChecked)
                .putBoolean("tilt_controls", switchTiltControls.isChecked)
                .putBoolean("audio_muted", switchAudioMuted.isChecked)
                .apply()
            saveConfigOptions()
        }

        spinnerScalingFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                layoutFsrSharpness.visibility = if (position == 4) View.VISIBLE else View.GONE
                saveAdvanced()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val switchListener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ -> saveAdvanced() }
        switchWidescreen.setOnCheckedChangeListener(switchListener)
        switchExtendToNotch.setOnCheckedChangeListener(switchListener)
        switchSkipUnreadyPipelines.setOnCheckedChangeListener(switchListener)
        switchDisableCopyFilter.setOnCheckedChangeListener(switchListener)
        switchDisableBloom.setOnCheckedChangeListener(switchListener)
        switchSustainedPerf.setOnCheckedChangeListener(switchListener)
        switchForce30Fps.setOnCheckedChangeListener(switchListener)
        switchAudioMixer.setOnCheckedChangeListener(switchListener)
        switchTouchControls.setOnCheckedChangeListener(switchListener)
        switchTiltControls.setOnCheckedChangeListener(switchListener)
        switchAudioMuted.setOnCheckedChangeListener(switchListener)

        sliderFsrSharpness.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                saveAdvanced()
            }
        })

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupSection(
        header: View,
        card: View,
        arrow: android.widget.ImageView,
        prefKey: String,
        defaultExpanded: Boolean
    ) {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        var isExpanded = prefs.getBoolean(prefKey, defaultExpanded)

        fun updateVisuals(animate: Boolean) {
            card.visibility = if (isExpanded) View.VISIBLE else View.GONE
            val targetRotation = if (isExpanded) 0f else -90f
            if (animate) {
                arrow.animate().rotation(targetRotation).setDuration(200).start()
            } else {
                arrow.rotation = targetRotation
            }
        }

        updateVisuals(animate = false)

        header.setOnClickListener {
            isExpanded = !isExpanded
            prefs.edit().putBoolean(prefKey, isExpanded).apply()
            updateVisuals(animate = true)
        }
    }

    private fun confirmDeleteGameData() {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        val configuredRoot = prefs.getString("dvd_root", null)?.let { File(it) }
        val sdcardData = File("/sdcard/Download/wiicompiled_data")
        val internalData = File(filesDir, "game_data")

        val targetDirs = mutableListOf<File>()
        if (configuredRoot != null && configuredRoot.exists()) targetDirs.add(configuredRoot)
        if (sdcardData.exists() && !targetDirs.contains(sdcardData)) targetDirs.add(sdcardData)
        if (internalData.exists() && !targetDirs.contains(internalData)) targetDirs.add(internalData)

        if (targetDirs.isEmpty()) {
            Toast.makeText(this, "No installed game data found", Toast.LENGTH_SHORT).show()
            return
        }

        val loadingDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Calculating size...")
            .setMessage("Scanning game files, please wait...")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        Thread {
            var totalBytes = 0L
            targetDirs.forEach { totalBytes += getFolderSize(it) }

            runOnUiThread {
                loadingDialog.dismiss()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Game Data?")
                    .setMessage("This will completely remove the extracted game files (${formatSize(totalBytes)}) from:\n\n" +
                            targetDirs.joinToString("\n") { "• ${it.absolutePath}" } +
                            "\n\nYou will need to install a Mario Kart Wii PAL disc again to play. Your save game (NAND) will NOT be deleted.")
                    .setPositiveButton("Delete Everything") { _, _ ->
                        val progressDialog = MaterialAlertDialogBuilder(this)
                            .setTitle("Deleting Game Data...")
                            .setMessage("Removing thousands of game files from storage...")
                            .setCancelable(false)
                            .create()
                        progressDialog.show()

                        Thread {
                            var success = true
                            targetDirs.forEach { dir ->
                                if (dir.exists()) {
                                    success = success && dir.deleteRecursively()
                                }
                            }
                            runOnUiThread {
                                progressDialog.dismiss()
                                prefs.edit().remove("dvd_root").apply()
                                checkPermissionsAndData()
                                Toast.makeText(
                                    this,
                                    if (success) "Game data deleted successfully" else "Some files could not be deleted",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }.start()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun wipeSaveData() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Wipe Save Data")
            .setMessage("This will delete the save file (rksys.dat) and FaceLib database files (RFL_DB.dat, RFL_Res.dat) from the NAND. This simulates a fresh install. Continue?")
            .setPositiveButton("Wipe Data") { _, _ ->
                try {
                    // The NAND root is typically at <app_data>/Wiicompiled/NAND/
                    val nandDir = File(filesDir, "WiiCompiled/NAND")
                    // MKW save file location: title/00010004/524d4350/data/rksys.dat
                    val saveFile = File(nandDir, "title/00010004/524d4350/data/rksys.dat")
                    // Also check legacy location shared2/menu/rksys/rksys.bin
                    val legacySaveFile = File(nandDir, "shared2/menu/rksys/rksys.bin")
                    val faceLibDir = File(nandDir, "shared2/menu/FaceLib")
                    val rflDbFile = File(faceLibDir, "RFL_DB.dat")
                    val rflResFile = File(faceLibDir, "RFL_Res.dat")

                    val deletedFiles = mutableListOf<String>()
                    val failedFiles = mutableListOf<String>()

                    fun deleteFile(file: File, label: String) {
                        if (file.exists()) {
                            try {
                                if (file.delete()) {
                                    deletedFiles.add(label)
                                } else {
                                    failedFiles.add("$label (delete failed)")
                                }
                            } catch (e: SecurityException) {
                                failedFiles.add("$label (${e.message})")
                            }
                        } else {
                            deletedFiles.add("$label (not present)")
                        }
                    }

                    deleteFile(saveFile, "rksys.dat (save file)")
                    deleteFile(legacySaveFile, "shared2/menu/rksys/rksys.bin (legacy save)")
                    deleteFile(rflDbFile, "FaceLib/RFL_DB.dat")
                    deleteFile(rflResFile, "FaceLib/RFL_Res.dat")

                    val summary = "Deleted:\n" + deletedFiles.joinToString("\n") { "  ✓ $it" } +
                            "\n\nFailed:\n" + (if (failedFiles.isEmpty()) "  (none)" else failedFiles.joinToString("\n") { "  ✗ $it" })

                    MaterialAlertDialogBuilder(this)
                        .setTitle("Data Wiped")
                        .setMessage(summary)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (e: Exception) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Wipe Error")
                        .setMessage("Failed to wipe save data: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportLogs() {
        try {
            val logsDir = File(filesDir, "WiiCompiled/Logs")
            val cacheLogs = File(cacheDir, "diagnostic_logs.txt")
            val sb = java.lang.StringBuilder()
            sb.append("=== WiiCompiled Android Diagnostic Log ===\n")
            sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})\n")
            sb.append("Hardware: ${android.os.Build.HARDWARE}, Board: ${android.os.Build.BOARD}\n")
            sb.append("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n\n")

            if (logsDir.exists() && logsDir.isDirectory) {
                val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() }
                if (!files.isNullOrEmpty()) {
                    sb.append("--- Native Logs Found (${files.size}) ---\n")
                    for (logFile in files.take(3)) {
                        sb.append("\n=== File: ${logFile.name} (${logFile.length()} bytes) ===\n")
                        try {
                            sb.append(logFile.readText())
                        } catch (re: Exception) {
                            sb.append("[Error reading file: ${re.message}]\n")
                        }
                    }
                } else {
                    sb.append("--- No native log files found in ${logsDir.absolutePath} ---\n")
                }
            } else {
                sb.append("--- Logs directory does not exist yet ---\n")
            }

            sb.append("\n--- Logcat (Filtered for WiiCompiled/MKW) ---\n")
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500"))
                val lines = process.inputStream.bufferedReader().readLines()
                val filtered = lines.filter {
                    it.contains("WiiCompiled", ignoreCase = true) ||
                    it.contains("mkw", ignoreCase = true) ||
                    it.contains("aurora", ignoreCase = true) ||
                    it.contains("AndroidRuntime", ignoreCase = true)
                }
                sb.append(filtered.takeLast(200).joinToString("\n"))
            } catch (le: Exception) {
                sb.append("[Could not dump logcat: ${le.message}]\n")
            }

            cacheLogs.writeText(sb.toString())

            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                cacheLogs
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "WiiCompiled Android Crash Logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Logs"))
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Log Export Error")
                .setMessage("Could not export logs: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showFpsAdvancedSettingsDialog() {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        val items = arrayOf(
            "Pass Breakdown (Encoder ms)",
            "Draw Calls & Merged Batches",
            "Bandwidth (Geometry / Textures)",
            "Pipeline Shader Builds & Cache %"
        )
        val keys = arrayOf(
            "show_fps_passes",
            "show_fps_draws",
            "show_fps_bandwidth",
            "show_fps_builds"
        )
        val defaults = booleanArrayOf(false, false, false, true)
        val checked = BooleanArray(keys.size) { i -> prefs.getBoolean(keys[i], defaults[i]) }

        MaterialAlertDialogBuilder(this)
            .setTitle("FPS Overlay Elements")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val editor = prefs.edit()
                for (i in keys.indices) {
                    editor.putBoolean(keys[i], checked[i])
                }
                editor.apply()
                saveConfigOptions()
                Toast.makeText(this, "FPS overlay settings saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchGame() {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        saveConfigOptions()
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra("SUSTAINED_PERF", prefs.getBoolean("sustained_perf", true))
            putExtra("EXTEND_TO_NOTCH", prefs.getBoolean("extend_to_notch", true))
        }
        startActivity(intent)
    }

    private fun setupConfigOptions() {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)

        // 1. Resolution
        val resMultiplierValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f)
        val resLabels = arrayOf(
            "0.5x (50%) [240p]",
            "0.75x (75%) [360p]",
            "1.0x (100%) [480p Native]",
            "1.5x (150%) [720p HD]",
            "2.0x (200%) [960p FHD]",
            "3.0x (300%) [1440p QHD]"
        )
        val savedResIdx = prefs.getInt("resolution_idx", 2).coerceIn(0, resMultiplierValues.size - 1)
        binding.sliderResolution.value = savedResIdx.toFloat()
        binding.textResolution.text = resLabels[savedResIdx]
        binding.sliderResolution.addOnChangeListener { _, value, _ ->
            val idx = value.toInt().coerceIn(0, resLabels.size - 1)
            binding.textResolution.text = resLabels[idx]
        }

        val autoSaveSlider = object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                saveConfigOptions()
            }
        }
        binding.sliderResolution.addOnSliderTouchListener(autoSaveSlider)
    }

    private fun saveConfigOptions() {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)

        val resIdx = binding.sliderResolution.value.toInt().coerceIn(0, 5)
        val scalingFilterIdx = prefs.getInt("scaling_filter_idx", 0)
        val lowEndPresent = scalingFilterIdx == 0
        val fsrSharpness = prefs.getInt("fsr_sharpness", 80)
        val widescreen = prefs.getBoolean("widescreen", true)
        val extendToNotch = prefs.getBoolean("extend_to_notch", true)
        val skipUnreadyPipelines = prefs.getBoolean("skip_unready_pipelines", true)
        val disableCopyFilter = prefs.getBoolean("disable_copy_filter", true)
        val disableBloom = prefs.getBoolean("disable_bloom", true)
        val sustainedPerf = prefs.getBoolean("sustained_perf", true)
        val force30Fps = prefs.getBoolean("force_30fps", false)
        val audioMixer = prefs.getBoolean("audio_mixer", true)

        val touchControls = prefs.getBoolean("touch_controls", true)
        val tiltControls = prefs.getBoolean("tilt_controls", true)

        val masterVol = prefs.getInt("audio_volume", 100)
        val musicVol = prefs.getInt("audio_music_volume", 100)
        val sfxVol = prefs.getInt("audio_sfx_volume", 100)
        val audioMuted = prefs.getBoolean("audio_muted", false)

        val networkEnabled = false

        prefs.edit()
            .putInt("resolution_idx", resIdx)
            .putBoolean("low_end_present", scalingFilterIdx == 0)
            .apply()

        val multiplier = when (resIdx) {
            0 -> "0.5"
            1 -> "0.75"
            2 -> "1.0"
            3 -> "1.5"
            4 -> "2.0"
            5 -> "3.0"
            else -> "1.0"
        }

        val scalingFilterStr = when (scalingFilterIdx) {
            0 -> "nearest" // Low-end hardware / DPU scaling: 1:1 copy, SurfaceFlinger stretches to the panel
            1 -> "bilinear"
            2 -> "nearest"
            3 -> "bicubic"
            4 -> "fsr"
            else -> "bilinear"
        }

        val frameInterpolationFps = 0
        val rumble = prefs.getBoolean("rumble", true)
        val showFps = prefs.getBoolean("show_fps", false)

        updateConfigFile(
            graphicsApi = "vulkan",
            resolutionMultiplier = multiplier,
            scalingFilter = scalingFilterStr,
            fsrSharpness = fsrSharpness / 100.0f,
            widescreen = widescreen,
            skipUnreadyPipelines = skipUnreadyPipelines,
            disableCopyFilter = disableCopyFilter,
            disableBloom = disableBloom,
            force30Fps = force30Fps,
            audioMixer = audioMixer,
            masterVolume = masterVol / 100.0f,
            musicVolume = musicVol / 100.0f,
            sfxVolume = sfxVol / 100.0f,
            audioMuted = audioMuted,
            frameInterpolationFps = frameInterpolationFps,
            rumble = rumble,
            showFps = showFps,
            networkEnabled = networkEnabled
        )
    }

    private fun updateConfigFile(
        graphicsApi: String = "vulkan",
        resolutionMultiplier: String,
        scalingFilter: String = "bilinear",
        fsrSharpness: Float = 0.8f,
        widescreen: Boolean,
        skipUnreadyPipelines: Boolean,
        disableCopyFilter: Boolean,
        disableBloom: Boolean,
        force30Fps: Boolean = false,
        audioMixer: Boolean,
        masterVolume: Float,
        musicVolume: Float,
        sfxVolume: Float,
        audioMuted: Boolean,
        frameInterpolationFps: Int,
        rumble: Boolean,
        textureReplacements: Boolean = false,
        showFps: Boolean,
        networkEnabled: Boolean,
        customDvdRoot: String? = null
    ) {
        val configDir = File(filesDir, "WiiCompiled")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        val configFile = File(configDir, "Config.toml")
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        val dvdRoot = customDvdRoot ?: prefs.getString("dvd_root", null)

        try {
            val pathsSection = if (!dvdRoot.isNullOrEmpty()) {
                "|[paths]\n|dvd_root = \"$dvdRoot\"\n|\n"
            } else ""

            val audioVolStr = String.format(java.util.Locale.US, "%.2f", masterVolume)
            val musicVolStr = String.format(java.util.Locale.US, "%.2f", musicVolume)
            val sfxVolStr = String.format(java.util.Locale.US, "%.2f", sfxVolume)
            val fsrSharpnessStr = String.format(java.util.Locale.US, "%.2f", fsrSharpness)
            val postProcessingPaths = if (disableBloom) 16 else 0

            val content = """
                |# WiiCompiled Android configuration (configured via launcher)
                |
                $pathsSection|[video]
                |widescreen = $widescreen
                |resolution_multiplier = $resolutionMultiplier
                |scaling_filter = "$scalingFilter"
                |fsr_sharpness = $fsrSharpnessStr
                |frame_interpolation_fps = $frameInterpolationFps
                |display_mode = "windowed"
                |graphics_api = "$graphicsApi"
                |skip_unready_pipelines = $skipUnreadyPipelines
                |disable_copy_filter = $disableCopyFilter
                |force_30fps = $force30Fps
                |disabled_post_processing_paths = $postProcessingPaths
                |show_fps = $showFps
                |show_fps_passes = ${prefs.getBoolean("show_fps_passes", false)}
                |show_fps_draws = ${prefs.getBoolean("show_fps_draws", false)}
                |show_fps_bandwidth = ${prefs.getBoolean("show_fps_bandwidth", false)}
                |show_fps_builds = ${prefs.getBoolean("show_fps_builds", true)}
                |texture_replacements = $textureReplacements
                |texture_dumps = false
                |
                |[audio]
                |volume = $audioVolStr
                |music_volume = $musicVolStr
                |sound_effects_volume = $sfxVolStr
                |ui_volume = 1.0
                |voices_volume = 1.0
                |muted = $audioMuted
                |mix_worker = $audioMixer
                |
                |[controller]
                |rumble = $rumble
                |wii_remotes = false
                |
                |[network]
                |enabled = $networkEnabled
                |
                |[discord]
                |enabled = false
            """.trimMargin() + "\n"

            configFile.writeText(content)
        } catch (e: Exception) {
            android.util.Log.e("WiiCompiled", "Failed to update Config.toml: " + e.message)
        }
    }

    private fun getFolderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    private fun pruneOldLogs(keepCount: Int = 3) {
        val logsDir = File(filesDir, "WiiCompiled/Logs")
        if (!logsDir.exists() || !logsDir.isDirectory) return

        try {
            val sessionDirs = logsDir.listFiles { file -> file.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            // If more than keepCount session folders exist, remove the oldest ones
            if (sessionDirs.size > keepCount) {
                for (oldDir in sessionDirs.drop(keepCount)) {
                    oldDir.deleteRecursively()
                    android.util.Log.i("WiiCompiled", "Pruned old log directory: ${oldDir.name}")
                }
            }

            // Also clean up any orphan guest memory crash dumps (.bin / .mem2) older than the latest session
            logsDir.walkTopDown().forEach { file ->
                if (file.isFile && (file.name.endsWith(".bin") || file.name.endsWith(".mem2"))) {
                    // Only keep dumps from the latest session if needed
                    val isInsideLatest = sessionDirs.take(1).any { file.absolutePath.startsWith(it.absolutePath) }
                    if (!isInsideLatest) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WiiCompiled", "Log pruning encountered an error: ${e.message}")
        }
    }

    private fun updateLogsButtonSize() {
        if (!::binding.isInitialized) return
        pruneOldLogs(keepCount = 3)
        binding.btnClearLogs.text = "Clear Logs"
    }

    override fun onPause() {
        super.onPause()
        saveConfigOptions()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updateLogsButtonSize()
        }
        checkPermissionsAndData()
    }

    private var hasPromptedStoragePermission = false

    private fun isGameDataInstalled(): Boolean {
        val prefs = getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        val configuredRoot = prefs.getString("dvd_root", null)?.let { File(it) }

        val isConfiguredValid = configuredRoot != null &&
                File(configuredRoot, "sys/main.dol").exists() &&
                File(configuredRoot, "files").isDirectory &&
                (File(configuredRoot, "files").list()?.isNotEmpty() == true)

        val sdcardSys = File("/sdcard/Download/wiicompiled_data/sys/main.dol")
        val sdcardFiles = File("/sdcard/Download/wiicompiled_data/files")
        val internalSys = File(filesDir, "game_data/sys/main.dol")
        val internalFiles = File(filesDir, "game_data/files")

        val hasSdcardData = sdcardSys.exists() && sdcardFiles.isDirectory && (sdcardFiles.list()?.isNotEmpty() == true)
        val hasInternalData = internalSys.exists() && internalFiles.isDirectory && (internalFiles.list()?.isNotEmpty() == true)

        return isConfiguredValid || hasSdcardData || hasInternalData
    }

    private fun checkPermissionsAndData() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager() && !hasPromptedStoragePermission) {
                hasPromptedStoragePermission = true
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val allIntent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(allIntent)
                    } catch (_: Exception) {}
                }
            }
        }

        val isInstalled = isGameDataInstalled()

        if (isInstalled) {
            binding.headerStatusBadge.backgroundTintList = getColorStateList(R.color.status_ready_container)
            binding.headerStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.headerStatusIcon.imageTintList = getColorStateList(R.color.status_ready)
            binding.headerStatusText.text = "Ready"
            binding.headerStatusText.setTextColor(getColor(R.color.status_ready))
            binding.launchBtn.isEnabled = true
        } else {
            binding.headerStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF381E1E.toInt())
            binding.headerStatusIcon.setImageResource(R.drawable.ic_error)
            binding.headerStatusIcon.imageTintList = null // Native red in ic_error
            binding.headerStatusText.text = "No Data"
            binding.headerStatusText.setTextColor(0xFFEF5350.toInt())
            binding.launchBtn.isEnabled = false
        }
    }
}
