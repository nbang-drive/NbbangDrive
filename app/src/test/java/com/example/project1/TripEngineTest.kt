package com.example.project1

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 정산 엔진 테스트. Android Studio에서 클래스 이름 옆 ▶ 를 누르면 실행됩니다.
 * 계산 규칙을 바꾸면 여기 숫자부터 확인하세요.
 */
class TripEngineTest {

    private fun totals(s: Settlement) = s.people.associate { it.rider.name to it.total }

    /** 기본 시나리오: 휘발유 12km/L, 1,700원/L, 운전자 포함 */
    @Test
    fun 구간별_정산_중간탑승_하차_톨비_주차비() {
        val e = TripEngine(); e.driverPays = true
        e.addRider("민준", isDriver = true, seat = "ROW_1_LEFT")
        val seoyeon = e.addRider("서연", seat = "ROW_1_RIGHT")
        e.start(0.0)
        e.addExpense(ExpenseType.TOLL, 7000, 40.0)          // 40km: 톨비 (민준·서연)
        e.board("지호", 40.0, seat = "ROW_2_LEFT")            // 40km: 지호 탑승
        e.alight(seoyeon.id, 100.0)                            // 100km: 서연 하차
        e.finish(120.0)
        e.addExpense(ExpenseType.PARKING, 3000, 120.0)        // 120km: 주차비 (민준·지호)

        val s = e.settlement()
        assertEquals(mapOf("민준" to 12080L, "서연" to 9170L, "지호" to 5750L), totals(s))
        assertEquals(27000L, s.roundedSum)
    }

    /** 운전자 제외: 운전자 0원, 나머지가 나눔 */
    @Test
    fun 운전자_제외() {
        val e = TripEngine(); e.driverPays = false
        e.addRider("A", isDriver = true); e.addRider("B"); e.addRider("C")
        e.start(0.0); e.board("D", 40.0); e.addExpense(ExpenseType.TOLL, 7000, 70.0)
        e.alight(3, 100.0); e.finish(120.0); e.addExpense(ExpenseType.PARKING, 3000, 120.0)
        assertEquals(mapOf("A" to 0L, "B" to 10920L, "C" to 8000L, "D" to 8080L), totals(e.settlement()))
    }

    /** 반올림 차액은 차량 소유자(출발 때 운전자) 몫에서 맞춤: 실제 14,166.7원 → 합계 14,170원 */
    @Test
    fun 반올림_차액은_운전자() {
        val e = TripEngine(); e.driverPays = true
        e.addRider("A", isDriver = true); val b = e.addRider("B")
        e.start(0.0); e.alight(b.id, 50.0); e.board("C", 50.0); e.finish(100.0)
        val s = e.settlement()
        assertEquals(mapOf("A" to 7090L, "B" to 3540L, "C" to 3540L), totals(s))
        assertEquals(14170L, s.roundedSum)
        assertEquals("A", s.adjusted?.rider?.name)
    }

    /** 전기차: 거리 ÷ 전비 × 단가 (배터리는 참고값) */
    @Test
    fun 전기차_전비_계산() {
        val e = TripEngine(CarSettings(fuelType = FuelType.EV)); e.driverPays = true   // 5km/kWh, 300원/kWh
        e.addRider("A", isDriver = true); e.addRider("B")
        e.start(0.0, batteryPct = 80.0); e.finish(60.0, batteryPct = 68.0)
        assertEquals(mapOf("A" to 1800L, "B" to 1800L), totals(e.settlement()))
        assertEquals(12.0, e.batteryUsedPct, 0.001)
    }

    /** 저장했다 되살려도 이어서 계산이 같아야 함 */
    @Test
    fun 저장_복원() {
        val e = TripEngine(); e.driverPays = true
        e.addRider("A", isDriver = true); e.addRider("B"); e.addRider("C")
        e.start(0.0); e.board("D", 40.0); e.addExpense(ExpenseType.TOLL, 7000, 70.0)
        val copy = TripEngine.restore(e.snapshot())
        copy.alight(3, 100.0); copy.finish(120.0); copy.addExpense(ExpenseType.PARKING, 3000, 120.0)
        assertEquals(mapOf("A" to 7710L, "B" to 7710L, "C" to 5760L, "D" to 5820L), totals(copy.settlement()))
    }

    /** 좌석 감지 지점부터 계산: 40km에서 앉고, 이름은 60km에서 넣음. 50km 톨비에도 포함 */
    @Test
    fun 감지지점부터_탑승() {
        val e = TripEngine(); e.driverPays = true
        e.addRider("A", isDriver = true); e.addRider("B")
        e.start(0.0)
        e.addExpense(ExpenseType.TOLL, 3000, 50.0)       // 이때 앱에는 A·B만 있음
        e.board("C", 40.0)                                // 60km에서 입력했지만 감지는 40km
        e.finish(100.0)
        assertEquals(mapOf("A" to 6670L, "B" to 6670L, "C" to 3830L), totals(e.settlement()))
    }

    /** 좌석이 빈 지점부터 빠짐: 30km에서 비었고, 50km에서 확인 */
    @Test
    fun 빈지점부터_하차() {
        val e = TripEngine(); e.driverPays = true
        e.addRider("A", isDriver = true); val b = e.addRider("B"); e.addRider("C")
        e.start(0.0)
        e.alight(b.id, 30.0)
        e.finish(60.0)
        assertEquals(mapOf("A" to 3540L, "B" to 1420L, "C" to 3540L), totals(e.settlement()))
    }

    /** 확인 순서가 뒤바뀌어도 맞게: 30km에 C 앉음, 50km에 B 내림 → B 하차를 먼저 확인하고 C 탑승은 나중에 */
    @Test
    fun 확인순서_뒤바뀜() {
        val e = TripEngine(); e.driverPays = true
        e.addRider("A", isDriver = true); val b = e.addRider("B")
        e.start(0.0)
        e.addExpense(ExpenseType.TOLL, 3000, 30.0)   // 30km 톨비 (A·B)
        e.alight(b.id, 50.0)                          // 60km에서 확인, 50km에 내림
        e.board("C", 30.0)                            // 그다음 확인, 30km에 탔음
        e.finish(60.0)
        assertEquals(mapOf("A" to 5280L, "B" to 4570L, "C" to 1650L), totals(e.settlement()))
    }

    /** 도착 후 빠뜨린 비용 넣기: 20km 톨비(그때 탄 사람 자동) + 주차비(A·C만 직접 고름) */
    @Test
    fun 도착후_비용추가() {
        val e = TripEngine(); e.driverPays = true
        val a = e.addRider("A", isDriver = true); val b = e.addRider("B"); val c = e.addRider("C")
        e.start(0.0)
        e.alight(b.id, 30.0)
        e.finish(60.0)
        assertEquals(setOf(a.id, b.id, c.id), e.ridersAt(20.0))
        assertEquals(setOf(a.id, c.id), e.ridersAt(45.0))
        e.addExpenseFor(ExpenseType.TOLL, 2000, 20.0, e.ridersAt(20.0))
        e.addExpenseFor(ExpenseType.PARKING, 3000, 60.0, setOf(a.id, c.id))
        assertEquals(mapOf("A" to 5710L, "B" to 2080L, "C" to 5710L), totals(e.settlement()))
    }

    /** 기본값은 운전자 제외 */
    @Test
    fun 기본값_운전자_제외() {
        assertEquals(false, TripEngine().driverPays)
    }

    /**
     * 운전자 교대: A(차주)가 0~50km 운전, 50km 휴게소에서 B로 교대, B가 50~100km 운전. C는 끝까지 동승.
     * 운전자 제외 → 0~50은 B·C, 50~100은 A·C가 나눔. A(차주)가 동승한 몫은 A가 받을 돈에서 차감.
     */
    @Test
    fun 운전자_교대() {
        val e = TripEngine()
        val a = e.addRider("A", isDriver = true); val b = e.addRider("B"); e.addRider("C")
        e.start(0.0)
        e.addExpense(ExpenseType.TOLL, 2000, 30.0)        // A 운전 중 톨비 → B·C
        e.changeDriver(b.id, 50.0)
        e.addExpense(ExpenseType.TOLL, 3000, 80.0)        // B 운전 중 톨비 → A·C
        e.finish(100.0)
        val s = e.settlement()
        assertEquals(a.id, s.ownerId)
        assertEquals(mapOf("A" to 5050L, "B" to 4540L, "C" to 9580L), totals(s))
        assertEquals(19170L, s.roundedSum)
        assertEquals(14120L, s.ownerReceives)              // B + C, A 본인 몫(5,050원)은 차감
        assertEquals(50.0, s.people.first { it.rider.name == "B" }.driveKm, 0.001)
    }

    /** 운전자 혼자 달린 구간은 나눌 사람이 없으니 제외: B가 60km에서 내리고 A 혼자 100km까지 */
    @Test
    fun 운전자_혼자_구간_제외() {
        for (pays in listOf(false, true)) {
            val e = TripEngine(); e.driverPays = pays
            e.addRider("A", isDriver = true); val b = e.addRider("B")
            e.start(0.0)
            e.alight(b.id, 60.0)
            e.addExpense(ExpenseType.PARKING, 5000, 100.0)   // A 혼자일 때 주차비 → 제외
            e.finish(100.0)
            val s = e.settlement()
            assertEquals(40.0, s.soloKm, 0.001)
            assertEquals(0, s.totalExpense)
            if (pays) assertEquals(mapOf("A" to 4250L, "B" to 4250L), totals(s))
            else assertEquals(mapOf("A" to 0L, "B" to 8500L), totals(s))
        }
    }

    /** 교대 기록도 저장했다 되살리면 그대로 */
    @Test
    fun 교대_저장_복원() {
        val e = TripEngine()
        e.addRider("A", isDriver = true); val b = e.addRider("B"); e.addRider("C")
        e.start(0.0); e.changeDriver(b.id, 50.0)
        val copy = TripEngine.restore(e.snapshot())
        copy.finish(100.0)
        assertEquals(mapOf("A" to 3550L, "B" to 3540L, "C" to 7080L), totals(copy.settlement()))
        assertEquals(1, copy.settlement().ownerId)
    }
}
