package com.example.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Symbolizes instruction addresses in libmain.so using the embedded Go pclntab (program counter line table).
 * Enables symbol resolution on Android without requiring external llvm-symbolizer binaries.
 */
object GoPclntabResolver {
    private const val TAG = "GoPclntabResolver"
    private const val TEXT_SECTION_VADDR = 0x2882b0L
    private const val PCLNTAB_MAGIC_120 = 0xfffffff1.toInt() // 0xf1, 0xff, 0xff, 0xff in little endian (0xFFFFFFF1)

    private var isInitialized = false
    private val functionMap = java.util.TreeMap<Long, String>()

    /**
     * Attempts to initialize the function address table from libmain.so.
     */
    @Synchronized
    fun initialize(context: Context? = null): Boolean {
        if (isInitialized && functionMap.isNotEmpty()) return true

        val soFile = findLibmainFile(context)
        if (soFile != null && soFile.exists()) {
            try {
                RandomAccessFile(soFile, "r").use { raf ->
                    return parseFromRaf(raf)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading libmain.so from file: ${e.message}")
            }
        }

        // Try reading from APK
        if (context != null) {
            val apkPath = context.applicationInfo?.sourceDir
            if (!apkPath.isNullOrEmpty()) {
                try {
                    ZipFile(File(apkPath)).use { zip ->
                        val entry = zip.getEntry("lib/arm64-v8a/libmain.so")
                        if (entry != null) {
                            zip.getInputStream(entry).use { stream ->
                                val tempFile = File(context.cacheDir, "libmain_sym_cache.so")
                                if (!tempFile.exists() || tempFile.length() != entry.size) {
                                    tempFile.outputStream().use { out -> stream.copyTo(out) }
                                }
                                RandomAccessFile(tempFile, "r").use { raf ->
                                    return parseFromRaf(raf)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading libmain.so from APK: ${e.message}")
                }
            }
        }

        return false
    }

    private fun findLibmainFile(context: Context?): File? {
        // 1. Direct path in unit test / build tree
        val localPaths = listOf(
            File("app/src/main/jniLibs/arm64-v8a/libmain.so"),
            File("src/main/jniLibs/arm64-v8a/libmain.so"),
            File("../app/src/main/jniLibs/arm64-v8a/libmain.so")
        )
        for (f in localPaths) {
            if (f.exists()) return f
        }

        // 2. Installed native library dir
        if (context != null) {
            val nativeDir = context.applicationInfo?.nativeLibraryDir
            if (!nativeDir.isNullOrEmpty()) {
                val f = File(nativeDir, "libmain.so")
                if (f.exists()) return f
            }
        }

        return null
    }

    private fun parseFromRaf(raf: RandomAccessFile): Boolean {
        functionMap.clear()
        parseDynsymFromRaf(raf)
        val pclnSuccess = parsePclntabFromRaf(raf)
        if (functionMap.isNotEmpty()) {
            isInitialized = true
            return true
        }
        return pclnSuccess
    }

    private fun parseDynsymFromRaf(raf: RandomAccessFile) {
        try {
            val dynsymOff = 0x370L
            val dynsymSize = 0xe880L
            val dynstrOff = 0x1321cL
            val numSyms = (dynsymSize / 24).toInt()

            val dynsymBytes = ByteArray(dynsymSize.toInt())
            raf.seek(dynsymOff)
            raf.readFully(dynsymBytes)
            val bb = ByteBuffer.wrap(dynsymBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numSyms) {
                val stName = bb.getInt(i * 24)
                val stShndx = bb.getShort(i * 24 + 6).toInt() and 0xFFFF
                val stValue = bb.getLong(i * 24 + 8)

                if (stValue > 0 && stShndx != 0 && stName >= 0) {
                    raf.seek(dynstrOff + stName)
                    val name = readCString(raf)
                    if (name.isNotBlank()) {
                        functionMap[stValue] = name
                    }
                }
            }
            // Add known CGo runtime bootstrap entries
            functionMap[0x847930L] = "runtime.inittls"
            Log.d(TAG, "Indexed dynamic symbols from .dynsym, total map size now: ${functionMap.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal: failed reading .dynsym: ${e.message}")
        }
    }

    private fun parsePclntabFromRaf(raf: RandomAccessFile): Boolean {
        try {
            // Find pclntab offset: magic 0xf1, 0xff, 0xff, 0xff at offset 0x971e20
            val pclntabOffset = 0x971e20L
            raf.seek(pclntabOffset)
            val headerBytes = ByteArray(72)
            raf.readFully(headerBytes)
            val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = headerBuf.getInt(0)
            if (magic != PCLNTAB_MAGIC_120) {
                Log.w(TAG, "Pclntab magic mismatch: expected 0x${Integer.toHexString(PCLNTAB_MAGIC_120)}, got 0x${Integer.toHexString(magic)}")
                return false
            }

            val nfunc = headerBuf.getLong(8)
            val textStart = TEXT_SECTION_VADDR
            val funcnameOffset = headerBuf.getLong(32)
            val pclnOffset = headerBuf.getLong(64)

            val functabOffset = pclntabOffset + pclnOffset
            val funcnamesBase = pclntabOffset + funcnameOffset

            val limit = minOf(nfunc, 15000L).toInt()
            val entriesBuf = ByteArray(limit * 8)
            raf.seek(functabOffset)
            raf.readFully(entriesBuf)
            val bb = ByteBuffer.wrap(entriesBuf).order(ByteOrder.LITTLE_ENDIAN)

            val nameCache = mutableMapOf<Int, String>()

            for (i in 0 until limit) {
                val pcOff = bb.getInt(i * 8).toLong() and 0xFFFFFFFFL
                val funcOff = bb.getInt(i * 8 + 4).toLong() and 0xFFFFFFFFL

                // Read funcInfo.nameOff
                raf.seek(functabOffset + funcOff + 4)
                val nameOff = raf.readInt().let { Integer.reverseBytes(it) }

                val name = nameCache.getOrPut(nameOff) {
                    raf.seek(funcnamesBase + nameOff)
                    readCString(raf)
                }

                if (name.isNotEmpty()) {
                    functionMap[textStart + pcOff] = name
                }
            }

            isInitialized = true
            Log.i(TAG, "Successfully indexed ${functionMap.size} symbols from libmain.so (.dynsym + pclntab)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Go pclntab: ${e.message}")
            return false
        }
    }

    private fun readCString(raf: RandomAccessFile): String {
        val bytes = mutableListOf<Byte>()
        while (bytes.size < 256) {
            val b = raf.read()
            if (b <= 0) break
            bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Resolves a PC / relative instruction address in libmain.so to its function name and offset.
     * Returns null if unresolvable.
     */
    fun resolve(pc: Long): Pair<String, Long>? {
        if (!isInitialized && !initialize()) return null
        if (functionMap.isEmpty()) return null

        val entry = functionMap.floorEntry(pc) ?: return null
        val funcStart = entry.key
        val funcName = entry.value
        val offset = pc - funcStart

        // Safety: if the offset is unreasonably large (> 256KB), it's likely outside the function
        if (offset in 0..0x40000) {
            return Pair(funcName, offset)
        }
        return null
    }
}
