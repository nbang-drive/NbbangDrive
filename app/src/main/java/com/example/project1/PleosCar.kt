package com.example.project1

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 진짜 차량 신호 (Pleos Vehicle SDK 2.3.2).
 * 에뮬레이터에서는 Virtual Console(http://127.0.0.1:8088)로 값을 바꿔서 테스트한다.
 */
class PleosCar(context: Context) : CarSource {
    private val tag = "NbbangPleosCar"
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())   // SDK 콜백은 다른 스레드 → 화면 값은 메인에서 바꾸기

    override var odometerKm by mutableDoubleStateOf(0.0)
    override var batteryPct by mutableDoubleStateOf(0.0)
    override var gear by mutableStateOf(Gear.P)
    override var carFuelKmPerL by mutableStateOf<Double?>(null)
    override val isRealCar = true

    // 좌석: 착좌 센서 / 안전벨트 둘 중 하나라도 true면 "사람 있음"
    private val occupancy = mutableStateMapOf<String, Boolean>()
    private val belt = mutableStateMapOf<String, Boolean>()
    override val seats: Map<String, Boolean>
        get() = Seats.TRACKED.associateWith { (occupancy[it] == true) || (belt[it] == true) }

    // 신호가 실제로 들어왔는지 (상태 막대 표시용)
    var odometerOk by mutableStateOf(false)
    var batteryOk by mutableStateOf(false)
    var gearOk by mutableStateOf(false)
    var seatOk by mutableStateOf(false)

    private fun ui(block: () -> Unit) = main.post(block)

    /**
     * 착좌 값 보정.
     * SDK 2.3.2는 차의 원래 값이 1일 때 true를 주는데, 안드로이드 차량 표준(VehicleSeatOccupancyState)은
     * 0 = 모름, 1 = 빈자리, 2 = 앉음 이다. 에뮬레이터 Virtual Console에서 확인한 결과
     * "Empty"를 누르면 true, "Occupied"를 누르면 false가 와서 뒤집어서 쓴다.
     */
    private val OCCUPANCY_INVERTED = true
    private fun occ(sdkValue: Boolean) = if (OCCUPANCY_INVERTED) !sdkValue else sdkValue

    // ---------------- 리스너 ----------------
    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value?.let { ui { odometerKm = it.toDouble(); odometerOk = true } }
        }
        override fun onFailed(e: Exception) { Log.w(tag, "distance failed", e) }
    }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            value?.let { ui { batteryPct = it.toDouble(); batteryOk = true } }
        }
        override fun onFailed(e: Exception) { Log.w(tag, "soc failed", e) }
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            gear?.let { g -> ui { this@PleosCar.gear = toAppGear(g); gearOk = true } }
        }
        override fun onFailed(e: Exception) { Log.w(tag, "gear failed", e) }
    }

    private val fuelListener = object : FuelEconomyListener {
        override fun onFuelEconomyUpdated(efficiency: Float?) {
            setFuel(efficiency)
        }
        override fun onFailed(e: Exception) { Log.w(tag, "fuel failed", e) }
    }

    private val occListeners = Seats.TRACKED.associateWith { seat ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                occupancy?.let { ui { this@PleosCar.occupancy[seat] = occ(it); seatOk = true } }
            }
            override fun onFailed(e: Exception) { Log.w(tag, "occupancy $seat failed", e) }
        }
    }

    private val beltListeners = Seats.TRACKED.associateWith { seat ->
        object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) {
                state?.let { ui { belt[seat] = it; seatOk = true } }
            }
            override fun onFailed(e: Exception) { Log.w(tag, "belt $seat failed", e) }
        }
    }

    /** P(주차)·N(중립)은 정차, 나머지(D, R, 1~9단)는 주행으로 본다 */
    private fun toAppGear(g: VehicleGear): Gear =
        if (g == VehicleGear.GEAR_PARK || g == VehicleGear.GEAR_NEUTRAL) Gear.P else Gear.D

    /** 차는 연비를 L/100km로 준다 → km/L로 바꾼다. 예) 8.0 L/100km → 100 ÷ 8.0 = 12.5 km/L */
    private fun setFuel(lPer100km: Float?) {
        val v = lPer100km ?: return
        if (v > 0f) ui { carFuelKmPerL = 100.0 / v }
    }

    private fun area(seat: String) = SeatArea(SeatValue.valueOf(seat))

    // ---------------- 시작 / 끝 ----------------
    fun start() {
        vehicle.initialize()   // 연결 전에 부른 요청은 SDK가 모아뒀다가 연결되면 보냄

        val odo = vehicle.getOdometer()
        odo.getCurrentDistanceDriven({ v -> v?.let { ui { odometerKm = it.toDouble(); odometerOk = true } } }, { Log.w(tag, "get distance", it) })
        odo.registerDistanceDriven(distanceListener)

        val ev = vehicle.getEvBattery()
        ev.getEvBatteryStateOfCharge({ v -> v?.let { ui { batteryPct = it.toDouble(); batteryOk = true } } }, { Log.w(tag, "get soc", it) })
        ev.registerEvBatteryStateOfCharge(socListener)

        val gearApi = vehicle.getGear()
        gearApi.getCurrentGearSelectionState({ g -> g?.let { ui { gear = toAppGear(it); gearOk = true } } }, { Log.w(tag, "get gear", it) })
        gearApi.registerGearSelection(gearListener)

        // 휘발유 차량용: 누적 연비. 에뮬레이터(전기차)에서는 지원 안 함 → null 유지 → 설정 연비 사용
        val fuel = vehicle.getFuel()
        fuel.getCumulativeFuelEconomy({ v -> setFuel(v) }, { Log.i(tag, "fuel economy not supported") })
        fuel.registerCumulativeFuelEconomy(fuelListener)

        val seatApi = vehicle.getSeat()
        for (s in Seats.TRACKED) {
            seatApi.getCurrentSeatOccupancy(area(s), { v -> v?.let { ui { occupancy[s] = occ(v); seatOk = true } } }, { Log.i(tag, "occupancy $s n/a") })
            seatApi.registerSeatOccupancy(area(s), occListeners.getValue(s))
            seatApi.getCurrentSeatBeltBuckledState(area(s), { v -> v?.let { ui { belt[s] = it; seatOk = true } } }, { Log.i(tag, "belt $s n/a") })
            seatApi.registerSeatBeltBuckledState(area(s), beltListeners.getValue(s))
        }
    }

    fun stop() {
        runCatching {
            vehicle.getOdometer().unregisterDistanceDriven(distanceListener)
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getFuel().unregisterCumulativeFuelEconomy(fuelListener)
            val seatApi = vehicle.getSeat()
            for (s in Seats.TRACKED) {
                seatApi.unregisterSeatOccupancy(area(s), occListeners.getValue(s))
                seatApi.unregisterSeatBeltBuckledState(area(s), beltListeners.getValue(s))
            }
        }
        vehicle.release()
    }

    /** 좌석별 원래 신호 (착좌 센서, 안전벨트). null = 아직 신호 없음 */
    fun seatRaw(seat: String): Pair<Boolean?, Boolean?> = occupancy[seat] to belt[seat]

    override fun signalSummary(): String {
        fun ok(b: Boolean) = if (b) "" else "(대기)"
        val fuel = carFuelKmPerL?.let { "연비 ${"%.1f".format(it)}km/L" } ?: "연비 미지원"
        val seated = seats.count { it.value }
        return "차량 신호 · 기어 ${gear.name}${ok(gearOk)} · 주행거리 ${"%,.1f".format(odometerKm)}km${ok(odometerOk)} · " +
                "배터리 ${"%.0f".format(batteryPct)}%${ok(batteryOk)} · $fuel · 착석 ${seated}명${ok(seatOk)}"
    }
}
