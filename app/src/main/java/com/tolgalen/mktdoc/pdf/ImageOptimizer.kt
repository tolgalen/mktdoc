package com.tolgalen.mktdoc.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

/** Shrinks images so the PDF weighs less (PNG/WebP → JPEG, maximum width, no transparency). */
object ImageOptimizer {
    fun dirName(q: ImageQuality) = when (q) { ImageQuality.BALANCED -> "img-bal"; ImageQuality.SMALL -> "img-small"; ImageQuality.ORIGINAL -> "img" }

    /** Returns original name -> optimized name (in `<outRoot>/<dirName>`). Untouched images are not listed. */
    fun optimize(assets: File, q: ImageQuality, outRoot: File = assets, isCancelled: () -> Boolean): Map<String, String> {
        if (q == ImageQuality.ORIGINAL) return emptyMap()
        val src = File(assets, "img")
        val dst = File(outRoot, dirName(q)).also { it.mkdirs() }
        val maxW = if (q == ImageQuality.BALANCED) 1400 else 1000
        val quality = if (q == ImageQuality.BALANCED) 82 else 65
        val map = HashMap<String, String>()
        for (f in src.listFiles() ?: emptyArray()) {
            if (isCancelled()) break
            val ext = f.extension.lowercase()
            if (ext !in setOf("png", "jpg", "jpeg", "webp")) continue
            val outName = f.nameWithoutExtension + ".jpg"
            val out = File(dst, outName)
            if (out.isFile && out.length() > 0) { map[f.name] = outName; continue }
            try {
                val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bo)
                if (bo.outWidth <= 0) continue
                var sample = 1
                while (bo.outWidth / (sample * 2) >= maxW) sample *= 2
                val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: continue
                var b = bmp
                if (b.width > maxW) {
                    b = Bitmap.createScaledBitmap(b, maxW, (b.height.toLong() * maxW / b.width).toInt().coerceAtLeast(1), true)
                    if (b !== bmp) bmp.recycle()
                }
                val flat = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
                b.recycle()
                FileOutputStream(out).use { flat.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                flat.recycle()
                // if it does not beat the original (an already small JPEG) the original is kept
                if (ext in setOf("jpg", "jpeg") && out.length() >= f.length()) { out.delete() } else map[f.name] = outName
            } catch (_: Throwable) { out.delete() }
        }
        return map
    }
}
