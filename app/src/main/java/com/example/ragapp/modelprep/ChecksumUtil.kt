package com.example.ragapp.modelprep

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ChecksumUtil {
    fun sha256(file: File): String {
        val buf = ByteArray(DEFAULT_BUFFER)
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            var r: Int
            while (true) {
                r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private const val DEFAULT_BUFFER = 1024 * 1024 // 1MB
}
