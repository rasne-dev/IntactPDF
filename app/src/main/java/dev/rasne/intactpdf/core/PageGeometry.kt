package dev.rasne.intactpdf.core

/**
 * Saf (Android/PDFBox bağımsız) sayfa geometrisi yardımcıları.
 *
 * PDFTextStripper'ın verdiği koordinatlar "görüntülenen" sayfa uzayındadır:
 * /Rotate uygulanmış, CropBox'a göre, sol-üst orijinli. Sayfaya içerik eklerken ise
 * PDF "kullanıcı uzayı" (döndürülmemiş, MediaBox/CropBox orijinli, sol-alt orijinli) kullanılır.
 * Bu sınıf iki uzay arasındaki dönüşümü tanımlar.
 */
object PageGeometry {

    /** /Rotate değerini 0, 90, 180 veya 270'e indirger (negatif ve 360+ değerleri de kabul eder). */
    fun normalizeRotation(raw: Int): Int {
        val r = ((raw % 360) + 360) % 360
        return (r / 90) * 90
    }

    /** Görüntülenen sayfanın (genişlik, yükseklik) değeri; 90/270 derecede eksenler yer değiştirir. */
    fun displaySize(cropWidth: Float, cropHeight: Float, rotation: Int): Pair<Float, Float> {
        return when (normalizeRotation(rotation)) {
            90, 270 -> Pair(cropHeight, cropWidth)
            else -> Pair(cropWidth, cropHeight)
        }
    }

    /**
     * "Görüntü uzayı" (sol-alt orijinli, y yukarı, döndürülmüş sayfa) -> kullanıcı uzayı dönüşümü.
     * PDF matris düzeni: [a b c d e f], x' = a*x + c*y + e, y' = b*x + d*y + f.
     */
    fun displayToUserMatrix(
        cropLeft: Float,
        cropBottom: Float,
        cropWidth: Float,
        cropHeight: Float,
        rotation: Int
    ): FloatArray {
        return when (normalizeRotation(rotation)) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, cropLeft + cropWidth, cropBottom)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, cropLeft + cropWidth, cropBottom + cropHeight)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, cropLeft, cropBottom + cropHeight)
            else -> floatArrayOf(1f, 0f, 0f, 1f, cropLeft, cropBottom)
        }
    }

    /** Kimlik dönüşümü mü? (Bu durumda içerik akışına ek matris yazmaya gerek yoktur.) */
    fun isIdentity(m: FloatArray): Boolean =
        m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 1f && m[4] == 0f && m[5] == 0f

    /** Bitmap için ölçek: hedef yoğunluğu, piksel bütçesini aşmayacak şekilde sınırlar. */
    fun cappedRenderScale(pageWidth: Int, pageHeight: Int, desired: Float, maxPixels: Long): Float {
        if (pageWidth <= 0 || pageHeight <= 0) return desired
        val pixels = pageWidth.toDouble() * pageHeight.toDouble() * desired.toDouble() * desired.toDouble()
        if (pixels <= maxPixels) return desired
        val capped = Math.sqrt(maxPixels.toDouble() / (pageWidth.toDouble() * pageHeight.toDouble())).toFloat()
        return capped.coerceAtLeast(0.1f)
    }
}
