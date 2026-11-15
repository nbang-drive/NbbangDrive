package com.example.project1

import kotlin.math.roundToLong

/** 연료 종류: 휘발유(연비로 계산) / 전기차(전비로 계산, 배터리 실측은 참고) */
enum class FuelType { GASOLINE, EV }

/** 차량·요금 설정 (설정 화면에서 바꿀 수 있는 값들) */
data class CarSettings(
    val carName: String = "그랜저 2.5 가솔린 (예시)",
    val fuelType: FuelType = FuelType.GASOLINE,
    val kmPerLiter: Double = 12.0,          // 연비 (km/L) - 차종 기본값, 사용자가 보정
    val fuelPricePerLiter: Int = 1700,      // 유가 (원/L) - 지금은 고정값, 나중에 오피넷
    val kmPerKwh: Double = 5.0,             // 전비 (km/kWh) - 전기차 계산 기준
    val batteryCapacityKwh: Double = 77.4,  // 전기차 배터리 용량 (kWh) - 실측 참고용
    val pricePerKwh: Int = 300,             // 충전 단가 (원/kWh)
    val transferLink: String = ""           // 운전자 송금 링크 (선택)
)

/** 탑승자 한 명 */
data class Rider(val id: Int, val name: String, val isDriver: Boolean = false, val seat: String? = null)

/** 비용 종류 */
enum class ExpenseType(val label: String) { TOLL("톨비"), PARKING("주차비"), ETC("기타") }

/** 구간: [startKm ~ endKm] 동안 riderIds 사람들이 타고 있었음, 그 구간 에너지 비용, 그때 운전한 사람 */
data class Segment(
    val startKm: Double,
    val endKm: Double,
    val riderIds: Set<Int>,
    val energyCost: Double,
    val driverId: Int? = null
) {
    val distanceKm: Double get() = endKm - startKm
}

/** 톨비·주차비 등 추가 비용: 입력한 순간 타고 있던 사람끼리 나눔 */
data class Expense(
    val type: ExpenseType,
    val amount: Int,
    val atKm: Double,
    val riderIds: Set<Int>,
    val memo: String = "",
    val driverId: Int? = null     // 낸 순간 운전하던 사람
)

/** 한 사람의 최종 정산 결과 */
data class PersonResult(
    val rider: Rider,
    val energyShare: Double,     // 기름값/충전비 몫 (원, 반올림 전)
    val expenseShare: Double,    // 톨비·주차비·기타 몫 (원, 반올림 전)
    val rideKm: Double,          // 탄 거리
    val total: Long,             // 최종 금액 (10원 단위, 차액 반영 후)
    val adjust: Long = 0,        // 반올림 차액을 이 사람 몫에서 맞춘 금액 (+10, -10 …)
    val driveKm: Double = 0.0    // 직접 운전한 거리
)

/** 전체 정산 결과 */
data class Settlement(
    val people: List<PersonResult>,
    val totalEnergyCost: Double,     // 나눈 기름값/충전비 (운전자 혼자 구간 제외)
    val totalExpense: Int,           // 나눈 톨비·주차비 등
    val totalKm: Double,
    val ownerId: Int? = null,        // 차량 소유자 (송금 받는 사람)
    val soloKm: Double = 0.0,        // 운전자 혼자 달린 거리 (정산 제외)
    val soloCost: Double = 0.0,      // 그 구간 비용 (정산 제외)
    val driverChanged: Boolean = false
) {
    val totalCost: Double get() = totalEnergyCost + totalExpense
    val roundedSum: Long get() = people.sumOf { it.total }
    /** 차액을 맞춘 사람 (없으면 null) */
    val adjusted: PersonResult? get() = people.firstOrNull { it.adjust != 0L }
    val owner: PersonResult? get() = people.firstOrNull { it.rider.id == ownerId }
    /** 차량 소유자가 받을 돈 = 다른 사람들이 낼 돈의 합 (소유자 본인 몫은 차감된 셈) */
    val ownerReceives: Long get() = people.filter { it.rider.id != ownerId }.sumOf { it.total }
}

/** 저장·복원용: 엔진 상태를 그대로 담는 그릇 */
data class TripSnapshot(
    val settings: CarSettings,
    val driverPays: Boolean,
    val fuelSource: String,
    val riders: List<Rider>,
    val onboard: Set<Int>,
    val segments: List<Segment>,
    val expenses: List<Expense>,
    val segStartKm: Double,
    val segStartBatteryPct: Double,
    val startKm: Double,
    val started: Boolean,
    val nextId: Int,
    val tripStartBatteryPct: Double = 100.0,
    val lastBatteryPct: Double = 100.0,
    val ownerId: Int? = null
)

/**
 * 구간별 정산 계산기.
 * - 사람이 타거나 내릴 때마다 "그 순간의 주행거리"로 구간을 끊는다.
 * - 각 구간 비용은 그 구간에 타고 있던 사람(돈 내는 사람)끼리 나눈다.
 */
class TripEngine(var settings: CarSettings = CarSettings()) {

    var driverPays: Boolean = false         // 운전자도 돈을 내는지 (도착할 때 확인, 기본은 운전자 제외)
    var ownerId: Int? = null                // 차량 소유자 = 출발할 때 운전자 (운전자가 바뀌어도 송금은 이 사람이 받음)
        private set
    var fuelSource: String = "설정값"        // 연비/배터리 값이 어디서 왔는지 (정산 화면 표시용)

    private val _riders = mutableListOf<Rider>()
    val riders: List<Rider> get() = _riders

    private val onboard = mutableSetOf<Int>()
    val onboardIds: Set<Int> get() = onboard

    private val _segments = mutableListOf<Segment>()
    val segments: List<Segment> get() = _segments

    private val _expenses = mutableListOf<Expense>()
    val expenses: List<Expense> get() = _expenses

    // 현재 열려 있는 구간의 시작 지점
    private var segStartKm: Double = 0.0
    private var segStartBatteryPct: Double = 100.0

    var startKm: Double = 0.0
        private set
    var started: Boolean = false
        private set

    private var nextId = 1

    /** 출발 전: 탑승자 등록 */
    fun addRider(name: String, isDriver: Boolean = false, seat: String? = null): Rider {
        val r = Rider(nextId++, name.trim(), isDriver, seat)
        _riders.add(r)
        onboard.add(r.id)
        return r
    }

    /** 출발 전: 운전자 지정 (한 명만) */
    fun setDriver(id: Int) {
        check(!started) { "출발 후에는 changeDriver를 쓰세요" }
        for (i in _riders.indices) _riders[i] = _riders[i].copy(isDriver = _riders[i].id == id)
    }

    /** 지금 운전하는 사람 */
    val currentDriverId: Int? get() = _riders.firstOrNull { it.isDriver }?.id

    /**
     * 운전자 교대 (휴게소 등 정차 중).
     * 지금 구간을 이 지점에서 닫고, 다음 구간부터 새 운전자로 기록한다. 차량 소유자(송금 받는 사람)는 그대로.
     */
    fun changeDriver(newId: Int, odometerKm: Double, batteryPct: Double = 100.0) {
        check(started) { "출발 전에는 setDriver를 쓰세요" }
        require(newId in onboard) { "타고 있는 사람만 운전자가 될 수 있어요" }
        if (newId == currentDriverId) return
        closeSegment(maxOf(odometerKm, segStartKm), batteryPct)
        for (i in _riders.indices) _riders[i] = _riders[i].copy(isDriver = _riders[i].id == newId)
        driverChanged = true
    }

    /** 운전자가 한 번이라도 바뀌었는지 (정산 화면 안내용) */
    var driverChanged: Boolean = false
        private set

    /** km 지점에 운전하던 사람 */
    fun driverAt(km: Double): Int? {
        val seg = _segments.firstOrNull { km >= it.startKm && km < it.endKm }
            ?: _segments.firstOrNull()?.takeIf { km < it.startKm }
        return seg?.driverId ?: currentDriverId
    }

    /** 좌석 연결/해제 (좌석 감지 기능용, 계산에는 영향 없음) */
    fun assignSeat(id: Int, seat: String?) {
        for (i in _riders.indices) if (_riders[i].id == id) _riders[i] = _riders[i].copy(seat = seat)
    }

    /** 이 좌석에 연결된, 지금 타고 있는 사람 */
    fun onboardRiderAt(seat: String): Rider? = _riders.firstOrNull { it.seat == seat && it.id in onboard }

    /** 이름 바꾸기 (자동 출발 때 붙은 "손님1" 등을 나중에 진짜 이름으로) */
    fun renameRider(id: Int, name: String) {
        if (name.isBlank()) return
        for (i in _riders.indices) if (_riders[i].id == id) _riders[i] = _riders[i].copy(name = name.trim())
    }

    fun removeRiderBeforeStart(id: Int) {
        check(!started) { "출발 후에는 하차 기능을 쓰세요" }
        _riders.removeAll { it.id == id }
        onboard.remove(id)
    }

    /** 출발: 주행거리(Odometer)와 배터리 %를 기록하고 첫 구간을 연다 */
    fun start(odometerKm: Double, batteryPct: Double = 100.0) {
        started = true
        if (ownerId == null) ownerId = currentDriverId
        startKm = odometerKm
        segStartKm = odometerKm
        segStartBatteryPct = batteryPct
        tripStartBatteryPct = batteryPct
        lastBatteryPct = batteryPct
    }

    /** 배터리 실측 (전기차 참고용): 출발 때 % → 마지막으로 구간을 닫을 때 % */
    var tripStartBatteryPct: Double = 100.0
        private set
    var lastBatteryPct: Double = 100.0
        private set
    val batteryUsedPct: Double get() = (tripStartBatteryPct - lastBatteryPct).coerceAtLeast(0.0)
    val batteryUsedKwh: Double get() = batteryUsedPct / 100.0 * settings.batteryCapacityKwh

    /** 지금 구간을 닫는다 (탑승·하차·도착 직전에 호출) */
    private fun closeSegment(odometerKm: Double, batteryPct: Double) {
        // 지금 구간 시작보다 앞선 지점은 쓸 수 없음 (주행거리가 거꾸로 가면 구간 시작으로 맞춤)
        val endKm = maxOf(odometerKm, segStartKm)
        val dist = endKm - segStartKm
        if (dist > 0.0 && onboard.isNotEmpty()) {
            val cost = energyCost(dist, segStartBatteryPct - batteryPct)
            _segments.add(Segment(segStartKm, endKm, onboard.toSet(), cost, currentDriverId))
        }
        segStartKm = endKm
        segStartBatteryPct = batteryPct
        lastBatteryPct = batteryPct
    }

    /** 구간 에너지 비용 계산 */
    fun energyCost(distanceKm: Double, batteryDropPct: Double): Double = when (settings.fuelType) {
        // 휘발유: 거리 ÷ 연비 × 유가
        FuelType.GASOLINE -> distanceKm / settings.kmPerLiter * settings.fuelPricePerLiter
        // 전기차: 거리 ÷ 전비 × 충전 단가
        // (배터리 %는 1% 단위라 짧은 구간이 0원이 되기 쉬워서 계산에는 쓰지 않고 정산 화면에 참고로만 보여줌)
        FuelType.EV -> distanceKm / settings.kmPerKwh * settings.pricePerKwh
    }

    /**
     * 중간 탑승.
     * odometerKm = 좌석 센서가 사람을 감지한 지점. 이름은 나중에(정차 후) 넣어도 그 지점부터 계산한다.
     * 감지 지점 뒤에 넣은 톨비 등에도 이 사람을 포함시킨다.
     */
    fun board(name: String, odometerKm: Double, batteryPct: Double = 100.0, seat: String? = null): Rider {
        val at = maxOf(odometerKm, startKm)
        val r = Rider(nextId++, name.trim(), false, seat)
        _riders.add(r)
        if (at >= segStartKm) {
            closeSegment(at, batteryPct)
        } else {
            // 확인이 늦어서 이미 닫힌 구간까지 거슬러 올라감 → 그 지점에서 구간을 쪼개고 이후 구간에 추가
            splitAt(at)
            for (i in _segments.indices) if (_segments[i].startKm >= at)
                _segments[i] = _segments[i].copy(riderIds = _segments[i].riderIds + r.id)
        }
        onboard.add(r.id)
        for (i in _expenses.indices) {
            val e = _expenses[i]
            if (e.atKm > at) _expenses[i] = e.copy(riderIds = e.riderIds + r.id)
        }
        return r
    }

    /**
     * 중간 하차.
     * odometerKm = 좌석이 빈 지점. 확인은 나중에 해도 그 지점부터 계산에서 빠진다.
     * 그 뒤에 넣은 톨비 등에서도 뺀다.
     */
    fun alight(riderId: Int, odometerKm: Double, batteryPct: Double = 100.0) {
        val at = maxOf(odometerKm, startKm)
        if (at >= segStartKm) {
            closeSegment(at, batteryPct)
        } else {
            // 이미 닫힌 구간까지 거슬러 올라감 → 그 지점 이후 구간에서 뺀다
            splitAt(at)
            for (i in _segments.indices) if (_segments[i].startKm >= at)
                _segments[i] = _segments[i].copy(riderIds = _segments[i].riderIds - riderId)
        }
        onboard.remove(riderId)
        for (i in _expenses.indices) {
            val e = _expenses[i]
            if (e.atKm > at && riderId in e.riderIds) _expenses[i] = e.copy(riderIds = e.riderIds - riderId)
        }
    }

    /** 톨비·주차비·기타: 지금 타고 있는 사람끼리 */
    fun addExpense(type: ExpenseType, amount: Int, odometerKm: Double, memo: String = "") {
        if (amount <= 0) return
        _expenses.add(Expense(type, amount, odometerKm, onboard.toSet(), memo, currentDriverId))
    }

    /**
     * 도착 후(또는 나중에) 빠뜨린 비용 넣기: 나눌 사람을 직접 정함.
     * atKm = 낸 지점(주행거리계 기준), riderIds = 나눠 낼 사람
     */
    fun addExpenseFor(type: ExpenseType, amount: Int, atKm: Double, riderIds: Set<Int>, memo: String = "") {
        if (amount <= 0 || riderIds.isEmpty()) return
        _expenses.add(Expense(type, amount, atKm, riderIds, memo, driverAt(atKm)))
    }

    /** km 지점(주행거리계 기준)에 타고 있던 사람. 기록 밖이면 가장 가까운 구간 */
    fun ridersAt(km: Double): Set<Int> {
        if (_segments.isEmpty()) return onboard.toSet().ifEmpty { _riders.map { it.id }.toSet() }
        val seg = _segments.firstOrNull { km >= it.startKm && km < it.endKm }
            ?: if (km < _segments.first().startKm) _segments.first() else _segments.last()
        return seg.riderIds
    }

    /** 닫힌 구간 하나를 km 지점에서 둘로 쪼갬 (비용은 거리 비율로 나눔) */
    private fun splitAt(km: Double) {
        val i = _segments.indexOfFirst { it.startKm < km && km < it.endKm }
        if (i < 0) return
        val s = _segments[i]
        val ratio = (km - s.startKm) / (s.endKm - s.startKm)
        _segments[i] = s.copy(endKm = km, energyCost = s.energyCost * ratio)
        _segments.add(i + 1, s.copy(startKm = km, energyCost = s.energyCost * (1 - ratio)))
    }

    /** 마지막으로 넣은 톨비 등 취소 (주행 중 잘못 누른 경우) */
    fun removeLastExpense(): Expense? = _expenses.removeLastOrNull()

    /** 도착: 마지막 구간을 닫는다 */
    fun finish(odometerKm: Double, batteryPct: Double = 100.0) {
        closeSegment(odometerKm, batteryPct)
    }

    /** 지금까지 비용 (주행 중 화면용, 아직 안 닫힌 구간도 포함해서 미리보기) */
    fun previewSettlement(odometerKm: Double, batteryPct: Double = 100.0): Settlement {
        val openDist = odometerKm - segStartKm
        val extra = if (openDist > 0.0 && onboard.isNotEmpty())
            listOf(Segment(segStartKm, odometerKm, onboard.toSet(),
                energyCost(openDist, segStartBatteryPct - batteryPct), currentDriverId))
        else emptyList()
        return settle(_segments + extra, odometerKm)
    }

    /** 최종 정산 */
    fun settlement(): Settlement = settle(_segments, _segments.lastOrNull()?.endKm ?: startKm)

    /**
     * 돈 내는 사람 고르기 (구간·비용마다 그때 운전한 사람 기준).
     * - 운전자 말고 아무도 없으면(운전자 혼자 달린 구간) → 나눌 사람이 없으니 정산에서 뺀다 (빈 집합)
     * - 운전자 제외면 그 구간 운전자를 뺀다, 포함이면 다 같이
     */
    fun payers(ids: Set<Int>, driverId: Int?): Set<Int> {
        val others = if (driverId == null) ids else ids - driverId
        if (others.isEmpty()) return emptySet()
        return if (driverPays) ids else others
    }

    private fun settle(segs: List<Segment>, endKm: Double): Settlement {
        val energy = mutableMapOf<Int, Double>()
        val exp = mutableMapOf<Int, Double>()
        val km = mutableMapOf<Int, Double>()

        val drive = mutableMapOf<Int, Double>()
        var sharedEnergy = 0.0
        var soloKm = 0.0
        var soloCost = 0.0
        var sharedExpense = 0

        for (s in segs) {
            for (id in s.riderIds) km[id] = (km[id] ?: 0.0) + s.distanceKm
            val d = s.driverId ?: currentDriverId
            if (d != null && d in s.riderIds) drive[d] = (drive[d] ?: 0.0) + s.distanceKm
            if (s.riderIds.isEmpty()) continue
            val p = payers(s.riderIds, d)
            if (p.isEmpty()) { soloKm += s.distanceKm; soloCost += s.energyCost; continue }   // 운전자 혼자 → 제외
            sharedEnergy += s.energyCost
            val share = s.energyCost / p.size
            for (id in p) energy[id] = (energy[id] ?: 0.0) + share
        }
        for (e in _expenses) {
            val p = payers(e.riderIds, e.driverId ?: currentDriverId)
            if (p.isEmpty()) continue
            sharedExpense += e.amount
            val share = e.amount.toDouble() / p.size
            for (id in p) exp[id] = (exp[id] ?: 0.0) + share
        }

        var people = _riders.map { r ->
            val en = energy[r.id] ?: 0.0
            val ex = exp[r.id] ?: 0.0
            PersonResult(r, en, ex, km[r.id] ?: 0.0, roundTo10(en + ex), driveKm = drive[r.id] ?: 0.0)
        }

        // 반올림 차액 맞추기: 다 더한 금액이 "실제 비용을 10원 단위로 반올림한 값"과 같아지도록
        // 차액은 차량 소유자(돈 받는 사람) 몫에서 조정 (소유자가 0원이면 가장 많이 내는 사람)
        val target = roundTo10(sharedEnergy + sharedExpense)
        val diff = target - people.sumOf { it.total }
        if (diff != 0L) {
            val owner = people.firstOrNull { it.rider.id == ownerId && it.total > 0 }
            val who = owner ?: people.filter { it.total > 0 }.maxByOrNull { it.total }
            if (who != null) people = people.map {
                if (it === who) it.copy(total = it.total + diff, adjust = diff) else it
            }
        }

        return Settlement(
            people = people,
            totalEnergyCost = sharedEnergy,
            totalExpense = sharedExpense,
            totalKm = endKm - startKm,
            ownerId = ownerId,
            soloKm = soloKm,
            soloCost = soloCost,
            driverChanged = driverChanged
        )
    }

    /** 지금 상태를 저장용으로 꺼내기 */
    fun snapshot() = TripSnapshot(
        settings, driverPays, fuelSource, _riders.toList(), onboard.toSet(), _segments.toList(),
        _expenses.toList(), segStartKm, segStartBatteryPct, startKm, started, nextId,
        tripStartBatteryPct, lastBatteryPct, ownerId
    )

    companion object {
        /** 저장해 둔 상태로 엔진 되살리기 */
        fun restore(s: TripSnapshot): TripEngine = TripEngine(s.settings).apply {
            driverPays = s.driverPays
            fuelSource = s.fuelSource
            _riders.addAll(s.riders)
            onboard.addAll(s.onboard)
            _segments.addAll(s.segments)
            _expenses.addAll(s.expenses)
            segStartKm = s.segStartKm
            segStartBatteryPct = s.segStartBatteryPct
            startKm = s.startKm
            started = s.started
            nextId = s.nextId
            tripStartBatteryPct = s.tripStartBatteryPct
            lastBatteryPct = s.lastBatteryPct
            ownerId = s.ownerId ?: s.riders.firstOrNull { it.isDriver }?.id
            driverChanged = s.segments.mapNotNull { it.driverId }.toSet().size > 1
        }

        /** 10원 단위 반올림: 4,958 → 4,960 / 4,954 → 4,950 */
        fun roundTo10(won: Double): Long = (won / 10.0).roundToLong() * 10
    }
}
