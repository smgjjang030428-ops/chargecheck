package com.chargecheck.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.toArgb
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.model.distanceMetersTo
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.GeoPoint as OsmPoint

/** Rebuild only the station layer as the map moves. Count badges never imply a shared verdict. */
internal class StationPinLayer(private val map: MapView) : MapListener {
    val overlay = FolderOverlay()
    private var stations = emptyList<MapStation>()
    private var selectedId: String? = null
    private var select: (String?) -> Unit = {}
    private var showGroup: (List<MapStation>) -> Unit = {}
    private val redraw = Runnable { render() }

    fun update(stations: List<MapStation>, selectedId: String?, select: (String?) -> Unit, showGroup: (List<MapStation>) -> Unit) {
        this.stations = stations
        this.selectedId = selectedId
        this.select = select
        this.showGroup = showGroup
        schedule()
    }

    override fun onScroll(event: ScrollEvent?): Boolean { schedule(); return false }
    override fun onZoom(event: ZoomEvent?): Boolean { schedule(); return false }
    private fun schedule() { map.removeCallbacks(redraw); map.post(redraw) }
    fun dispose() { map.removeCallbacks(redraw); map.removeMapListener(this) }

    private fun render() {
        if (map.width == 0 || map.height == 0) return
        val density = map.resources.displayMetrics.density
        val radius = 56.0 * density
        val byId = stations.associateBy { it.id }
        val points = stations.mapNotNull { s ->
            val p = map.projection.toPixels(OsmPoint(s.location.lat, s.location.lon), null)
            if (p.x < -radius || p.x > map.width + radius || p.y < -radius || p.y > map.height + radius) null
            else MapPinPoint(s.id, p.x.toDouble(), p.y.toDouble(), s.highlight || s.routeTarget || s.id == selectedId)
        }
        overlay.items.clear()
        // Draw important individual pins last so a cluster cannot cover the selected destination.
        groupMapPins(points, radius).sortedBy { group -> group.any { it.emphasized } }.forEach { group ->
            val members = group.mapNotNull { byId[it.id] }
            val s = members.first()
            val multiple = members.size > 1
            val label = when {
                multiple -> null
                s.id == selectedId -> "선택 · ${s.name}"
                s.routeTarget -> "내비 · ${s.name}"
                s.highlight -> "대안 · ${s.name}"
                else -> null
            }
            overlay.add(Marker(map).apply {
                position = OsmPoint(members.map { it.location.lat }.average(), members.map { it.location.lon }.average())
                icon = pinIcon(if (multiple) Color.rgb(40, 43, 46) else s.verdict.color().toArgb(),
                    if (multiple) members.size.toString() else when (s.verdict) { Verdict.GREEN -> "✓"; Verdict.GRAY -> "?"; else -> "!" }, label)
                setAnchor(Marker.ANCHOR_CENTER, if (label == null) Marker.ANCHOR_CENTER else 0.28f)
                title = if (multiple) "충전소 ${members.size}곳 · 확대해서 보기" else "${s.name} · ${s.reason}"
                setOnMarkerClickListener { _, _ ->
                    if (!multiple) select(s.id)
                    else if (map.zoomLevelDouble >= 20.0 || members.all { it.location.distanceMetersTo(s.location) < 5 }) showGroup(members)
                    else { map.controller.setCenter(position); map.controller.setZoom((map.zoomLevelDouble + 2).coerceAtMost(20.0)) }
                    true
                }
            })
        }
        map.invalidate()
    }

    private fun pinIcon(color: Int, symbol: String, label: String?): BitmapDrawable {
        val density = map.resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 18 * density }
        val caption = label?.let { if (it.length > 18) it.take(17) + "…" else it }
        val diameter = if (caption == null) 44f * density else 52f * density
        val width = maxOf(diameter + 6 * density, caption?.let { paint.measureText(it) + 24 * density } ?: 0f)
        val height = diameter + if (caption == null) 6 * density else 38 * density
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2; val cy = diameter / 2 + 3 * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, diameter / 2, paint)
        paint.color = color
        canvas.drawCircle(cx, cy, diameter / 2 - 3 * density, paint)
        paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 23 * density
        canvas.drawText(symbol, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
        if (caption != null) {
            paint.color = Color.rgb(30, 32, 34)
            canvas.drawRoundRect(0f, diameter + 4 * density, width, height, 8 * density, 8 * density, paint)
            paint.color = Color.WHITE; paint.textSize = 18 * density
            canvas.drawText(caption, cx, height - 10 * density, paint)
        }
        return BitmapDrawable(map.resources, bitmap)
    }
}
