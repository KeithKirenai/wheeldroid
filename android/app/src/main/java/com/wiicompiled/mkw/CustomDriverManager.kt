package com.wiicompiled.mkw

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class CustomDriverInfo(
    val id: String, // Folder name
    val name: String,
    val description: String,
    val author: String,
    val vendor: String,
    val version: String,
    val libraryName: String,
    val rootDir: File
)

object CustomDriverManager {
    private const val TAG = "CustomDriverManager"
    private const val DRIVERS_DIR_NAME = "custom_drivers"
    const val DEFAULT_SYSTEM_DRIVER_ID = "system_default"

    fun getDriversDirectory(context: Context): File {
        val dir = File(context.filesDir, DRIVERS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun listDrivers(context: Context): List<CustomDriverInfo> {
        val baseDir = getDriversDirectory(context)
        val list = mutableListOf<CustomDriverInfo>()
        val subDirs = baseDir.listFiles { f -> f.isDirectory } ?: return list

        for (dir in subDirs) {
            val metaFile = File(dir, "meta.json")
            if (metaFile.exists()) {
                try {
                    val json = JSONObject(metaFile.readText(Charsets.UTF_8))
                    val libName = json.optString("libraryName", "libvulkan_freedreno.so")
                    val soFile = File(dir, libName)
                    if (soFile.exists()) {
                        list.add(
                            CustomDriverInfo(
                                id = dir.name,
                                name = json.optString("name", dir.name),
                                description = json.optString("description", ""),
                                author = json.optString("author", "Unknown"),
                                vendor = json.optString("vendor", "Mesa"),
                                version = json.optString("driverVersion", json.optString("packageVersion", "1.0")),
                                libraryName = libName,
                                rootDir = dir
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing meta.json in ${dir.absolutePath}", e)
                }
            } else {
                // Fallback: check if libvulkan_freedreno.so exists directly
                val defaultSo = File(dir, "libvulkan_freedreno.so")
                if (defaultSo.exists()) {
                    list.add(
                        CustomDriverInfo(
                            id = dir.name,
                            name = dir.name,
                            description = "Custom Turnip Driver",
                            author = "Turnip/Mesa",
                            vendor = "Mesa",
                            version = "Custom",
                            libraryName = "libvulkan_freedreno.so",
                            rootDir = dir
                        )
                    )
                }
            }
        }
        return list
    }

    fun getActiveDriver(context: Context): CustomDriverInfo? {
        val prefs = context.getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        val activeId = prefs.getString("custom_driver_id", DEFAULT_SYSTEM_DRIVER_ID)
        if (activeId == null || activeId == DEFAULT_SYSTEM_DRIVER_ID) {
            return null
        }
        return listDrivers(context).firstOrNull { it.id == activeId }
    }

    fun setActiveDriver(context: Context, driverId: String) {
        val prefs = context.getSharedPreferences("wiicompiled_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_driver_id", driverId).apply()
    }

    fun installDriverZip(context: Context, uri: Uri): Result<CustomDriverInfo> {
        return try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Cannot open file from Uri"))

            // Extract to temporary folder first
            val tempDir = File(context.cacheDir, "driver_import_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            unzip(inputStream, tempDir)

            // Check if files are at root or in single nested folder
            var targetDir = tempDir
            val metaAtRoot = File(tempDir, "meta.json")
            if (!metaAtRoot.exists()) {
                val nested = tempDir.listFiles { f -> f.isDirectory }
                if (nested != null && nested.size == 1 && File(nested[0], "meta.json").exists()) {
                    targetDir = nested[0]
                }
            }

            val metaFile = File(targetDir, "meta.json")
            var libName = "libvulkan_freedreno.so"
            var driverName = "Turnip Driver"
            var author = "Mesa"
            var vendor = "Mesa"
            var version = "1.0"
            var desc = ""

            if (metaFile.exists()) {
                val json = JSONObject(metaFile.readText(Charsets.UTF_8))
                libName = json.optString("libraryName", libName)
                driverName = json.optString("name", driverName)
                author = json.optString("author", author)
                vendor = json.optString("vendor", vendor)
                version = json.optString("driverVersion", json.optString("packageVersion", version))
                desc = json.optString("description", "")
            }

            val soFile = File(targetDir, libName)
            if (!soFile.exists()) {
                // If named differently, search for any .so file
                val anySo = targetDir.listFiles { f -> f.name.endsWith(".so") }?.firstOrNull()
                if (anySo != null) {
                    libName = anySo.name
                } else {
                    tempDir.deleteRecursively()
                    return Result.failure(Exception("Driver archive missing $libName or any .so file"))
                }
            }

            // Create unique driver ID
            val safeId = driverName.lowercase().replace(Regex("[^a-z0-9_]"), "_") + "_" + System.currentTimeMillis()
            val destDir = File(getDriversDirectory(context), safeId)
            destDir.mkdirs()

            // Move or copy files into destDir
            targetDir.listFiles()?.forEach { file ->
                file.copyTo(File(destDir, file.name), overwrite = true)
            }
            tempDir.deleteRecursively()

            val info = CustomDriverInfo(
                id = safeId,
                name = driverName,
                description = desc,
                author = author,
                vendor = vendor,
                version = version,
                libraryName = libName,
                rootDir = destDir
            )
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install driver zip", e)
            Result.failure(e)
        }
    }

    fun deleteDriver(context: Context, driverId: String): Boolean {
        val baseDir = getDriversDirectory(context)
        val dir = File(baseDir, driverId)
        if (dir.exists() && dir.isDirectory) {
            val active = getActiveDriver(context)
            if (active?.id == driverId) {
                setActiveDriver(context, DEFAULT_SYSTEM_DRIVER_ID)
            }
            return dir.deleteRecursively()
        }
        return false
    }

    private fun unzip(inputStream: InputStream, targetDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            val buffer = ByteArray(16384)
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                // Protect against Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry is outside of target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
