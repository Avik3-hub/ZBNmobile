package com.example.zbnreader

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object DocumentProcessor {
    enum class Filter { ORIGINAL, COLOR, BLACK_WHITE }
    data class Detection(val corners: Array<Point>, val edgesFound: Boolean)

    fun detectCorners(source: Bitmap): Detection {
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val scale = (1600.0 / max(rgba.cols(), rgba.rows())).coerceAtMost(1.0)
        val detection = Mat()
        Imgproc.resize(rgba, detection, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(detection, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 180.0)
        val kernel = Mat()
        Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 1)

        val edgeContours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, edgeContours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val imageArea = detection.cols() * detection.rows().toDouble()
        val whiteMask = Mat()
        Imgproc.threshold(gray, whiteMask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(whiteMask, whiteMask, Imgproc.MORPH_CLOSE, closeKernel)
        val whiteContours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(whiteMask, whiteContours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val corners = findDocument(whiteContours, imageArea, scale)
            ?: findDocument(edgeContours, imageArea, scale)

        val fallbackX = rgba.cols() * 0.08
        val fallbackY = rgba.rows() * 0.08
        val result = corners ?: arrayOf(
            Point(fallbackX, fallbackY), Point(rgba.cols() - fallbackX, fallbackY),
            Point(rgba.cols() - fallbackX, rgba.rows() - fallbackY),
            Point(fallbackX, rgba.rows() - fallbackY)
        )
        edgeContours.forEach { it.release() }
        whiteContours.forEach { it.release() }
        rgba.release(); detection.release(); gray.release(); edges.release()
        kernel.release(); hierarchy.release(); whiteMask.release(); closeKernel.release()
        return Detection(result, corners != null)
    }

    fun crop(source: Bitmap, corners: Array<Point>, filter: Filter): Bitmap {
        require(corners.size == 4)
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val ordered = corners
        val width = max(distance(ordered[0], ordered[1]), distance(ordered[3], ordered[2]))
            .toInt().coerceAtLeast(200)
        val height = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2]))
            .toInt().coerceAtLeast(200)
        val sourceCorners = MatOfPoint2f(*ordered)
        val targetCorners = MatOfPoint2f(
            Point(0.0, 0.0), Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0)
        )
        val transform = Imgproc.getPerspectiveTransform(sourceCorners, targetCorners)
        val warped = Mat()
        Imgproc.warpPerspective(rgba, warped, transform, Size(width.toDouble(), height.toDouble()))

        if (filter == Filter.ORIGINAL) {
            val output = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, output)
            rgba.release(); sourceCorners.release(); targetCorners.release()
            transform.release(); warped.release()
            return output
        }

        if (filter == Filter.BLACK_WHITE) {
            val gray = Mat()
            Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(
                gray, gray, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 31, 12.0
            )
            val output = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(gray, output)
            rgba.release(); sourceCorners.release(); targetCorners.release()
            transform.release(); warped.release(); gray.release()
            return output
        }

        val rgb = Mat()
        val lab = Mat()
        Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, lab)
        val enhancedRgb = Mat()
        val enhanced = Mat()
        Imgproc.cvtColor(lab, enhancedRgb, Imgproc.COLOR_Lab2RGB)
        Imgproc.cvtColor(enhancedRgb, enhanced, Imgproc.COLOR_RGB2RGBA)
        val output = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhanced, output)

        rgba.release(); sourceCorners.release(); targetCorners.release()
        transform.release(); warped.release(); rgb.release(); lab.release()
        enhancedRgb.release(); enhanced.release()
        channels.forEach { it.release() }
        clahe.collectGarbage()
        return output
    }

    private fun findDocument(
        contours: List<MatOfPoint>,
        imageArea: Double,
        scale: Double
    ): Array<Point>? {
        var best: Array<Point>? = null
        var bestScore = 0.0
        for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(40)) {
            val area = Imgproc.contourArea(contour)
            if (area < imageArea * 0.08) break
            if (area > imageArea * 0.94) continue
            val curve = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.035, true)
            val polygon = MatOfPoint(*approx.toArray())
            if (approx.total() == 4L && Imgproc.isContourConvex(polygon)) {
                val ordered = order(approx.toArray())
                val width = max(distance(ordered[0], ordered[1]), distance(ordered[3], ordered[2]))
                val height = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2]))
                val ratio = minOf(width, height) / max(width, height)
                if (ratio in 0.48..0.88) {
                    val score = area * (1.0 - kotlin.math.abs(ratio - 0.707) * 0.35)
                    if (score > bestScore) {
                        bestScore = score
                        best = ordered.map { Point(it.x / scale, it.y / scale) }.toTypedArray()
                    }
                }
            }
            polygon.release(); curve.release(); approx.release()
        }
        return best
    }

    private fun order(points: Array<Point>): Array<Point> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
}
