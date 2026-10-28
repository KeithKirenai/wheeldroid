package com.wiicompiled.mkw.extractor

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance on-device disc extractor for Mario Kart Wii PAL (RMCP01).
 * Supports both .wbfs (block-mapped) and uncompressed .iso images.
 * Uses hardware-accelerated AES-128-CBC via Android's Conscrypt (ARMv8 Crypto Extensions).
 */
object WiiDiscExtractor {

    private const val TAG = "WiiDiscExtractor"

    // Mario Kart Wii PAL expected constants
    const val EXPECTED_GAME_ID = "RMCP01"
    val SUPPORTED_GAME_IDS = setOf("RMCP01", "RMCE01", "RMCJ01")
    const val WII_MAGIC = 0x5D1C9EA3L
    // PAL (RMCP01)
    const val EXPECTED_DOL_SHA256 = "80d18895b39c63bd80f457398bfcbb91b7d16ac116a41a88967e954080155b05"
    const val EXPECTED_REL_SHA256 = "16d9d146112541fefea701ecb5bc1a496f9d50e4a752fbb5b6778e7c6399f67d"
    // NTSC-U (RMCE01)
    const val EXPECTED_DOL_SHA256_NTSC_U = "d2beec1b1645fcd134efe9e7e63774b546667764ed8d431029daccd725995694"
    const val EXPECTED_REL_SHA256_NTSC_U = "1168107f8fdef27a356df76036db55afe4fbf7752606dbac991726701133a617"
    // Nintendo Wii Retail Common Key
    private val RETAIL_COMMON_KEY = byteArrayOf(
        0xeb.toByte(), 0xe4.toByte(), 0x2a.toByte(), 0x22.toByte(),
        0x5e.toByte(), 0x85.toByte(), 0x93.toByte(), 0xe4.toByte(),
        0x48.toByte(), 0xd9.toByte(), 0xc5.toByte(), 0x45.toByte(),
        0x73.toByte(), 0x81.toByte(), 0xaa.toByte(), 0xf7.toByte()
    )

    private const val CLUSTER_SIZE = 0x8000           // 32,768 bytes
    private const val CLUSTER_DATA_SIZE = 0x7C00      // 31,744 bytes
    private const val CLUSTER_HEADER_SIZE = 0x400     // 1,024 bytes

    data class ExtractionResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val gameId: String? = null,
        val extractedFilesCount: Int = 0,
        val totalBytesExtracted: Long = 0L,
        val dolVerified: Boolean = false,
        val relVerified: Boolean = false
    )

    interface DiscSource : AutoCloseable {
        fun readAt(position: Long, dst: ByteArray, offset: Int, length: Int)
        fun readAt(position: Long, length: Int): ByteArray {
            val buf = ByteArray(length)
            readAt(position, buf, 0, length)
            return buf
        }
    }

    class ChannelDiscSource(
        private val channel: FileChannel,
        private val pfd: ParcelFileDescriptor? = null
    ) : DiscSource {
        @Synchronized
        override fun readAt(position: Long, dst: ByteArray, offset: Int, length: Int) {
            var remaining = length
            var curPos = position
            var curDst = offset
            while (remaining > 0) {
                val buf = ByteBuffer.wrap(dst, curDst, remaining)
                val readBytes = channel.read(buf, curPos)
                if (readBytes <= 0) {
                    throw java.io.EOFException("Unexpected EOF reading disc at offset $curPos (requested $remaining bytes)")
                }
                curPos += readBytes
                curDst += readBytes
                remaining -= readBytes
            }
        }

        override fun close() {
            try { channel.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    class WbfsDiscSource(
        private val rawSource: DiscSource,
        private val wbfsSectorSize: Long,
        private val blockTable: IntArray
    ) : DiscSource {
        override fun readAt(position: Long, dst: ByteArray, offset: Int, length: Int) {
            var remaining = length
            var curPos = position
            var curDst = offset

            while (remaining > 0) {
                val blockIndex = (curPos / wbfsSectorSize).toInt()
                val offsetInBlock = curPos % wbfsSectorSize
                val chunk = minOf(remaining.toLong(), wbfsSectorSize - offsetInBlock).toInt()

                if (blockIndex >= blockTable.size || blockTable[blockIndex] == 0) {
                    // Sparse unallocated block - fill with zeroes
                    dst.fill(0, curDst, curDst + chunk)
                } else {
                    val physBlock = blockTable[blockIndex]
                    val physPos = physBlock.toLong() * wbfsSectorSize + offsetInBlock
                    rawSource.readAt(physPos, dst, curDst, chunk)
                }

                curPos += chunk
                curDst += chunk
                remaining -= chunk
            }
        }

        override fun close() {
            rawSource.close()
        }
    }

    /**
     * Inspects and opens the disc container (detecting WBFS or ISO).
     */
    fun openDiscSource(context: Context, uri: Uri): DiscSource {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Could not open file descriptor for: $uri")
        val channel = FileInputStream(pfd.fileDescriptor).channel
        val rawSource = ChannelDiscSource(channel, pfd)
        return wrapIfWbfs(rawSource)
    }

    fun openDiscSource(file: File): DiscSource {
        val raf = RandomAccessFile(file, "r")
        val rawSource = ChannelDiscSource(raf.channel)
        return wrapIfWbfs(rawSource)
    }

    private fun wrapIfWbfs(rawSource: DiscSource): DiscSource {
        val header = rawSource.readAt(0, 512)
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        if (magic == "WBFS") {
            val hdSecSzS = header[8].toInt() and 0xFF
            val wbfsSecSzS = header[9].toInt() and 0xFF
            val hdSecSz = 1L shl hdSecSzS
            val wbfsSecSz = 1L shl wbfsSecSzS

            // Table of disc headers starts at 1 hd sector (offset hdSecSz, usually 0x200)
            val discInfoOffset = hdSecSz
            val discHeader = rawSource.readAt(discInfoOffset, 0x100)

            // wlba_table starts right after disc_header_copy (0x100 bytes)
            val wlbaOffset = discInfoOffset + 0x100
            val maxWbfsBlocks = 2241 // Maximum 2MB blocks for 4.37 GB Wii disc
            val wlbaBytes = rawSource.readAt(wlbaOffset, maxWbfsBlocks * 2)

            val blockTable = IntArray(maxWbfsBlocks)
            for (i in 0 until maxWbfsBlocks) {
                val b0 = wlbaBytes[i * 2].toInt() and 0xFF
                val b1 = wlbaBytes[i * 2 + 1].toInt() and 0xFF
                blockTable[i] = (b0 shl 8) or b1
            }

            Log.i(TAG, "Opened WBFS image: sectorSize=$wbfsSecSz, mappedBlocks=${blockTable.count { it != 0 }}")
            return WbfsDiscSource(rawSource, wbfsSecSz, blockTable)
        }

        Log.i(TAG, "Opened raw ISO disc image")
        return rawSource
    }

    /**
     * Inspects a disc image to check Game ID and version without full extraction.
     */
    fun inspectDisc(source: DiscSource): Pair<String, Long> {
        val header = source.readAt(0, 0x20)
        val gameId = String(header, 0, 6, Charsets.US_ASCII).trimEnd(0.toChar())
        val magic = readIntBE(header, 0x18).toLong() and 0xFFFFFFFFL
        return Pair(gameId, magic)
    }

    /**
     * Extracts and validates the complete Mario Kart Wii PAL game assets.
     */
    fun extract(
        source: DiscSource,
        destDirectory: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (status: String, percent: Int) -> Unit
    ): ExtractionResult {
        onProgress("Validating disc image...", 1)

        val (gameId, magic) = inspectDisc(source)
        Log.i(TAG, "Inspecting disc: GameID='$gameId', Magic=0x${magic.toString(16)}")

        if (magic != WII_MAGIC) {
            return ExtractionResult(
                success = false,
                errorMessage = "Invalid Wii disc image (magic mismatch: 0x${magic.toString(16)})."
            )
        }

        if (gameId !in SUPPORTED_GAME_IDS) {
            val errorMsg = when (gameId) {
                "RMCK01" -> "Detected Korean version (RMCK01). Supported versions are PAL (RMCP01), USA (RMCE01), and Japan (RMCJ01)."
                else -> "Unsupported game ID: '$gameId'. Expected Mario Kart Wii PAL (RMCP01), USA (RMCE01), or Japan (RMCJ01)."
            }
            return ExtractionResult(success = false, errorMessage = errorMsg, gameId = gameId)
        }

        if (isCancelled()) return ExtractionResult(success = false, errorMessage = "Extraction cancelled by user.")

        // 1. Locate Data Partition from Partition Table at 0x40000
        onProgress("Reading disc partition table...", 3)
        val partHeader = source.readAt(0x40000, 0x20)
        val numPartitions = readIntBE(partHeader, 0)
        val partTableOffset = (readIntBE(partHeader, 4).toLong() and 0xFFFFFFFFL) shl 2

        var dataPartitionOffset: Long = -1L
        val partEntries = source.readAt(partTableOffset, numPartitions * 8)
        for (i in 0 until numPartitions) {
            val offsetWords = readIntBE(partEntries, i * 8).toLong() and 0xFFFFFFFFL
            val type = readIntBE(partEntries, i * 8 + 4)
            val partOffset = offsetWords shl 2
            Log.i(TAG, "Partition $i: offset=0x${partOffset.toString(16)}, type=$type (0=Data)")
            if (type == 0) {
                dataPartitionOffset = partOffset
                break
            }
        }

        if (dataPartitionOffset < 0) {
            return ExtractionResult(
                success = false,
                errorMessage = "Data partition (type 0) not found on disc."
            )
        }

        // 2. Decrypt Title Key using Wii Common Key and Ticket
        onProgress("Decrypting partition cryptographic keys...", 5)
        val ticket = source.readAt(dataPartitionOffset, 0x2A4)
        val encTitleKey = ticket.copyOfRange(0x1BF, 0x1CF)
        val titleId = ticket.copyOfRange(0x1DC, 0x1E4)

        val ticketIv = ByteArray(16)
        System.arraycopy(titleId, 0, ticketIv, 0, 8) // IV = TitleID + 8 zeroes

        val commonCipher = Cipher.getInstance("AES/CBC/NoPadding")
        commonCipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(RETAIL_COMMON_KEY, "AES"),
            IvParameterSpec(ticketIv)
        )
        val titleKey = commonCipher.doFinal(encTitleKey)
        val titleKeySpec = SecretKeySpec(titleKey, "AES")
        Log.i(TAG, "Decrypted Title Key successfully (ID: ${bytesToHex(titleId)})")

        if (isCancelled()) return ExtractionResult(success = false, errorMessage = "Extraction cancelled by user.")

        // 3. Initialize Decrypted Cluster Streamer
        val encDataOffset = dataPartitionOffset + 0x20000
        val clusterReader = DecryptedClusterReader(source, encDataOffset, titleKeySpec)

        // 4. Create output directories
        val sysDir = File(destDirectory, "sys")
        val filesDir = File(destDirectory, "files")
        sysDir.mkdirs()
        filesDir.mkdirs()

        // 5. Read boot.bin and system offsets
        onProgress("Extracting boot information...", 7)
        val bootBin = clusterReader.read(0, 0x440)
        File(sysDir, "boot.bin").writeBytes(bootBin)

        val bi2Bin = clusterReader.read(0x440, 0x2000)
        File(sysDir, "bi2.bin").writeBytes(bi2Bin)

        val dolOffset = (readIntBE(bootBin, 0x420).toLong() and 0xFFFFFFFFL) shl 2
        val fstOffset = (readIntBE(bootBin, 0x424).toLong() and 0xFFFFFFFFL) shl 2
        val fstSize = (readIntBE(bootBin, 0x428).toLong() and 0xFFFFFFFFL) shl 2

        Log.i(TAG, "System layout: dolOffset=0x${dolOffset.toString(16)}, fstOffset=0x${fstOffset.toString(16)}, fstSize=$fstSize")

        // 6. Extract apploader.img
        val apploaderHdr = clusterReader.read(0x2440, 0x20)
        val apploaderSize = readIntBE(apploaderHdr, 0x14)
        val apploaderTrailer = readIntBE(apploaderHdr, 0x18)
        val apploaderTotal = 0x20 + apploaderSize + apploaderTrailer
        val apploaderBytes = clusterReader.read(0x2440, apploaderTotal)
        File(sysDir, "apploader.img").writeBytes(apploaderBytes)

        // 7. Extract main.dol
        onProgress("Extracting main.dol...", 8)
        val dolHdr = clusterReader.read(dolOffset, 0x100)
        var maxDolEnd = 0L
        for (i in 0 until 7) {
            val off = readIntBE(dolHdr, i * 4).toLong() and 0xFFFFFFFFL
            val sz = readIntBE(dolHdr, 0x90 + i * 4).toLong() and 0xFFFFFFFFL
            if (off + sz > maxDolEnd) maxDolEnd = off + sz
        }
        for (i in 0 until 11) {
            val off = readIntBE(dolHdr, 0x1C + i * 4).toLong() and 0xFFFFFFFFL
            val sz = readIntBE(dolHdr, 0xAC + i * 4).toLong() and 0xFFFFFFFFL
            if (off + sz > maxDolEnd) maxDolEnd = off + sz
        }

        val dolFile = File(sysDir, "main.dol")
        val dolDigest = MessageDigest.getInstance("SHA-256")
        clusterReader.writeToStream(dolOffset, maxDolEnd, dolFile, dolDigest)
        val dolSha = bytesToHex(dolDigest.digest())
        val dolVerified = dolSha.equals(EXPECTED_DOL_SHA256, ignoreCase = true)
        Log.i(TAG, "main.dol extracted (${maxDolEnd} bytes), sha256=$dolSha (verified=$dolVerified)")

        // 8. Extract fst.bin
        onProgress("Parsing File System Table (fst.bin)...", 10)
        val fstBytes = clusterReader.read(fstOffset, fstSize.toInt())
        File(sysDir, "fst.bin").writeBytes(fstBytes)

        // 9. Parse FST entries
        val entryCount = readIntBE(fstBytes, 8)
        val stringTableOffset = entryCount * 12
        Log.i(TAG, "FST parsed: total entries = $entryCount")

        fun getStringAt(offset: Int): String {
            var end = stringTableOffset + offset
            while (end < fstBytes.size && fstBytes[end] != 0.toByte()) {
                end++
            }
            return String(fstBytes, stringTableOffset + offset, end - (stringTableOffset + offset), Charsets.ISO_8859_1)
        }

        data class FstFile(val relativePath: String, val offset: Long, val length: Long)
        val filesToExtract = ArrayList<FstFile>(entryCount)

        val dirStack = ArrayDeque<Pair<Int, String>>()
        dirStack.add(Pair(entryCount, ""))

        for (i in 1 until entryCount) {
            val entryStart = i * 12
            val isDir = fstBytes[entryStart] != 0.toByte()
            val nameOff = ((fstBytes[entryStart + 1].toInt() and 0xFF) shl 16) or
                    ((fstBytes[entryStart + 2].toInt() and 0xFF) shl 8) or
                    (fstBytes[entryStart + 3].toInt() and 0xFF)
            val name = getStringAt(nameOff)

            while (dirStack.isNotEmpty() && i >= dirStack.last().first) {
                dirStack.removeLast()
            }

            val parentDir = dirStack.last().second
            val fullPath = if (parentDir.isEmpty()) name else "$parentDir/$name"

            if (isDir) {
                val nextIdx = readIntBE(fstBytes, entryStart + 8)
                dirStack.add(Pair(nextIdx, fullPath))
                File(filesDir, fullPath).mkdirs()
            } else {
                val fileOffset = (readIntBE(fstBytes, entryStart + 4).toLong() and 0xFFFFFFFFL) shl 2
                val fileLen = readIntBE(fstBytes, entryStart + 8).toLong() and 0xFFFFFFFFL
                filesToExtract.add(FstFile(fullPath, fileOffset, fileLen))
            }
        }

        Log.i(TAG, "Discovered ${filesToExtract.size} game files in FST table")

        // 10. Extract all files with sequential streaming
        var totalBytesExtracted = 0L
        var relVerified = false
        val totalFiles = filesToExtract.size
        val relDigest = MessageDigest.getInstance("SHA-256")

        for (index in filesToExtract.indices) {
            if (isCancelled()) {
                return ExtractionResult(success = false, errorMessage = "Extraction cancelled by user.")
            }

            val f = filesToExtract[index]
            val progressPercent = 10 + ((index.toFloat() / totalFiles) * 85).toInt()

            if (index % 15 == 0 || index == totalFiles - 1) {
                onProgress("Extracting: ${f.relativePath}", progressPercent)
            }

            val targetFile = File(filesDir, f.relativePath)
            targetFile.parentFile?.mkdirs()

            val isStaticRel = f.relativePath.endsWith("StaticR.rel", ignoreCase = true)
            val digest = if (isStaticRel) relDigest else null

            clusterReader.writeToStream(f.offset, f.length, targetFile, digest)
            totalBytesExtracted += f.length

            if (isStaticRel) {
                val relSha = bytesToHex(relDigest.digest())
                relVerified = relSha.equals(EXPECTED_REL_SHA256, ignoreCase = true)
                Log.i(TAG, "StaticR.rel extracted (${f.length} bytes), sha256=$relSha (verified=$relVerified)")
            }
        }

        onProgress("Verifying extracted assets...", 98)
        if (!dolVerified) {
            Log.w(TAG, "Warning: main.dol hash did not match pinned RMCP01 SHA256")
        }
        if (!relVerified) {
            Log.w(TAG, "Warning: StaticR.rel hash did not match pinned RMCP01 SHA256")
        }

        onProgress("Installation complete!", 100)
        return ExtractionResult(
            success = true,
            gameId = gameId,
            extractedFilesCount = totalFiles,
            totalBytesExtracted = totalBytesExtracted,
            dolVerified = dolVerified,
            relVerified = relVerified
        )
    }

    private class DecryptedClusterReader(
        private val source: DiscSource,
        private val encDataOffset: Long,
        private val keySpec: SecretKeySpec
    ) {
        private var cachedClusterIndex = -1L
        private val clusterRawBuffer = ByteArray(CLUSTER_SIZE)
        private val clusterDecryptedBuffer = ByteArray(CLUSTER_DATA_SIZE)
        private val clusterIv = ByteArray(16)
        private val cipher = Cipher.getInstance("AES/CBC/NoPadding")

        @Synchronized
        fun readCluster(clusterIndex: Long): ByteArray {
            if (cachedClusterIndex != clusterIndex) {
                val clusterPhysPos = encDataOffset + clusterIndex * CLUSTER_SIZE
                source.readAt(clusterPhysPos, clusterRawBuffer, 0, CLUSTER_SIZE)

                // IV is stored at offset 0x3D0 inside the 0x400 cluster header
                System.arraycopy(clusterRawBuffer, 0x3D0, clusterIv, 0, 16)

                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(clusterIv))
                cipher.doFinal(
                    clusterRawBuffer,
                    CLUSTER_HEADER_SIZE,
                    CLUSTER_DATA_SIZE,
                    clusterDecryptedBuffer,
                    0
                )
                cachedClusterIndex = clusterIndex
            }
            return clusterDecryptedBuffer
        }

        fun read(offset: Long, size: Int): ByteArray {
            val result = ByteArray(size)
            var remaining = size
            var curOffset = offset
            var curDst = 0

            while (remaining > 0) {
                val clusterIndex = curOffset / CLUSTER_DATA_SIZE
                val offsetInCluster = (curOffset % CLUSTER_DATA_SIZE).toInt()
                val chunk = minOf(remaining, CLUSTER_DATA_SIZE - offsetInCluster)

                val dec = readCluster(clusterIndex)
                System.arraycopy(dec, offsetInCluster, result, curDst, chunk)

                curOffset += chunk
                curDst += chunk
                remaining -= chunk
            }
            return result
        }

        fun writeToStream(
            offset: Long,
            size: Long,
            targetFile: File,
            digest: MessageDigest? = null
        ) {
            BufferedOutputStream(FileOutputStream(targetFile), 64 * 1024).use { out ->
                var remaining = size
                var curOffset = offset

                while (remaining > 0) {
                    val clusterIndex = curOffset / CLUSTER_DATA_SIZE
                    val offsetInCluster = (curOffset % CLUSTER_DATA_SIZE).toInt()
                    val chunk = minOf(remaining, (CLUSTER_DATA_SIZE - offsetInCluster).toLong()).toInt()

                    val dec = readCluster(clusterIndex)
                    out.write(dec, offsetInCluster, chunk)
                    digest?.update(dec, offsetInCluster, chunk)

                    curOffset += chunk
                    remaining -= chunk
                }
                out.flush()
            }
        }
    }

    private fun readIntBE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
