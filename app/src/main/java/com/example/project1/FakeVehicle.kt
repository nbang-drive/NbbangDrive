package com.example.project1

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 모의 차량 (SDK 없이 테스트용).
 * 아래 노란 막대 버튼으로 기어·주행거리·좌석을 바꾼다.
 */
class FakeVehicle : CarSource {
    override var odometerKm by mutableDoubleStateOf(12_345.0)
    override var batteryPct by mutableDoubleStateOf(80.0)
    override var gear by mutableStateOf(Gear.P)
    override val seats = mutableStateMapOf<String, Boolean>().apply {
        Seats.TRACKED.forEach { put(it, it == Seats.DRIVER) }   // 처음엔 운전석만 앉아 있음
    }
    override val carFuelKmPerL: Double? = null                  // 모의 차는 연비를 안 줌
    override val isRealCar = false

    /** 기어가 D일 때만 km만큼 달린 것으로 처리 */
    fun drive(km: Double) {
        if (gear != Gear.D) return
        odometerKm += km
        // 전기차 예시: 100km 달리면 배터리 18% 감소 (77.4kWh 기준)
        batteryPct = (batteryPct - km * 0.18).coerceAtLeast(0.0)
    }

    fun toggleSeat(seat: String) {
        seats[seat] = !(seats[seat] ?: false)
    }

    override fun signalSummary() =
        "모의 차량 · 주행거리 ${"%,.1f".format(odometerKm)}km · 배터리 ${"%.1f".format(batteryPct)}%"
}
