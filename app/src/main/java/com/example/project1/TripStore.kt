package com.example.project1

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 앱을 다시 켰을 때 이어서 쓰려고 저장하는 것들 */
data class SavedState(
    val screen: Screen,
    val useCar: Boolean,
    val fakeOdometerKm: Double,
    val fakeBatteryPct: Double,
    val trip: TripSnapshot,
    val seatOnKm: Map<String, Double> = emptyMap(),   // 좌석에 사람이 앉은 지점
    val seatOffKm: Map<String, Double> = emptyMap(),  // 좌석이 빈 지점
    val seatManual: Set<String> = emptySet()          // 센서 없이 손으로 등록한 자리
)

/**
 * 여행 기록 저장소.
 * 앱이 꺼져도(시연 중 사고 포함) 진행 중인 여행이 남도록 SharedPreferences에 JSON으로 저장한다.
 */
class TripStore(context: Context) {
    private val prefs = context.getSharedPreferences("nbbang", Context.MODE_PRIVATE)

    fun save(state: SavedState) {
        runCatching { prefs.edit().putString(KEY, toJson(state).toString()).apply() }
    }

    fun load(): SavedState? = runCatching {
        prefs.getString(KEY, null)?.let { fromJson(JSONObject(it)) }
    }.getOrNull()

    // ---------------- JSON 변환 ----------------
    private fun toJson(s: SavedState) = JSONObject().apply {
        put("screen", s.screen.name)
        put("useCar", s.useCar)
        put("fakeOdo", s.fakeOdometerKm)
        put("fakeBat", s.fakeBatteryPct)
        put("seatOn", JSONObject(s.seatOnKm))
        put("seatOff", JSONObject(s.seatOffKm))
        put("seatManual", JSONArray(s.seatManual.toList()))
        val t = s.trip
        put("settings", JSONObject().apply {
            put("carName", t.settings.carName)
            put("fuelType", t.settings.fuelType.name)
            put("kmPerLiter", t.settings.kmPerLiter)
            put("fuelPrice", t.settings.fuelPricePerLiter)
            put("kmPerKwh", t.settings.kmPerKwh)
            put("battery", t.settings.batteryCapacityKwh)
            put("kwhPrice", t.settings.pricePerKwh)
            put("link", t.settings.transferLink)
        })
        put("driverPays", t.driverPays)
        put("fuelSource", t.fuelSource)
        put("riders", JSONArray().apply {
            t.riders.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("name", r.name); put("driver", r.isDriver)
                    put("seat", r.seat ?: "")
                })
            }
        })
        put("onboard", JSONArray(t.onboard.toList()))
        put("segments", JSONArray().apply {
            t.segments.forEach { g ->
                put(JSONObject().apply {
                    put("start", g.startKm); put("end", g.endKm)
                    put("riders", JSONArray(g.riderIds.toList())); put("cost", g.energyCost)
                    put("driver", g.driverId ?: -1)
                })
            }
        })
        put("expenses", JSONArray().apply {
            t.expenses.forEach { e ->
                put(JSONObject().apply {
                    put("type", e.type.name); put("amount", e.amount); put("at", e.atKm)
                    put("riders", JSONArray(e.riderIds.toList())); put("memo", e.memo)
                    put("driver", e.driverId ?: -1)
                })
            }
        })
        put("segStartKm", t.segStartKm)
        put("segStartBat", t.segStartBatteryPct)
        put("startKm", t.startKm)
        put("started", t.started)
        put("nextId", t.nextId)
        put("tripStartBat", t.tripStartBatteryPct)
        put("lastBat", t.lastBatteryPct)
        put("owner", t.ownerId ?: -1)
    }

    private fun kmMap(o: JSONObject?): Map<String, Double> =
        if (o == null) emptyMap() else o.keys().asSequence().associateWith { o.getDouble(it) }

    private fun ints(a: JSONArray): Set<Int> = (0 until a.length()).map { a.getInt(it) }.toSet()

    private fun fromJson(o: JSONObject): SavedState {
        val st = o.getJSONObject("settings")
        val settings = CarSettings(
            carName = st.getString("carName"),
            fuelType = FuelType.valueOf(st.getString("fuelType")),
            kmPerLiter = st.getDouble("kmPerLiter"),
            fuelPricePerLiter = st.getInt("fuelPrice"),
            kmPerKwh = st.optDouble("kmPerKwh", 5.0),
            batteryCapacityKwh = st.getDouble("battery"),
            pricePerKwh = st.getInt("kwhPrice"),
            transferLink = st.getString("link")
        )
        val ra = o.getJSONArray("riders")
        val riders = (0 until ra.length()).map {
            val r = ra.getJSONObject(it)
            Rider(r.getInt("id"), r.getString("name"), r.getBoolean("driver"), r.getString("seat").ifEmpty { null })
        }
        val sa = o.getJSONArray("segments")
        val segments = (0 until sa.length()).map {
            val g = sa.getJSONObject(it)
            Segment(g.getDouble("start"), g.getDouble("end"), ints(g.getJSONArray("riders")), g.getDouble("cost"),
                g.optInt("driver", -1).takeIf { it >= 0 })
        }
        val ea = o.getJSONArray("expenses")
        val expenses = (0 until ea.length()).map {
            val e = ea.getJSONObject(it)
            Expense(ExpenseType.valueOf(e.getString("type")), e.getInt("amount"), e.getDouble("at"),
                ints(e.getJSONArray("riders")), e.getString("memo"), e.optInt("driver", -1).takeIf { it >= 0 })
        }
        val trip = TripSnapshot(
            settings = settings,
            driverPays = o.getBoolean("driverPays"),
            fuelSource = o.getString("fuelSource"),
            riders = riders,
            onboard = ints(o.getJSONArray("onboard")),
            segments = segments,
            expenses = expenses,
            segStartKm = o.getDouble("segStartKm"),
            segStartBatteryPct = o.getDouble("segStartBat"),
            startKm = o.getDouble("startKm"),
            started = o.getBoolean("started"),
            nextId = o.getInt("nextId"),
            tripStartBatteryPct = o.optDouble("tripStartBat", 100.0),
            lastBatteryPct = o.optDouble("lastBat", 100.0),
            ownerId = o.optInt("owner", -1).takeIf { it >= 0 }
        )
        return SavedState(
            screen = Screen.valueOf(o.getString("screen")),
            useCar = o.getBoolean("useCar"),
            fakeOdometerKm = o.getDouble("fakeOdo"),
            fakeBatteryPct = o.getDouble("fakeBat"),
            trip = trip,
            seatOnKm = kmMap(o.optJSONObject("seatOn")),
            seatOffKm = kmMap(o.optJSONObject("seatOff")),
            seatManual = o.optJSONArray("seatManual")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        )
    }

    companion object { private const val KEY = "trip_v1" }
}
