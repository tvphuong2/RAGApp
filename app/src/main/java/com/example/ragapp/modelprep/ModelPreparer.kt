package com.example.ragapp.modelprep

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

data class ModelReadyResult(
    val ready: Boolean,
    val message: String,
    val modelAbsolutePath: String?,
    val nCtxHint: Int?
)

class ModelPreparer(private val context: Context) {

    private val assetsRoot = "models_bootstrap"

    private companion object {
        private const val TAG = "ModelPreparer"
    }

    /**
     * Đảm bảo model trong manifest đã sẵn sàng (copy-once) + LOG CHI TIẾT.
     * - progressCb: (copiedBytes, totalBytes) → cập nhật UI
     * - cancelFlag: đặt true để hủy giữa chừng
     */
    suspend fun ensureModelReady(
        progressCb: (Long, Long) -> Unit,
        cancelFlag: AtomicBoolean
    ): ModelReadyResult = withContext(Dispatchers.IO) {
        val overallStartNs = SystemClock.elapsedRealtimeNanos()
        try {
            Log.i(TAG, "=== ensureModelReady: START ===")

            // 1) Đọc manifest
            val manifestPath = "$assetsRoot/manifest.json"
            Log.d(TAG, "Đọc manifest: $manifestPath")
            val manifestText = context.assets.open(manifestPath).use { it.readBytes().decodeToString() }
            val manifest = ModelManifest.fromJson(manifestText)
            if (manifest.models.isEmpty()) {
                Log.w(TAG, "Manifest rỗng")
                return@withContext ModelReadyResult(false, "Manifest rỗng", null, null)
            }
            val m = manifest.models.first() // giả định 1 model
            Log.i(TAG, "Manifest OK: name=${m.name}, version=${m.version}, file=${m.filename}, size=${m.sizeBytes}, nCtxHint=${m.nCtxHint}")

            // 2) Tính đường dẫn đích có version
            val destDir = File(context.filesDir, "models/${m.name}/${m.version}").apply { mkdirs() }
            val destFile = File(destDir, m.filename)
            val partFile = File(destDir, "${m.filename}.part")
            val statusFile = File(destDir, "status.json")
            Log.d(TAG, "Đích: dir=${destDir.absolutePath}, file=${destFile.name}")

            // 3) Idempotent: nếu file đã có & checksum khớp → xong
            if (destFile.exists()) {
                Log.d(TAG, "File đích đã tồn tại, kiểm tra checksum…")
                val startHashNs = SystemClock.elapsedRealtimeNanos()
                val ok = ChecksumUtil.sha256(destFile).equals(m.sha256, ignoreCase = true)
                val hashMs = (SystemClock.elapsedRealtimeNanos() - startHashNs) / 1_000_000
                Log.i(TAG, "Checksum hiện hữu=${ok}, thời gian=${hashMs}ms")
                if (ok) {
                    val totalMs = (SystemClock.elapsedRealtimeNanos() - overallStartNs) / 1_000_000
                    Log.i(TAG, "Model đã sẵn sàng (đã có sẵn). Tổng thời gian=${totalMs}ms")
                    return@withContext ModelReadyResult(true, "Model đã sẵn sàng (đã có sẵn)", destFile.absolutePath, m.nCtxHint)
                } else {
                    Log.w(TAG, "Checksum lệch → xóa file cũ để copy lại")
                    destFile.delete()
                }
            }

            // 4) (Tuỳ chọn) Có thể kiểm tra dung lượng trống bằng StatFs—bỏ qua ở đây
            Log.d(TAG, "Bắt đầu copy asset theo khối → .part (resume cơ bản)")

            // 5) Copy chunked từ assets → .part
            val copyStats = copyAssetChunked(
                assets = context.assets,
                assetPath = "$assetsRoot/${m.filename}",
                destPartFile = partFile,
                totalSize = m.sizeBytes,
                progressCb = progressCb,
                cancelFlag = cancelFlag
            )
            Log.i(
                TAG,
                "Copy kết thúc: bytes=${copyStats.bytesCopied}/${
                    if (m.sizeBytes > 0) m.sizeBytes else -1
                }, resumed=${copyStats.resumed}, avgSpeedMBps=%.2f, durationMs=%d".format(
                    copyStats.avgMBps, copyStats.durationMs
                )
            )

            if (cancelFlag.get()) {
                if (partFile.exists()) partFile.delete()
                Log.w(TAG, "Huỷ bởi người dùng, đã dọn .part")
                return@withContext ModelReadyResult(false, "Huỷ bởi người dùng", null, null)
            }

            // 6) Kiểm chứng checksum trên .part
            Log.d(TAG, "Kiểm tra checksum .part…")
            val startHashNs2 = SystemClock.elapsedRealtimeNanos()
            val hash = ChecksumUtil.sha256(partFile)
            val hashMs2 = (SystemClock.elapsedRealtimeNanos() - startHashNs2) / 1_000_000
            val hashOk = hash.equals(m.sha256, ignoreCase = true)
            Log.i(TAG, "Checksum .part khớp=${hashOk}, thời gian=${hashMs2}ms")
            if (!hashOk) {
                partFile.delete()
                Log.e(TAG, "Checksum sai: expected=${m.sha256}, actual=$hash → xoá .part")
                return@withContext ModelReadyResult(false, "Checksum sai: $hash", null, null)
            }

            // 7) Atomic rename .part → .gguf
            Log.d(TAG, "Atomic rename .part → ${destFile.name}")
            atomicRename(partFile, destFile)

            // 8) Ghi status.json nhỏ (tùy chọn)
            statusFile.writeText(
                """{"ready":true,"sha256":"${m.sha256}","version":"${m.version}","size":${m.sizeBytes}}"""
            )
            val totalMs = (SystemClock.elapsedRealtimeNanos() - overallStartNs) / 1_000_000
            Log.i(TAG, "Model copy thành công → ${destFile.absolutePath}. Tổng thời gian=${totalMs}ms, nCtxHint=${m.nCtxHint}")
            Log.i(TAG, "=== ensureModelReady: DONE ===")

            ModelReadyResult(true, "Model copy thành công", destFile.absolutePath, m.nCtxHint)
        } catch (e: Exception) {
            val totalMs = (SystemClock.elapsedRealtimeNanos() - overallStartNs) / 1_000_000
            Log.e(TAG, "Lỗi trong ensureModelReady (elapsed=${totalMs}ms): ${e.message}", e)
            ModelReadyResult(false, "Lỗi: ${e.message}", null, null)
        }
    }

    /**
     * Copy chunked với resume cơ bản + LOG tốc độ:
     * - Nếu .part đã tồn tại và nhỏ hơn totalSize → skip InputStream tương ứng.
     * - Cập nhật progress theo byte.
     * - Thống kê tốc độ tức thời & trung bình theo chu kỳ ~1s.
     * - fsync() để đảm bảo flush.
     */
    private fun copyAssetChunked(
        assets: AssetManager,
        assetPath: String,
        destPartFile: File,
        totalSize: Long,
        progressCb: (Long, Long) -> Unit,
        cancelFlag: AtomicBoolean,
        chunkSize: Int = 8 * 1024 * 1024 // 8MB
    ): CopyStats {
        val startNs = SystemClock.elapsedRealtimeNanos()

        // Số byte đã có (resume)
        var existing = if (destPartFile.exists()) destPartFile.length() else 0L
        if (existing > totalSize && totalSize > 0) {
            Log.w(TAG, "Phát hiện .part lớn hơn totalSize (existing=$existing, total=$totalSize) → xoá để copy lại")
            destPartFile.delete()
            existing = 0L
        }

        val resumed = existing > 0
        if (resumed) Log.i(TAG, "Resume copy từ offset=$existing bytes")

        var copied = existing
        var lastLogNs = startNs
        var lastLogBytes = copied

        assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
            // Skip tới vị trí resume
            if (existing > 0) {
                Log.d(TAG, "Skip trong InputStream: $existing bytes")
                skipFully(input, existing)
            }

            // Mở append stream
            FileOutputStream(destPartFile, /*append*/ existing > 0).use { fos ->
                val buf = ByteArray(chunkSize)
                progressCb(copied, totalSize.coerceAtLeast(1))

                while (true) {
                    if (cancelFlag.get()) {
                        Log.w(TAG, "Người dùng yêu cầu hủy trong khi copy")
                        break
                    }
                    val r = input.read(buf)
                    if (r <= 0) break
                    fos.write(buf, 0, r)
                    copied += r

                    // Báo UI nhẹ nhàng
                    if (copied % (512 * 1024) == 0L) {
                        progressCb(copied, totalSize.coerceAtLeast(1))
                    }

                    // LOG tốc độ mỗi ~1s để không spam
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    val elapsedSinceLastMs = (nowNs - lastLogNs) / 1_000_000
                    if (elapsedSinceLastMs >= 1000) {
                        val deltaBytes = copied - lastLogBytes
                        val instMBps = (deltaBytes / 1_048_576.0) / (elapsedSinceLastMs / 1000.0)
                        val elapsedTotalMs = (nowNs - startNs) / 1_000_000
                        val avgMBps = (copied - existing) / 1_048_576.0 / (elapsedTotalMs / 1000.0).coerceAtLeast(0.001)
                        Log.d(
                            TAG,
                            "Copy tiến độ: copied=$copied/${if (totalSize > 0) totalSize else -1} bytes, inst=%.2f MB/s, avg=%.2f MB/s, elapsed=%dms".format(
                                instMBps, avgMBps, elapsedTotalMs
                            )
                        )
                        lastLogNs = nowNs
                        lastLogBytes = copied
                    }
                }

                // flush + fsync
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                    Log.d(TAG, "fsync() không khả dụng trên thiết bị này")
                }

                // cập nhật cuối cho UI
                progressCb(copied, totalSize.coerceAtLeast(1))
            }
        }

        val durationMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000
        val avgMBps = (copied - existing) / 1_048_576.0 / (durationMs / 1000.0).coerceAtLeast(0.001)
        return CopyStats(
            bytesCopied = copied,
            durationMs = durationMs,
            avgMBps = avgMBps,
            resumed = resumed
        )
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remain = bytesToSkip
        while (remain > 0) {
            val skipped = input.skip(remain)
            if (skipped <= 0) {
                // InputStream skip có thể trả 0 → đọc và bỏ
                val one = input.read()
                if (one == -1) break
                remain -= 1
            } else {
                remain -= skipped
            }
        }
    }

    private fun atomicRename(from: File, to: File) {
        // API 26+: dùng Files.move để bảo toàn atomic trong cùng filesystem
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
        Log.d(TAG, "Atomic rename hoàn tất: ${from.name} → ${to.name}")
    }
}

/** Thống kê copy để log hiệu quả chuyển giao. */
private data class CopyStats(
    val bytesCopied: Long,
    val durationMs: Long,
    val avgMBps: Double,
    val resumed: Boolean
)
