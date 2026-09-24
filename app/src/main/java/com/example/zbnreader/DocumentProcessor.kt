package com.example.zbnreader

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object DocumentProcessor {
    data class Result(val bitmap: Bitmap, val edgesFound: Boolean)

    fun scan(source: Bitmap): Result {
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

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val minimumArea = detection.cols() * detection.rows() * 0.18
        var corners: Array<Point>? = null
        for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(12)) {
            if (Imgproc.contourArea(contour) < minimumArea) break
            val curve = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.02, true)
            val polygon = MatOfPoint(*approx.toArray())
            if (approx.total() == 4L && Imgproc.isContourConvex(polygon)) {
                corners = order(approx.toArray()).map { Point(it.x / scale, it.y / scale) }.toTypedArray()
                polygon.release()
                curve.release()
                approx.release()
                break
            }
            polygon.release()
            curve.release()
            approx.release()
        }

        val found = corners != null
        val ordered = corners ?: arrayOf(
            Point(0.0, 0.0), Point(rgba.cols() - 1.0, 0.0),
            Point(rgba.cols() - 1.0, rgba.rows() - 1.0), Point(0.0, rgba.rows() - 1.0)
        )
        val width = max(distance(ordered[0], ordered[1]), distance(ordered[3], ordered[2])).toInt().coerceAtLeast(1)
        val height = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2])).toInt().coerceAtLeast(1)
        val sourceCorners = MatOfPoint2f(*ordered)
        val targetCorners = MatOfPoint2f(
            Point(0.0, 0.0), Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0)
        )
        val transform = Imgproc.getPerspectiveTransform(sourceCorners, targetCorners)
        val warped = Mat()
        Imgproc.warpPerspective(rgba, warped, transform, Size(width.toDouble(), height.toDouble()))

        val scanned = Mat()
        Imgproc.cvtColor(warped, scanned, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.adaptiveThreshold(
            scanned, scanned, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, 31, 12.0
        )
        val output = Bitmap.createBitmap(scanned.cols(), scanned.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(scanned, output)

        contours.forEach { it.release() }
        rgba.release(); detection.release(); gray.release(); edges.release()
        kernel.release(); hierarchy.release(); sourceCorners.release(); targetCorners.release()
        transform.release(); warped.release(); scanned.release()
        return Result(output, found)
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
