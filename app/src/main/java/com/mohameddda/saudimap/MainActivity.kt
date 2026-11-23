package com.mohameddda.saudimap

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.*
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Native Saudi-only map: vector map, offline regions, walk/car/bike routing. */
class MainActivity : Activity() {
    // Vector style. For production, host your own style/tiles (see README).
    private val styleUrl = "https://tiles.openfreemap.org/styles/liberty"
    private val ksa = LatLngBounds.from(32.3, 55.8, 15.6, 34.4)
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var routeSrc: GeoJsonSource? = null
    private lateinit var info: TextView
    private lateinit var search: EditText
    private val pts = ArrayList<LatLng>()
    private var mode = "foot"
    private val modeNames = mapOf("foot" to "مشي", "car" to "سيارة", "bike" to "دراجة")
    private lateinit var modeBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        MapLibre.getInstance(this)
        val root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        mapView = MapView(this)
        root.addView(mapView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // top search bar
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.background = card(); top.setPadding(16, 8, 16, 8)
        search = EditText(this); search.hint = "ابحث عن مدينة أو حي أو شارع"; search.isSingleLine = true
        top.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(btn("بحث") { doSearch() })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(24, 48, 24, 0) })

        // bottom panel
        val bottom = LinearLayout(this); bottom.orientation = LinearLayout.VERTICAL; bottom.background = card(); bottom.setPadding(24, 16, 24, 16)
        info = TextView(this); info.textSize = 15f; info.text = "اضغط مطولاً على الخريطة لتحديد البداية ثم الوجهة."
        bottom.addView(info)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        modeBtn = btn("الوسيلة: مشي") { cycleMode() }
        row.addView(btn("موقعي") { locate() }); row.addView(modeBtn)
        row.addView(btn("مسح") { clearAll() }); row.addView(btn("دون إنترنت") { offlineMenu() })
        bottom.addView(row)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 36) })
        setContentView(root)

        mapView.onCreate(b)
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromUri(styleUrl)) { st ->
                routeSrc = GeoJsonSource("route"); st.addSource(routeSrc!!)
                st.addLayer(LineLayer("route-line", "route").withProperties(
                    PropertyFactory.lineColor("#0b4a3a"), PropertyFactory.lineWidth(6f)))
            }
            m.setLatLngBoundsForCameraTarget(ksa)   // Saudi Arabia only
            m.setMinZoomPreference(4.5)
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(ksa, 20))
            m.addOnMapLongClickListener { ll -> onPoint(ll); true }
        }
    }

    private fun card() = GradientDrawable().apply { setColor(Color.parseColor("#F7F6F1")); cornerRadius = 36f }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
    private fun say(t: String) = runOnUiThread { info.text = t }

    // ---- points & routing ----
    private fun onPoint(ll: LatLng) {
        if (!ksa.contains(ll)) { say("هذا الموقع خارج حدود المملكة."); return }
        if (pts.size >= 2) clearAll()
        pts.add(ll)
        map?.addMarker(MarkerOptions().position(ll))
        if (pts.size == 2) route() else say("تم تحديد البداية. اضغط مطولاً لتحديد الوجهة.")
    }
    private fun clearAll() {
        pts.clear(); map?.clear(); routeSrc?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
        say("اضغط مطولاً على الخريطة لتحديد البداية ثم الوجهة.")
    }
    private fun cycleMode() {
        mode = when (mode) { "foot" -> "car"; "car" -> "bike"; else -> "foot" }
        modeBtn.text = "الوسيلة: " + modeNames[mode]
        if (pts.size == 2) route()
    }
    private fun route() {
        val a = pts[0]; val c = pts[1]; val md = mode
        say("جارٍ حساب المسار…")
        Thread {
            try {
                val u = "https://routing.openstreetmap.de/routed-$md/route/v1/driving/${a.longitude},${a.latitude};${c.longitude},${c.latitude}?overview=full&geometries=geojson"
                val r = JSONObject(get(u)).getJSONArray("routes").getJSONObject(0)
                val feat = """{"type":"Feature","properties":{},"geometry":${r.getJSONObject("geometry")}}"""
                val km = r.getDouble("distance") / 1000; val min = Math.round(r.getDouble("duration") / 60)
                runOnUiThread {
                    routeSrc?.setGeoJson(feat)
                    info.text = "المسافة %.1f كم · الزمن %d دقيقة (%s)".format(km, min, modeNames[md])
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().include(a).include(c).build(), 150))
                }
            } catch (e: Exception) {
                offlineRoute(a, c, md)
            }
        }.start()
    }

    // ---- search (online) ----
    private fun doSearch() {
        val q = search.text.toString().trim(); if (q.length < 2) return
        say("جارٍ البحث…")
        Thread {
            try {
                val u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=sa&accept-language=ar&q=" + URLEncoder.encode(q, "UTF-8")
                val arr = org.json.JSONArray(get(u))
                if (arr.length() == 0) { say("لا توجد نتائج."); return@Thread }
                val o = arr.getJSONObject(0); val ll = LatLng(o.getDouble("lat"), o.getDouble("lon"))
                runOnUiThread { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 14.0)); onPoint(ll) }
            } catch (e: Exception) { offlineSearch(q) }
        }.start()
    }
    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SaudiMapApp/1.0 (github.com/mohamedDDA)"); c.connectTimeout = 15000; c.readTimeout = 20000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    // ---- location ----
    private fun locate() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1); return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = listOf("gps", "network").mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.firstOrNull()
        if (l == null) { say("تعذّر تحديد موقعك. فعّل GPS وحاول مجدداً."); return }
        val ll = LatLng(l.latitude, l.longitude)
        if (!ksa.contains(ll)) { say("موقعك الحالي خارج حدود المملكة."); return }
        clearAll(); map?.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 15.0)); onPoint(ll)
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) locate()
    }

    // ---- offline regions (stored permanently inside the app) ----
    private val regions = listOf(
        Triple("المملكة كاملة (مستوى عام)", LatLngBounds.from(32.3, 55.8, 15.6, 34.4), 9.0),
        Triple("الرياض", LatLngBounds.from(25.1, 47.1, 24.4, 46.3), 15.0),
        Triple("جدة", LatLngBounds.from(21.8, 39.4, 21.2, 38.9), 15.0),
        Triple("مكة المكرمة", LatLngBounds.from(21.6, 40.0, 21.25, 39.6), 15.0),
        Triple("المدينة المنورة", LatLngBounds.from(24.65, 39.85, 24.3, 39.4), 15.0),
        Triple("الدمام والخبر", LatLngBounds.from(26.55, 50.3, 26.1, 49.9), 15.0)
    )
    private fun offlineMenu() {
        val names = regions.map { it.first }.toMutableList(); names.add("تنزيل بيانات البحث والاتجاهات دون إنترنت"); names.add("حذف كل الخرائط المحفوظة")
        AlertDialog.Builder(this).setTitle("تنزيل خريطة للاستخدام دون إنترنت")
            .setItems(names.toTypedArray()) { _, i -> when { i < regions.size -> download(regions[i]); i == regions.size -> downloadData(); else -> deleteAll() } }.show()
    }
    private fun download(r: Triple<String, LatLngBounds, Double>) {
        val om = OfflineManager.getInstance(this)
        om.setOfflineMapboxTileCountLimit(250000)
        val def = OfflineTilePyramidRegionDefinition(styleUrl, r.second, 4.0, r.third, resources.displayMetrics.density)
        om.createOfflineRegion(def, r.first.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(s: OfflineRegionStatus) {
                        val p = if (s.requiredResourceCount > 0) 100 * s.completedResourceCount / s.requiredResourceCount else 0
                        say(if (s.isComplete) "اكتمل تنزيل: ${r.first}" else "تنزيل ${r.first}: $p%")
                    }
                    override fun onError(e: OfflineRegionError) { say("خطأ في التنزيل: ${e.message}") }
                    override fun mapboxTileCountLimitExceeded(limit: Long) { say("المنطقة كبيرة جداً.") }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) { say("تعذّر بدء التنزيل: $error") }
        })
    }
    private fun deleteAll() {
        OfflineManager.getInstance(this).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(list: Array<OfflineRegion>?) {
                list?.forEach { it.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { say("تم حذف الخرائط المحفوظة.") }
                    override fun onError(error: String) { say("تعذّر الحذف: $error") }
                }) }
            }
            override fun onError(error: String) { say("تعذّر قراءة الخرائط المحفوظة.") }
        })
    }

    // ---- offline data: routing graph + place search (built by tools/build_data.py, see README) ----
    // Change this to YOUR repo: Releases > "data" tag created by the "Build offline data" workflow.
    private val dataBase = "https://github.com/mohamedDDA/saudi-map-android/releases/download/data/"
    private var graph: Graph? = null

    private fun downloadData() {
        Thread {
            try {
                for (nm in listOf("places.db", "ksa.graph")) {
                    val c = URL(dataBase + nm).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 30000
                    val tmp = File(filesDir, "$nm.part"); var done = 0L; var lastMb = -1L
                    c.inputStream.use { i -> tmp.outputStream().use { o ->
                        val buf = ByteArray(65536); var r = i.read(buf)
                        while (r > 0) { o.write(buf, 0, r); done += r; val mb = done / 1048576
                            if (mb != lastMb && mb % 2 == 0L) { lastMb = mb; say("تنزيل $nm: $mb م.ب") }; r = i.read(buf) } } }
                    File(filesDir, nm).delete(); tmp.renameTo(File(filesDir, nm))
                }
                graph = null; say("اكتمل تنزيل بيانات البحث والاتجاهات. تعمل الآن دون إنترنت.")
            } catch (e: Exception) { say("تعذّر التنزيل: ${e.message}") }
        }.start()
    }

    private fun offlineRoute(a: LatLng, c: LatLng, md: String) {
        val gf = File(filesDir, "ksa.graph")
        if (!gf.exists()) { say("لا اتصال: المسافة المباشرة %.1f كم. نزّل بيانات الاتجاهات من زر «دون إنترنت».".format(a.distanceTo(c) / 1000)); return }
        try {
            if (graph == null) graph = Graph(gf)
            val r = graph!!.route(a, c, md == "car")
            if (r == null) { say("لا يوجد مسار بين النقطتين في البيانات المحفوظة."); return }
            val coords = r.first.joinToString(",") { "[${it.longitude},${it.latitude}]" }
            val feat = """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
            runOnUiThread {
                routeSrc?.setGeoJson(feat)
                info.text = "دون إنترنت: %.1f كم · %d دقيقة (%s)".format(r.second / 1000, Math.round(r.third / 60), modeNames[md])
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().include(a).include(c).build(), 150))
            }
        } catch (e: Exception) { say("تعذّر استخدام بيانات الاتجاهات: ${e.message}") }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

    private fun offlineSearch(q: String) {
        val f = File(filesDir, "places.db")
        if (!f.exists()) { say("تعذّر البحث. نزّل بيانات البحث دون إنترنت من زر «دون إنترنت»."); return }
        val res = ArrayList<Triple<String, String, LatLng>>()
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        db.rawQuery("SELECT name,kind,lat,lon FROM places WHERE norm LIKE ? ORDER BY CASE kind WHEN 'place' THEN 0 WHEN 'poi' THEN 1 ELSE 2 END, length(name) LIMIT 8",
            arrayOf("%" + norm(q) + "%")).use { c -> while (c.moveToNext()) res.add(Triple(c.getString(0), c.getString(1), LatLng(c.getDouble(2), c.getDouble(3)))) }
        db.close()
        if (res.isEmpty()) { say("لا توجد نتائج في البيانات المحفوظة."); return }
        val kinds = mapOf("place" to "منطقة", "poi" to "معلم", "road" to "شارع")
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("نتائج دون إنترنت")
                .setItems(res.map { it.first + " (" + kinds[it.second] + ")" }.toTypedArray()) { _, i ->
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(res[i].third, 15.0)); onPoint(res[i].third) }.show()
        }
        say("نتائج البحث دون إنترنت")
    }

    // ---- MapView lifecycle ----
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); mapView.onSaveInstanceState(o) }
}

/** Memory-mapped road graph (format written by tools/build_data.py). A* routing, no network needed. */
class Graph(f: File) {
    private val n: Int
    private val lat: IntBuffer; private val lon: IntBuffer; private val off: IntBuffer
    private val to: IntBuffer; private val len: IntBuffer; private val spd: IntBuffer
    init {
        val mb = RandomAccessFile(f, "r").channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
        require(mb.getInt(0) == 0x4B534147) { "ملف بيانات غير صالح" }
        n = mb.getInt(4); val m = mb.getInt(8); var p = 12
        fun sec(c: Int): IntBuffer { val d = mb.duplicate(); d.position(p); val sl = d.slice(); sl.limit(c * 4); p += c * 4; return sl.asIntBuffer() }
        lat = sec(n); lon = sec(n); off = sec(n + 1); to = sec(m); len = sec(m); spd = sec(m)
    }
    private fun nearest(p: LatLng): Int {
        val c = Math.cos(Math.toRadians(p.latitude)); var best = -1; var bd = Double.MAX_VALUE
        for (i in 0 until n) {
            val dy = lat.get(i) / 1e6 - p.latitude; val dx = (lon.get(i) / 1e6 - p.longitude) * c
            val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i }
        }
        return best
    }
    private fun key(f: Float, i: Int) = (java.lang.Float.floatToIntBits(f).toLong() shl 32) or i.toLong()
    /** returns (points, meters, seconds) or null */
    fun route(a: LatLng, b: LatLng, car: Boolean): Triple<List<LatLng>, Double, Double>? {
        val s = nearest(a); val t = nearest(b)
        val g = FloatArray(n) { Float.MAX_VALUE }; val prev = IntArray(n) { -1 }
        val tla = lat.get(t) / 1e6; val tlo = lon.get(t) / 1e6; val ct = Math.cos(Math.toRadians(tla))
        val vmax = if (car) 120 / 3.6 else 1.4
        fun h(i: Int): Float { val dy = (lat.get(i) / 1e6 - tla) * 110540; val dx = (lon.get(i) / 1e6 - tlo) * 111320 * ct; return (Math.sqrt(dx * dx + dy * dy) / vmax).toFloat() }
        val pq = PriorityQueue<Long>(); g[s] = 0f; pq.add(key(h(s), s))
        while (pq.isNotEmpty()) {
            val k = pq.poll()!!; val u = (k and 0xffffffffL).toInt(); val fv = Float.fromBits((k shr 32).toInt())
            if (u == t) break
            if (fv > g[u] + h(u) + 0.01f) continue
            for (e in off.get(u) until off.get(u + 1)) {
                val sp = spd.get(e); val v = to.get(e)
                if (car) { if (sp and 256 != 0) continue } else { if ((sp and 255) >= 90) continue }   // walking: no motorways
                val speed = if (car) (sp and 255) / 3.6 else 1.4
                val nc = g[u] + (len.get(e) / speed).toFloat()
                if (nc < g[v]) { g[v] = nc; prev[v] = u; pq.add(key(nc + h(v), v)) }
            }
        }
        if (g[t] == Float.MAX_VALUE) return null
        val path = ArrayList<LatLng>(); var v = t
        while (v != -1) { path.add(LatLng(lat.get(v) / 1e6, lon.get(v) / 1e6)); v = prev[v] }
        path.reverse(); path.add(0, a); path.add(b)
        var d = 0.0; for (i in 1 until path.size) d += path[i - 1].distanceTo(path[i])
        return Triple(path, d, g[t].toDouble())
    }
}
