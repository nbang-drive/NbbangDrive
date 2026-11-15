package com.example.project1

/** 기어 상태 (앱에서는 정차 P / 주행 D 두 가지로만 구분) */
enum class Gear { P, D }

/**
 * 차량 정보를 주는 곳.
 * - PleosCar: 진짜 Pleos Vehicle SDK (에뮬레이터 Virtual Console / 실제 차)
 * - FakeVehicle: SDK 없이 테스트하는 모의 차량
 */
interface CarSource {
    val odometerKm: Double              // 누적 주행거리 (km)
    val batteryPct: Double              // 배터리 잔량 (%)
    val gear: Gear                      // 기어 P/D
    val seats: Map<String, Boolean>     // 좌석 이름 → 사람이 앉아 있는지
    val carFuelKmPerL: Double?          // 차가 알려주는 연비 (km/L). 지원 안 하면 null
    val isRealCar: Boolean              // 진짜 차량 신호인지
    fun signalSummary(): String         // 아래 상태 막대에 보여줄 한 줄
}

/** 좌석 이름과 한글 표시 */
object Seats {
    const val DRIVER = "ROW_1_LEFT"
    val TRACKED = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")

    fun label(seat: String?): String = when (seat) {
        "ROW_1_LEFT" -> "운전석"
        "ROW_1_RIGHT" -> "조수석"
        "ROW_2_LEFT" -> "뒷좌석 왼쪽"
        "ROW_2_CENTER" -> "뒷좌석 가운데"
        "ROW_2_RIGHT" -> "뒷좌석 오른쪽"
        null -> "자리 미지정"
        else -> seat
    }

    fun short(seat: String): String = when (seat) {
        "ROW_1_LEFT" -> "운전"
        "ROW_1_RIGHT" -> "조수"
        "ROW_2_LEFT" -> "뒤L"
        "ROW_2_CENTER" -> "뒤C"
        "ROW_2_RIGHT" -> "뒤R"
        else -> seat
    }
}
