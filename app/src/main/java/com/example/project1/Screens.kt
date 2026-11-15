package com.example.project1

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 화면 순서: 설정 → 출발 → 여행 중(주행/정차) → 정산 결과 */
enum class Screen(val step: String) {
    SETTINGS("1 설정"), DEPART("2 출발"), TRIP("3 여행 중"), RESULT("4 정산")
}

/** 금액 표시: 4960 → "4,960원" */
fun won(n: Long): String = "%,d원".format(n)
fun won(d: Double): String = won(TripEngine.roundTo10(d))
fun km(d: Double): String = "%.1f km".format(d)

// ---------------------------------------------------------------------------
// 앱 전체 틀
// ---------------------------------------------------------------------------
@Composable
fun NbbangApp(pleos: PleosCar?) {
    // 저장해 둔 여행이 있으면 거기서 이어서 시작 (앱이 꺼졌다 켜져도 기록 유지)
    val context = LocalContext.current
    val store = remember { TripStore(context) }
    val saved = remember { store.load() }

    val fake = remember {
        FakeVehicle().apply {
            if (saved != null) { odometerKm = saved.fakeOdometerKm; batteryPct = saved.fakeBatteryPct }
        }
    }
    var useCar by remember { mutableStateOf(if (saved != null) saved.useCar && pleos != null else pleos != null) }  // 진짜 차량 신호 사용 여부
    val car: CarSource = if (useCar && pleos != null) pleos else fake
    var engine by remember { mutableStateOf(saved?.let { TripEngine.restore(it.trip) } ?: TripEngine()) }
    var screen by remember { mutableStateOf(saved?.screen ?: Screen.SETTINGS) }
    var tick by remember { mutableIntStateOf(0) }          // 엔진 값이 바뀌면 화면 새로고침
    val refresh: () -> Unit = { tick++ }

    // 좌석 센서가 바뀐 지점(주행거리)을 기억 → 이름을 나중에 넣어도 그 지점부터 계산 (앱을 다시 켜도 유지)
    val marks = remember {
        SeatMarks().apply { saved?.let { onKm.putAll(it.seatOnKm); offKm.putAll(it.seatOffKm); it.seatManual.forEach { s -> manual[s] = true } } }
    }

    // 무언가 바뀔 때마다 저장
    LaunchedEffect(tick, screen, useCar, engine, fake.odometerKm, fake.batteryPct, marks.onKm.toMap(), marks.offKm.toMap(), marks.manual.toMap()) {
        store.save(SavedState(screen, useCar, fake.odometerKm, fake.batteryPct, engine.snapshot(), marks.onKm.toMap(), marks.offKm.toMap(), marks.manual.keys.toSet()))
    }

    val prevSeats = remember { mutableMapOf<String, Boolean>() }
    val seatNow = Seats.TRACKED.map { car.seats[it] == true }
    LaunchedEffect(seatNow, car) {
        Seats.TRACKED.forEachIndexed { i, seat ->
            val now = seatNow[i]
            // 앱을 막 켰을 때: 저장된 지점이 있으면 그대로 둔다
            if (prevSeats[seat] == null && ((now && seat in marks.onKm) || (!now && seat in marks.offKm))) {
                prevSeats[seat] = now
            } else if (prevSeats[seat] != now) {
                if (now) marks.onKm[seat] = car.odometerKm else marks.offKm[seat] = car.odometerKm
                if (now) marks.manual.remove(seat)   // 센서가 잡히면 손 등록 표시는 풀기
                prevSeats[seat] = now
            }
        }
    }

    // 출발 처리 (출발 버튼, 또는 D로 바꿨을 때 자동)
    val startTrip: () -> Unit = {
        // 연비 출처 정하기: 차가 연비를 주면 그 값, 아니면 설정값
        val s = engine.settings
        val carKmL = car.carFuelKmPerL
        engine.fuelSource = when {
            // 차가 준 연비가 말이 되는 범위(3~40 km/L)일 때만 사용
            s.fuelType == FuelType.GASOLINE && carKmL != null && carKmL in 3.0..40.0 -> {
                engine.settings = s.copy(kmPerLiter = Math.round(carKmL * 10) / 10.0)
                "차량 연비 (Fuel SDK)"
            }
            s.fuelType == FuelType.GASOLINE -> "설정 연비"
            else -> "설정 전비"
        }
        engine.start(car.odometerKm, car.batteryPct)
        screen = Screen.TRIP
    }

    // 출발 버튼을 안 누르고 D로 바꾸면 자동 출발: 이름 없는 자리는 "손님1, 손님2…"로 임시 등록
    LaunchedEffect(car.gear, screen) {
        if (screen == Screen.DEPART && car.gear == Gear.D && !engine.started) {
            var n = 1
            Seats.TRACKED.filter { seat -> car.seats[seat] == true && engine.riders.none { it.seat == seat } }
                .forEach { seat ->
                    val isDrv = seat == Seats.DRIVER
                    val r = engine.addRider(if (isDrv) "운전자" else "손님${n++}", isDriver = engine.riders.isEmpty() || isDrv, seat = seat)
                    if (isDrv) engine.setDriver(r.id)
                }
            if (engine.riders.isEmpty()) engine.addRider("운전자", isDriver = true, seat = Seats.DRIVER)
            startTrip()
            refresh()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Header(screen)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            key(tick, screen) {
                when (screen) {
                    Screen.SETTINGS -> SettingsScreen(engine.settings, useCar, pleos != null) { newSettings, newUseCar ->
                        engine.settings = newSettings
                        useCar = newUseCar
                        screen = Screen.DEPART
                    }

                    Screen.DEPART -> DepartScreen(
                        engine = engine,
                        car = car,
                        marks = marks,
                        refresh = refresh,
                        onSettings = { screen = Screen.SETTINGS },
                        onStart = startTrip
                    )

                    Screen.TRIP ->
                        if (car.gear == Gear.D) DrivingScreen(engine, car, marks, refresh)
                        else StoppedScreen(engine, car, marks, refresh) {
                            engine.finish(car.odometerKm, car.batteryPct)
                            screen = Screen.RESULT
                        }

                    Screen.RESULT -> ResultScreen(engine, refresh) {
                        engine = TripEngine(engine.settings)   // 설정은 그대로, 새 여행
                        screen = Screen.DEPART
                    }
                }
            }
        }
        if (car is FakeVehicle) DevVehicleBar(car) else SignalBar(car)
    }
}

@Composable
fun Header(screen: Screen) {
    Row(
        Modifier.fillMaxWidth().background(CardWhite).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("N빵 드라이브", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Accent)
        Spacer(Modifier.width(24.dp))
        Screen.entries.forEach { s ->
            val on = s == screen
            Text(
                s.step,
                fontSize = 16.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) Accent else Muted,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .background(if (on) AccentSoft else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 공통 부품
// ---------------------------------------------------------------------------
@Composable
fun Title(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)

@Composable
fun Sub(text: String) = Text(text, fontSize = 16.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(16.dp)
    ) { Column(Modifier.padding(20.dp)) { content() } }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp)
    ) { Text(text, fontSize = 16.sp) }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// ① 설정 화면
// ---------------------------------------------------------------------------
@Composable
fun SettingsScreen(current: CarSettings, useCarNow: Boolean, carAvailable: Boolean, onSave: (CarSettings, Boolean) -> Unit) {
    var useCar by remember { mutableStateOf(useCarNow) }
    var carName by remember { mutableStateOf(current.carName) }
    var fuelType by remember { mutableStateOf(current.fuelType) }
    var kmPerL by remember { mutableStateOf(current.kmPerLiter.toString()) }
    var fuelPrice by remember { mutableStateOf(current.fuelPricePerLiter.toString()) }
    var kmPerKwh by remember { mutableStateOf(current.kmPerKwh.toString()) }
    var battery by remember { mutableStateOf(current.batteryCapacityKwh.toString()) }
    var kwhPrice by remember { mutableStateOf(current.pricePerKwh.toString()) }
    var link by remember { mutableStateOf(current.transferLink) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Title("차량 · 요금 설정")
            Sub("처음 한 번만 하면 돼요. 차종 기본값이 들어가 있고, 계기판 연비를 보고 고칠 수 있어요.")
            OutlinedTextField(
                value = carName, onValueChange = { carName = it },
                label = { Text("차종 (나중에 CarInfo로 자동 입력)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val gas = fuelType == FuelType.GASOLINE
                if (gas) BigButton("휘발유") {} else SmallButton("휘발유") { fuelType = FuelType.GASOLINE }
                if (!gas) BigButton("전기차") {} else SmallButton("전기차") { fuelType = FuelType.EV }
            }
            Spacer(Modifier.height(16.dp))
            if (fuelType == FuelType.GASOLINE) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("연비 (km/L)", kmPerL, { kmPerL = it }, Modifier.weight(1f))
                    NumberField("기름값 (원/L)", fuelPrice, { fuelPrice = it }, Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("전비 (km/kWh)", kmPerKwh, { kmPerKwh = it }, Modifier.weight(1f))
                    NumberField("충전 단가 (원/kWh)", kwhPrice, { kwhPrice = it }, Modifier.weight(1f))
                    NumberField("배터리 용량 (kWh, 참고)", battery, { battery = it }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = link, onValueChange = { link = it },
                label = { Text("운전자 송금 링크 (선택 · 카카오페이/토스)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        Column(Modifier.width(340.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Text("계산 방식", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (fuelType == FuelType.GASOLINE)
                    Text("기름값 = 달린 거리 ÷ 연비 × 기름값\n예) 120km ÷ 12 × 1,700 = 17,000원", color = Muted, fontSize = 15.sp)
                else
                    Text("충전비 = 달린 거리 ÷ 전비 × 충전 단가\n예) 120km ÷ 5 × 300 = 7,200원\n배터리 사용량은 정산 화면에 참고로 보여줘요", color = Muted, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("실제 차량 신호 사용", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (useCar) "기어·주행거리·배터리·좌석을 차(SDK)에서 읽어요"
                            else "아래 노란 막대로 직접 조작하는 모의 차량",
                            color = Muted, fontSize = 14.sp
                        )
                    }
                    Switch(checked = useCar, enabled = carAvailable, onCheckedChange = { useCar = it })
                }
            }
            Spacer(Modifier.weight(1f))
            BigButton("저장하고 다음", Modifier.fillMaxWidth()) {
                onSave(
                    CarSettings(
                        carName = carName.ifBlank { current.carName },
                        fuelType = fuelType,
                        kmPerLiter = kmPerL.toDoubleOrNull()?.takeIf { it > 0 } ?: current.kmPerLiter,
                        fuelPricePerLiter = fuelPrice.toIntOrNull()?.takeIf { it > 0 } ?: current.fuelPricePerLiter,
                        kmPerKwh = kmPerKwh.toDoubleOrNull()?.takeIf { it > 0 } ?: current.kmPerKwh,
                        batteryCapacityKwh = battery.toDoubleOrNull()?.takeIf { it > 0 } ?: current.batteryCapacityKwh,
                        pricePerKwh = kwhPrice.toIntOrNull()?.takeIf { it > 0 } ?: current.pricePerKwh,
                        transferLink = link.trim()
                    ),
                    useCar
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 좌석 그림: 차 실내를 위에서 본 모습. 좌석에는 이름과 색깔만 (1인당 금액은 정산 화면에서만)
// ---------------------------------------------------------------------------

/** 좌석 센서가 바뀐 지점(주행거리) 기록: 탑승/하차를 그 지점부터 계산하는 데 씀 */
class SeatMarks {
    val onKm = mutableStateMapOf<String, Double>()   // 사람이 앉은 지점
    val offKm = mutableStateMapOf<String, Double>()  // 자리가 빈 지점
    val manual = mutableStateMapOf<String, Boolean>() // 센서 없이 손으로 등록한 자리 (센서가 한 번 감지하면 풀림)
    val swapAsked = mutableStateMapOf<String, Double>() // 운전자 교대를 이미 물어본 지점 (같은 걸 또 묻지 않게)
}

/**
 * 운전자 교대: 새 운전자를 운전석으로, 원래 운전자는 새 운전자가 앉았던 자리로.
 * 이 지점부터 새 운전자로 기록. 송금 받는 사람(차주)은 그대로.
 */
fun swapDriver(engine: TripEngine, newId: Int, car: CarSource) {
    val old = engine.riders.firstOrNull { it.isDriver }
    val newSeat = engine.riders.firstOrNull { it.id == newId }?.seat
    engine.changeDriver(newId, car.odometerKm, car.batteryPct)
    engine.assignSeat(newId, Seats.DRIVER)
    if (old != null && old.id != newId) engine.assignSeat(old.id, if (newSeat == Seats.DRIVER) null else newSeat)
}

/** 좌석 상태 */
enum class SeatState {
    EMPTY,    // 회색 + 추가하기: 빈자리
    UNKNOWN,  // 주황 + 감지됨: 센서는 감지했는데 이름 없음
    ONBOARD,  // 파랑 + 이름: 탑승 중
    AWAY,     // 연파랑 + 잠시 비움: 정차 중에 잠깐 일어남 (차가 아직 안 움직임)
    LEFT      // 빨강 + 내리셨나요?: 자리가 빈 채로 차가 움직였음
}

data class SeatInfo(val seat: String, val state: SeatState, val rider: Rider?)

/** 자리가 빈 뒤 이만큼(km) 움직이면 "잠시 비움" → "내리셨나요?" */
const val AWAY_KM = 0.3

fun seatInfo(seat: String, engine: TripEngine, car: CarSource, marks: SeatMarks? = null): SeatInfo {
    val rider = if (engine.started) engine.onboardRiderAt(seat) else engine.riders.firstOrNull { it.seat == seat }
    val sensed = car.seats[seat] == true || marks?.manual?.get(seat) == true
    val off = marks?.offKm?.get(seat)
    val state = when {
        rider != null && sensed -> SeatState.ONBOARD
        rider != null && !engine.started -> SeatState.AWAY
        rider != null && (off == null || car.odometerKm - off < AWAY_KM) -> SeatState.AWAY
        rider != null -> SeatState.LEFT
        sensed -> SeatState.UNKNOWN
        else -> SeatState.EMPTY
    }
    return SeatInfo(seat, state, rider)
}

private val Orange = Color(0xFFEA580C)
private val OrangeDeep = Color(0xFFC2410C)
private val Red = Color(0xFFDC2626)
private val RedDeep = Color(0xFFB91C1C)
private val BlueDeep = Color(0xFF1D4ED8)
private val AwayFill = Color(0xFFBFDBFE)
private val AwayDeep = Color(0xFF93C5FD)
private val AwayInk = Color(0xFF1E3A8A)
private val EmptyFill = Color(0xFFE2E8F0)
private val EmptyDeep = Color(0xFFCBD5E1)
private val EmptyInk = Color(0xFF475569)
private val CarBody = Color(0xFF64748B)
private val Cabin = Color(0xFF1E293B)
private val CabinDark = Color(0xFF0F172A)

fun seatCaption(seat: String): String = when (seat) {
    "ROW_1_LEFT" -> "운전석"
    "ROW_1_RIGHT" -> "조수석"
    "ROW_2_LEFT" -> "뒤 왼쪽"
    "ROW_2_CENTER" -> "뒤 가운데"
    "ROW_2_RIGHT" -> "뒤 오른쪽"
    else -> seat
}

/** 상태별 색: (좌석 색, 머리받이/그림자 색, 글자 색) */
fun seatColors(state: SeatState): Triple<Color, Color, Color> = when (state) {
    SeatState.EMPTY -> Triple(EmptyFill, EmptyDeep, EmptyInk)
    SeatState.UNKNOWN -> Triple(Orange, OrangeDeep, Color.White)
    SeatState.ONBOARD -> Triple(Accent, BlueDeep, Color.White)
    SeatState.AWAY -> Triple(AwayFill, AwayDeep, AwayInk)
    SeatState.LEFT -> Triple(Red, RedDeep, Color.White)
}

// ---- 그림 아이콘 (아이콘 라이브러리 없이 직접 그림) ----
@Composable
fun PersonIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(color, radius = w * 0.2f, center = Offset(w / 2, w * 0.3f))
        drawRoundRect(color, topLeft = Offset(w * 0.18f, w * 0.56f), size = Size(w * 0.64f, w * 0.36f), cornerRadius = CornerRadius(w * 0.3f, w * 0.3f))
    }
}

@Composable
fun PlusIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.12f
        drawLine(color, Offset(w / 2, w * 0.15f), Offset(w / 2, w * 0.85f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.15f, w / 2), Offset(w * 0.85f, w / 2), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** 감지 신호 ((•)) */
@Composable
fun SignalIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2, w / 2)
        val sw = w * 0.08f
        drawCircle(color, radius = w * 0.08f, center = c)
        listOf(0.25f, 0.42f).forEach { r ->
            val tl = Offset(c.x - w * r, c.y - w * r)
            val sz = Size(w * r * 2, w * r * 2)
            drawArc(color, -45f, 90f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, 135f, 90f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

/** 좌석 하나: 머리받이 + 등받이 모양. w = 좌석 폭 */
@Composable
fun SeatTile(info: SeatInfo, w: Dp, mode: SeatMode, onSeat: (SeatInfo) -> Unit) {
    val (fill, deep, ink) = seatColors(info.state)
    val bodyShape = RoundedCornerShape(topStart = w * 0.26f, topEnd = w * 0.26f, bottomStart = w * 0.14f, bottomEnd = w * 0.14f)
    val icon = w * 0.24f
    val nameSize = (w.value * 0.15f).coerceIn(14f, 34f).sp
    val smallSize = (w.value * 0.1f).coerceIn(11f, 20f).sp
    Column(
        Modifier.width(w).clickable { onSeat(info) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 머리받이
        Box(Modifier.width(w * 0.46f).height(w * 0.13f).background(deep, RoundedCornerShape(50)))
        Spacer(Modifier.height(3.dp))
        // 등받이(몸통)
        Box(
            Modifier.width(w).height(w * 0.9f)
                .shadow(6.dp, bodyShape)
                .background(Brush.verticalGradient(listOf(fill, fill, deep)), bodyShape)
                .let { if (info.state == SeatState.AWAY) it.border(2.dp, Accent, bodyShape) else it },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (info.state) {
                    SeatState.EMPTY -> if (mode == SeatMode.DRIVING) Text("—", fontSize = nameSize, color = ink) else PlusIcon(ink, icon * 0.85f)
                    SeatState.UNKNOWN -> SignalIcon(ink, icon)
                    else -> PersonIcon(ink, icon)
                }
                Spacer(Modifier.height(4.dp))
                val main = when (info.state) {
                    SeatState.EMPTY -> if (mode == SeatMode.DRIVING) "빈자리" else "추가하기"
                    SeatState.UNKNOWN -> "감지됨"
                    SeatState.ONBOARD, SeatState.AWAY, SeatState.LEFT -> info.rider?.name ?: ""
                }
                Text(main, fontSize = nameSize, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp))
                val sub = when {
                    info.state == SeatState.AWAY -> "잠시 비움"
                    info.state == SeatState.LEFT -> "내리셨나요?"
                    info.state == SeatState.ONBOARD && info.rider?.isDriver == true -> "(운전자)"
                    else -> seatCaption(info.seat)
                }
                Text(sub, fontSize = smallSize, fontWeight = if (info.state == SeatState.LEFT) FontWeight.Bold else FontWeight.Normal,
                    color = ink.copy(alpha = if (info.state == SeatState.LEFT) 1f else 0.85f), maxLines = 1)
            }
        }
    }
}

/**
 * 차 실내 탑뷰 좌석 지도. 주어진 공간에 맞춰 좌석 크기를 정한다 (화면의 큰 부분을 차지하도록).
 * maxSeat: 좌석 폭 최대값
 */
@Composable
fun SeatMap(engine: TripEngine, car: CarSource, marks: SeatMarks, mode: SeatMode, modifier: Modifier = Modifier, maxSeat: Dp = 400.dp, onSeat: (SeatInfo) -> Unit) {
    val info = { seat: String -> seatInfo(seat, engine, car, marks) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 차 전체 크기 = 좌석 폭 s의 약 3.5배(가로) × 3.1배(세로) + 범례
        val byH = (maxHeight - 44.dp) / 3.15f
        val byW = (maxWidth - 8.dp) / 3.5f
        val s = minOf(byH, byW, maxSeat).coerceAtLeast(70.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Top) {
                // 왼쪽 사이드미러
                Box(Modifier.padding(top = s * 0.62f).width(s * 0.14f).height(s * 0.24f).background(CarBody, RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp)))
                val body = RoundedCornerShape(topStart = s * 0.9f, topEnd = s * 0.9f, bottomStart = s * 0.42f, bottomEnd = s * 0.42f)
                Box(
                    Modifier.shadow(10.dp, body).background(Brush.horizontalGradient(listOf(CarBody, Color(0xFF94A3B8), CarBody)), body).padding(s * 0.08f)
                ) {
                    val inner = RoundedCornerShape(topStart = s * 0.84f, topEnd = s * 0.84f, bottomStart = s * 0.36f, bottomEnd = s * 0.36f)
                    Column(
                        Modifier.background(Cabin, inner).padding(horizontal = s * 0.1f).padding(bottom = s * 0.14f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 앞유리 + 대시보드 + 핸들
                        Box(Modifier.width(s * 2.7f).height(s * 0.45f).background(CabinDark, RoundedCornerShape(bottomStart = s * 0.2f, bottomEnd = s * 0.2f))) {
                            Box(
                                Modifier.align(Alignment.BottomStart).padding(start = s * 0.33f, bottom = s * 0.02f)
                                    .size(s * 0.4f).border(s * 0.05f, Color(0xFF64748B), RoundedCornerShape(50))
                            )
                            Text("▲ 앞", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
                        }
                        Spacer(Modifier.height(s * 0.06f))
                        // 앞줄: 운전석 · 콘솔 · 조수석
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SeatTile(info("ROW_1_LEFT"), s, mode, onSeat)
                            Box(Modifier.padding(horizontal = s * 0.12f).width(s * 0.3f).height(s * 0.85f).background(CabinDark, RoundedCornerShape(s * 0.1f)))
                            SeatTile(info("ROW_1_RIGHT"), s, mode, onSeat)
                        }
                        Spacer(Modifier.height(s * 0.22f))
                        // 뒷줄 벤치
                        Row(
                            Modifier.background(CabinDark, RoundedCornerShape(s * 0.16f)).padding(horizontal = s * 0.06f, vertical = s * 0.05f),
                            horizontalArrangement = Arrangement.spacedBy(s * 0.05f)
                        ) {
                            SeatTile(info("ROW_2_LEFT"), s * 0.86f, mode, onSeat)
                            SeatTile(info("ROW_2_CENTER"), s * 0.86f, mode, onSeat)
                            SeatTile(info("ROW_2_RIGHT"), s * 0.86f, mode, onSeat)
                        }
                    }
                }
                // 오른쪽 사이드미러
                Box(Modifier.padding(top = s * 0.62f).width(s * 0.14f).height(s * 0.24f).background(CarBody, RoundedCornerShape(topEnd = 50.dp, bottomEnd = 50.dp)))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(Accent, "탑승")
                Legend(Orange, "감지됨")
                Legend(AwayFill, "잠시 비움")
                Legend(Red, "내리셨나요?")
                Legend(EmptyFill, "빈자리")
            }
        }
    }
}

@Composable
fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(color, RoundedCornerShape(50)).border(1.dp, Color(0x22000000), RoundedCornerShape(50)))
        Text(" $text", fontSize = 16.sp, color = Muted)
    }
}

// ---------------------------------------------------------------------------
// 좌석을 눌렀을 때 뜨는 창
// ---------------------------------------------------------------------------
class SeatAction(val label: String, val needsName: Boolean = false, val primary: Boolean = false, val run: (String) -> Unit)

@Composable
fun SeatDialog(
    title: String,
    message: String,
    showName: Boolean,
    state: SeatState = SeatState.ONBOARD,
    initialName: String = "",
    actions: List<SeatAction>,
    voiceOnly: Boolean = false,          // 주행 중: 키보드 없이 음성으로만 이름
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val voice = LocalVoice.current
    DisposableEffect(Unit) { onDispose { voice?.cancel() } }
    val (fill, _, ink) = seatColors(state)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Text("닫기", fontSize = 16.sp)
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = CardWhite,
        icon = {
            Box(Modifier.size(72.dp).background(fill, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                when (state) {
                    SeatState.UNKNOWN -> SignalIcon(ink, 38.dp)
                    SeatState.EMPTY -> PlusIcon(ink, 30.dp)
                    else -> PersonIcon(ink, 38.dp)
                }
            }
        },
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, fontSize = 16.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (showName && !voiceOnly) OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("탑승자 이름") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
                if (showName && voiceOnly) Box(
                    Modifier.fillMaxWidth().height(56.dp).background(Line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(name.ifBlank { "아래 마이크를 누르고 이름을 말해 주세요" }, fontSize = if (name.isBlank()) 15.sp else 20.sp,
                        fontWeight = if (name.isBlank()) FontWeight.Normal else FontWeight.Bold, color = if (name.isBlank()) Muted else Ink)
                }
                if (showName && voice != null && voice.state != VoiceInput.State.UNAVAILABLE) VoiceButton(voice) { name = it }
                if (showName && voice != null && voice.state == VoiceInput.State.UNAVAILABLE)
                    Text("음성 입력 꺼짐 · ${voice.message ?: "준비 실패"}", fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                actions.forEach { a ->
                    val ok = !a.needsName || name.isNotBlank()
                    val text = if (a.needsName && name.isNotBlank()) "${a.label} · ${name.trim()}" else a.label
                    val click = { a.run(name.trim()); onDismiss() }
                    if (a.primary)
                        Button(onClick = click, enabled = ok, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp)) {
                            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    else
                        OutlinedButton(onClick = click, enabled = ok, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                            Text(text, fontSize = 16.sp)
                        }
                }
            }
        }
    )
}

/** 🎤 말해서 이름 넣기 버튼 (Gleo AI SpeechToText) */
@Composable
fun VoiceButton(voice: VoiceInput, onName: (String) -> Unit) {
    val listening = voice.state == VoiceInput.State.LISTENING
    val ready = voice.state == VoiceInput.State.READY || listening
    Button(
        onClick = { if (listening) voice.cancel() else voice.listen(onName) },
        enabled = ready,
        modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (listening) Red else Ink)
    ) {
        MicIcon(Color.White, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                listening -> "듣고 있어요… (누르면 멈춤)"
                voice.state == VoiceInput.State.PREPARING -> "음성 인식 준비 중…"
                else -> "말해서 이름 넣기"
            },
            fontSize = 17.sp, fontWeight = FontWeight.Bold
        )
    }
    if (listening && voice.partial.isNotBlank()) Text("“${voice.partial}”", fontSize = 16.sp, color = Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    voice.message?.let { Text(it, fontSize = 14.sp, color = Warn, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
}

@Composable
fun MicIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(color, topLeft = Offset(w * 0.34f, w * 0.05f), size = Size(w * 0.32f, w * 0.55f), cornerRadius = CornerRadius(w * 0.16f, w * 0.16f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.2f, w * 0.25f), Size(w * 0.6f, w * 0.5f), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, w * 0.75f), Offset(w / 2, w * 0.92f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    }
}

enum class SeatMode { DEPART, STOPPED, DRIVING }

@Composable
fun SeatDialogFor(
    seat: String,
    mode: SeatMode,
    engine: TripEngine,
    car: CarSource,
    marks: SeatMarks,
    refresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = seatInfo(seat, engine, car, marks)
    val label = Seats.label(seat)
    val keyboard = mode != SeatMode.DRIVING                    // 주행 중에는 글자 입력 막기
    val voiceOk = LocalVoice.current?.available == true          // 음성 입력 가능 (주행 중엔 이것만)
    val onAt = marks.onKm[seat] ?: car.odometerKm               // 사람이 앉은 지점
    val offAt = marks.offKm[seat] ?: car.odometerKm             // 자리가 빈 지점
    fun tripKm(x: Double) = km((x - engine.startKm).coerceAtLeast(0.0))

    // 이 자리로 "옮겨 앉았을" 수 있는 사람: 자리가 없거나, 원래 자리가 비어 있는 탑승자
    val pool = if (engine.started) engine.riders.filter { it.id in engine.onboardIds } else engine.riders
    val movers = pool.filter { r ->
        r.seat != seat && (r.seat == null || seatInfo(r.seat, engine, car, marks).state.let { it == SeatState.AWAY || it == SeatState.LEFT })
    }
    val moveActions = movers.map { u ->
        SeatAction(if (u.seat == null) "${u.name} 님 자리로 연결" else "${u.name} 님이 옮겨 앉았어요") { _ ->
            engine.assignSeat(u.id, seat)
            marks.manual.remove(u.seat ?: "")
            if (!engine.started && seat == Seats.DRIVER) engine.setDriver(u.id)
            refresh()
        }
    }

    when (info.state) {
        SeatState.EMPTY -> {
            // 센서가 없거나 안 잡히는 자리: 손으로 추가
            val add = when (mode) {
                SeatMode.DEPART -> listOf(SeatAction("이 자리에 등록", needsName = true, primary = true) { n ->
                    val r = engine.addRider(n, isDriver = engine.riders.isEmpty() || seat == Seats.DRIVER, seat = seat)
                    if (seat == Seats.DRIVER) engine.setDriver(r.id)
                    marks.manual[seat] = true
                    refresh()
                })
                SeatMode.STOPPED -> listOf(SeatAction("지금 탑승", needsName = true, primary = true) { n ->
                    engine.board(n, car.odometerKm, car.batteryPct, seat)
                    marks.manual[seat] = true
                    refresh()
                })
                SeatMode.DRIVING -> emptyList()
            }
            SeatDialog(
                "$label · 빈자리",
                if (mode == SeatMode.DRIVING) "주행 중에는 추가할 수 없어요. 정차(P) 후에 눌러 주세요."
                else "사람이 앉으면 자동으로 '감지됨'으로 바뀌어요.\n센서가 안 잡히면 여기서 직접 추가하세요.",
                showName = keyboard && add.isNotEmpty(), state = SeatState.EMPTY,
                actions = add + if (mode == SeatMode.DRIVING) emptyList() else moveActions, onDismiss = onDismiss
            )
        }

        SeatState.UNKNOWN -> {
            val msg = when (mode) {
                SeatMode.DEPART -> "누가 앉았나요? 이름을 넣어 주세요."
                SeatMode.STOPPED -> "새로 탄 사람이에요. 앉은 지점(${tripKm(onAt)})부터 계산해요."
                SeatMode.DRIVING -> if (voiceOk) "말로 이름을 알려 주세요 (키보드는 정차 후에).\n앉은 지점(${tripKm(onAt)})부터 계산돼요."
                    else "이름은 정차(P) 후에 넣을 수 있어요.\n앉은 지점(${tripKm(onAt)})부터 계산되니 그대로 두셔도 돼요."
            }
            val register = when (mode) {
                SeatMode.DEPART -> listOf(SeatAction("등록", needsName = true, primary = true) { n ->
                    val r = engine.addRider(n, isDriver = engine.riders.isEmpty() || seat == Seats.DRIVER, seat = seat)
                    if (seat == Seats.DRIVER) engine.setDriver(r.id)
                    refresh()
                })
                SeatMode.STOPPED -> listOf(SeatAction("탑승", needsName = true, primary = true) { n ->
                    engine.board(n, onAt, car.batteryPct, seat)
                    refresh()
                })
                SeatMode.DRIVING -> if (voiceOk) listOf(SeatAction("탑승", needsName = true, primary = true) { n ->
                    engine.board(n, onAt, car.batteryPct, seat)
                    refresh()
                }) else emptyList()
            }
            SeatDialog("$label · 누가 탔나요?", msg, showName = keyboard || voiceOk, state = SeatState.UNKNOWN, actions = register + moveActions,
                voiceOnly = !keyboard, onDismiss = onDismiss)
        }

        SeatState.ONBOARD -> {
            val r = info.rider!!
            val acts = mutableListOf<SeatAction>()
            if (keyboard) acts += SeatAction("이름 바꾸기", needsName = true) { n -> engine.renameRider(r.id, n); refresh() }
            if (mode == SeatMode.DEPART) {
                if (!r.isDriver) acts += SeatAction("운전자로 지정") { _ -> engine.setDriver(r.id); refresh() }
                acts += SeatAction("명단에서 빼기") { _ -> engine.removeRiderBeforeStart(r.id); marks.manual.remove(seat); refresh() }
            } else if (mode == SeatMode.STOPPED) {
                if (r.isDriver) {
                    // 운전석 → 다른 사람에게 운전 교대
                    engine.riders.filter { it.id in engine.onboardIds && !it.isDriver }.forEach { o ->
                        acts += SeatAction("운전 교대 → ${o.name} 님") { _ -> swapDriver(engine, o.id, car); refresh() }
                    }
                } else {
                    acts += SeatAction("${r.name} 님이 운전 (교대)") { _ -> swapDriver(engine, r.id, car); refresh() }
                    acts += SeatAction("지금 하차") { _ -> engine.alight(r.id, car.odometerKm, car.batteryPct); marks.manual.remove(seat); refresh() }
                }
            }
            val ownerNote = if (engine.started && r.id == engine.ownerId) " · 차주" else ""
            SeatDialog(
                "$label · ${r.name}${if (r.isDriver) " (운전자)" else ""}$ownerNote",
                when {
                    mode == SeatMode.DEPART -> "등록된 사람이에요."
                    mode == SeatMode.STOPPED && r.isDriver -> "운전 중인 사람이에요. 휴게소에서 운전을 바꾸면 여기서 교대하세요.\n교대한 지점부터 새 운전자로 기록돼요."
                    else -> "타고 있어요."
                },
                showName = keyboard, state = SeatState.ONBOARD, initialName = r.name, actions = acts, onDismiss = onDismiss
            )
        }

        SeatState.AWAY, SeatState.LEFT -> {
            val r = info.rider!!
            // 다른 자리에 새로 감지된 사람이 있으면 → 그 자리로 옮긴 걸로 처리
            val orangeSeats = Seats.TRACKED.filter { it != seat && seatInfo(it, engine, car, marks).state == SeatState.UNKNOWN }
            val moveTo = orangeSeats.map { s ->
                SeatAction("${Seats.label(s)}(으)로 옮겼어요") { _ -> engine.assignSeat(r.id, s); refresh() }
            }
            val acts = if (mode == SeatMode.DEPART) moveTo + listOf(
                SeatAction("명단에서 빼기") { _ -> engine.removeRiderBeforeStart(r.id); refresh() }
            ) else if (mode == SeatMode.DRIVING) emptyList()   // 주행 중에는 표시만, 확인은 정차(P) 후에
            else listOf(
                SeatAction("네, 내렸어요 (${tripKm(offAt)} 지점)", primary = true) { _ ->
                    engine.alight(r.id, offAt, car.batteryPct); refresh()
                }
            ) + moveTo + listOf(
                SeatAction("잠깐 나갔어요 · 계속 탑승") { _ -> refresh() }
            )
            val msg = when {
                mode == SeatMode.DEPART -> "자리가 비어 있어요. 다시 앉으면 원래대로 돌아와요."
                mode == SeatMode.DRIVING -> "주행 중에는 확인하지 않아요. 정차(P) 후에 눌러 주세요.\n자리가 빈 지점(${tripKm(offAt)})부터 계산되니 그대로 두셔도 돼요."
                info.state == SeatState.AWAY -> "자리가 잠시 비었어요. 다시 앉으면 자동으로 원래대로 돌아와요.\n내렸다면 자리가 빈 지점부터 계산에서 빠져요."
                else -> "자리가 빈 채로 차가 움직였어요.\n내렸다면 자리가 빈 지점(${tripKm(offAt)})부터 계산에서 빠져요."
            }
            SeatDialog(
                "$label · ${r.name} 님 내리셨나요?", msg,
                showName = false, state = info.state, actions = acts, onDismiss = onDismiss
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ② 출발 화면: 큰 좌석 그림 + 탑승자 목록
// ---------------------------------------------------------------------------
@Composable
fun DepartScreen(
    engine: TripEngine,
    car: CarSource,
    marks: SeatMarks,
    refresh: () -> Unit,
    onSettings: () -> Unit,
    onStart: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dialogSeat by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        SeatMap(engine, car, marks, SeatMode.DEPART, Modifier.weight(1.6f).fillMaxHeight()) { dialogSeat = it.seat }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title("누가 탔나요?")
                Spacer(Modifier.weight(1f))
                Text("총 ${engine.riders.size}명", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink,
                    modifier = Modifier.background(Line, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
            Sub("주황색 '감지됨' 자리를 눌러 이름을 넣어 주세요. 기어를 D로 바꾸면 자동으로 출발해요.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 주황 자리가 하나뿐이면 여기서 입력해도 그 자리로 등록
                val orange = Seats.TRACKED.filter { seatInfo(it, engine, car, marks).state == SeatState.UNKNOWN }
                val target = orange.singleOrNull()
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(if (target != null) "이름 → ${Seats.label(target)}에 등록" else "이름 (좌석 없이 추가)") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                BigButton("추가", enabled = name.isNotBlank()) {
                    val isDrv = target == Seats.DRIVER
                    val r = engine.addRider(name, isDriver = engine.riders.isEmpty() || isDrv, seat = target)
                    if (isDrv) engine.setDriver(r.id)
                    name = ""
                    refresh()
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(engine.riders) { r ->
                    Row(
                        Modifier.fillMaxWidth().background(CardWhite, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("  ${Seats.label(r.seat)}", fontSize = 14.sp, color = Muted, modifier = Modifier.weight(1f))
                        if (r.isDriver) Text("운전자", color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        else SmallButton("운전자로") { engine.setDriver(r.id); refresh() }
                        Spacer(Modifier.width(6.dp))
                        SmallButton("빼기") { engine.removeRiderBeforeStart(r.id); r.seat?.let { marks.manual.remove(it) }; refresh() }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(engine.settings.carName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        val s = engine.settings
                        Text(
                            if (s.fuelType == FuelType.GASOLINE) "연비 ${s.kmPerLiter}km/L · 기름값 ${"%,d".format(s.fuelPricePerLiter)}원/L"
                            else "전비 ${s.kmPerKwh}km/kWh · 충전 ${s.pricePerKwh}원/kWh",
                            color = Muted, fontSize = 14.sp
                        )
                    }
                    SmallButton("설정", onClick = onSettings)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
                val owner = engine.riders.firstOrNull { it.isDriver }
                Text("차주 · 송금 받는 사람: ${owner?.name ?: "운전석에 등록한 사람"}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("운전자도 비용을 낼지는 도착할 때 물어봐요 (기본: 운전자 제외)", color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (car.gear != Gear.P) {
                Text("기어를 P로 두고 출발을 눌러주세요", color = Warn, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
            }
            BigButton(
                "주행 시작하기 (${engine.riders.size}명)",
                Modifier.fillMaxWidth(),
                enabled = engine.riders.isNotEmpty() && car.gear == Gear.P,
                onClick = onStart
            )
        }
    }
    dialogSeat?.let { SeatDialogFor(it, SeatMode.DEPART, engine, car, marks, refresh) { dialogSeat = null } }
}

// ---------------------------------------------------------------------------
// 톨비 빠른 버튼 (주행 중에도 버튼 한 번으로)
// ---------------------------------------------------------------------------
val QuickToll = listOf(1000, 3000, 5000)

@Composable
fun QuickTollPanel(engine: TripEngine, car: CarSource, refresh: () -> Unit) {
    val n = engine.onboardIds.size
    Panel(Modifier.fillMaxWidth()) {
        Text("톨비 빠르게 넣기", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("지금 탄 ${n}명이 나눠요", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickToll.forEach { a ->
                Button(
                    onClick = { engine.addExpense(ExpenseType.TOLL, a, car.odometerKm); refresh() },
                    modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) { Text("%,d".format(a), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
            }
        }
        val last = engine.expenses.lastOrNull()
        if (last != null) {
            Spacer(Modifier.height(8.dp))
            engine.expenses.takeLast(2).forEach { e ->
                Text("· ${e.type.label} ${won(e.amount.toLong())} (${e.riderIds.size}명)", color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            SmallButton("마지막 입력 취소 (${last.type.label} ${won(last.amount.toLong())})", Modifier.fillMaxWidth()) {
                engine.removeLastExpense(); refresh()
            }
        }
    }
}

/** 지금까지 쓴 돈 전체 (1인당 금액은 정산 화면에서만) */
@Composable
fun RunningTotal(engine: TripEngine, car: CarSource, big: Boolean) {
    val p = remember(car.odometerKm, engine.expenses.size, engine.onboardIds.size) {
        runCatching { engine.previewSettlement(car.odometerKm, car.batteryPct) }.getOrNull()
    } ?: return
    val energy = if (engine.settings.fuelType == FuelType.GASOLINE) "기름값" else "충전비"
    Column(horizontalAlignment = if (big) Alignment.CenterHorizontally else Alignment.Start) {
        Text("지금까지 총", fontSize = if (big) 16.sp else 14.sp, color = Muted)
        Text(won(p.totalCost), fontSize = if (big) 34.sp else 24.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("$energy ${won(p.totalEnergyCost)} + 톨비·주차 ${won(p.totalExpense.toLong())}", fontSize = 13.sp, color = Muted)
    }
}

// ---------------------------------------------------------------------------
// ③-1 주행 중 화면 (기어 D): 큰 좌석 그림 + 버튼만, 글자 입력은 막음
// ---------------------------------------------------------------------------
@Composable
fun DrivingScreen(engine: TripEngine, car: CarSource, marks: SeatMarks, refresh: () -> Unit) {
    var dialogSeat by remember { mutableStateOf<String?>(null) }
    val onboard = engine.onboardIds.size
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.width(250.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("주행 중", fontSize = 20.sp, color = Accent, fontWeight = FontWeight.Bold)
            Text(km(car.odometerKm - engine.startKm), fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("탑승 ${onboard}명", fontSize = 24.sp, color = Ink)
            Spacer(Modifier.height(18.dp))
            RunningTotal(engine, car, big = true)
        }
        SeatMap(engine, car, marks, SeatMode.DRIVING, Modifier.weight(1f).fillMaxHeight()) { dialogSeat = it.seat }
        Column(Modifier.width(320.dp)) {
            QuickTollPanel(engine, car, refresh)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFE5E7EB), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⌨", fontSize = 24.sp, color = Muted)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("글자 입력은 잠시 막혀 있어요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Muted)
                    Text("이름·금액 입력은 정차(P) 후에", fontSize = 14.sp, color = Muted)
                }
            }
        }
    }
    dialogSeat?.let { SeatDialogFor(it, SeatMode.DRIVING, engine, car, marks, refresh) { dialogSeat = null } }
}

// ---------------------------------------------------------------------------
// ③-2 정차 중 화면 (기어 P): 큰 좌석 그림으로 탑승·하차, 비용 입력, 도착
// ---------------------------------------------------------------------------
@Composable
fun StoppedScreen(engine: TripEngine, car: CarSource, marks: SeatMarks, refresh: () -> Unit, onFinish: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var dialogSeat by remember { mutableStateOf<String?>(null) }
    // 좌석이 없는 탑승자 (센서 없이 이름만 넣은 사람) → 목록에서 하차
    val noSeat = engine.riders.filter { it.id in engine.onboardIds && it.seat == null }
    val needCheck = Seats.TRACKED.count { seatInfo(it, engine, car, marks).state.let { s -> s == SeatState.UNKNOWN || s == SeatState.LEFT || s == SeatState.AWAY } }
    var arriveOpen by remember { mutableStateOf(false) }

    // 운전자 교대 감지: 정차 중 운전석이 비었다가 다시 사람이 앉음 → "운전자가 바뀌었나요?"
    val drvOff = marks.offKm[Seats.DRIVER]
    val drvOn = marks.onKm[Seats.DRIVER]
    val curDriver = engine.riders.firstOrNull { it.isDriver && it.id in engine.onboardIds }
    val swapSuspect = curDriver != null && car.seats[Seats.DRIVER] == true && drvOff != null && drvOn != null &&
        drvOn >= drvOff && drvOff > engine.startKm && car.odometerKm - drvOff < AWAY_KM &&
        marks.swapAsked[Seats.DRIVER] != drvOn

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title("정차 중 · ${km(car.odometerKm - engine.startKm)}")
                Spacer(Modifier.width(12.dp))
                Text("탑승 ${engine.onboardIds.size}명", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink,
                    modifier = Modifier.background(Line, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
            Text(
                if (needCheck > 0) "확인할 자리가 ${needCheck}곳 있어요 · 좌석을 눌러 주세요. 앉은/내린 지점부터 계산돼요."
                else "좌석을 누르면 탑승·하차·이름을 바꿀 수 있어요.",
                fontSize = 16.sp, color = if (needCheck > 0) Warn else Muted, modifier = Modifier.padding(top = 4.dp)
            )
            if (engine.riders.any { it.id in engine.onboardIds && (it.name.startsWith("손님") || it.name == "운전자") }) {
                Text(
                    "자동 출발했어요 · '손님' 자리를 눌러 이름을 바꿀 수 있어요",
                    color = Accent, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp).background(AccentSoft, RoundedCornerShape(8.dp)).padding(8.dp)
                )
            }
            SeatMap(engine, car, marks, SeatMode.STOPPED, Modifier.weight(1f).fillMaxWidth()) { dialogSeat = it.seat }
            if (noSeat.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("좌석 없이 탑승:", fontSize = 14.sp, color = Muted)
                noSeat.forEach { r ->
                    SmallButton("${r.name} 하차") { engine.alight(r.id, car.odometerKm, car.batteryPct); refresh() }
                }
            }
        }
        Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Panel(Modifier.fillMaxWidth()) { RunningTotal(engine, car, big = false) }
            Spacer(Modifier.height(10.dp))
            QuickTollPanel(engine, car, refresh)
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Text("직접 입력", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                NumberField("금액 (원)", amount, { amount = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpenseType.entries.forEach { t ->
                        SmallButton(t.label, Modifier.weight(1f), enabled = (amount.toIntOrNull() ?: 0) > 0) {
                            engine.addExpense(t, amount.toInt(), car.odometerKm); amount = ""; refresh()
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Line)
                // 좌석 센서 없이 탄 사람 (예: 센서 없는 자리)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("좌석 없이 탑승 (이름)") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    SmallButton("탑승", enabled = newName.isNotBlank()) {
                        engine.board(newName, car.odometerKm, car.batteryPct); newName = ""; refresh()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("기어를 D로 바꾸면 주행 화면으로 바뀌어요", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            BigButton("도착 · 정산하기", Modifier.fillMaxWidth()) { arriveOpen = true }
        }
    }
    dialogSeat?.let { SeatDialogFor(it, SeatMode.STOPPED, engine, car, marks, refresh) { dialogSeat = null } }
    if (arriveOpen) ArriveDialog(engine, onDismiss = { arriveOpen = false }) { pays ->
        engine.driverPays = pays
        arriveOpen = false
        onFinish()
    }
    if (swapSuspect && dialogSeat == null && !arriveOpen) {
        val done = { marks.swapAsked[Seats.DRIVER] = drvOn!!; refresh() }
        // 자리가 빈 사람(운전석으로 옮겼을 가능성이 큰 사람)을 먼저
        val candidates = engine.riders.filter { it.id in engine.onboardIds && !it.isDriver }
            .sortedBy { r -> if (r.seat != null && seatInfo(r.seat, engine, car, marks).state.let { it == SeatState.AWAY || it == SeatState.LEFT }) 0 else 1 }
        val owner = engine.riders.firstOrNull { it.id == engine.ownerId }
        SeatDialog(
            "운전자가 바뀌었나요?",
            "정차 중에 운전석에 다시 사람이 앉았어요.\n교대했다면 새 운전자를 골라 주세요. 이 지점(${km(car.odometerKm - engine.startKm)})부터 새 운전자로 기록돼요." +
                (owner?.let { "\n송금은 계속 차주 ${it.name} 님이 받아요." } ?: ""),
            showName = false, state = SeatState.ONBOARD,
            actions = candidates.mapIndexed { i, r ->
                SeatAction("${r.name} 님이 운전해요", primary = i == 0) { _ -> swapDriver(engine, r.id, car) }
            } + SeatAction("아니요, 그대로 ${curDriver!!.name} 님") { _ -> },
            onDismiss = done
        )
    }
}

/** 도착할 때: 운전자도 비용을 나눠 낼지 확인 (기본: 운전자 제외) */
@Composable
fun ArriveDialog(engine: TripEngine, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var pays by remember { mutableStateOf(false) }
    val driver = engine.riders.firstOrNull { it.isDriver }
    @Composable
    fun Choice(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .background(if (selected) AccentSoft else Line, RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(sub, fontSize = 14.sp, color = Muted)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = CardWhite,
        title = { Text("운전자도 비용을 나눠 낼까요?", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("차와 운전을 맡은 사람은 보통 기름값을 내지 않아서, 기본은 '운전자 제외'예요.", fontSize = 15.sp, color = Muted)
                Choice(!pays, "운전자 제외 (기본)",
                    if (engine.driverChanged) "운전자가 바뀌었으면 각자 운전한 구간에서만 빠져요"
                    else "${driver?.name ?: "운전자"} 님은 빼고 나머지가 나눠요") { pays = false }
                Choice(pays, "운전자도 같이 내기", "렌터카·공용 차량처럼 모두 같이 낼 때") { pays = true }
                Text("운전자 혼자 달린 구간은 나눌 사람이 없어서 정산에서 빠져요.", fontSize = 13.sp, color = Muted)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(pays) }, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(12.dp)) {
                Text("정산하기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", fontSize = 16.sp) } }
    )
}

// ---------------------------------------------------------------------------
// ④ 정산 결과 화면
// ---------------------------------------------------------------------------
@Composable
fun ResultScreen(engine: TripEngine, refresh: () -> Unit = {}, onNewTrip: () -> Unit) {
    var ver by remember { mutableIntStateOf(0) }          // 비용을 추가하면 다시 계산
    var addOpen by remember { mutableStateOf(false) }
    val result = remember(ver) { engine.settlement() }
    val energyLabel = if (engine.settings.fuelType == FuelType.GASOLINE) "기름값" else "충전비"
    val link = engine.settings.transferLink
    // QR ① 정산표 (찍으면 글자로 보임, 단톡방에 붙여넣기용)
    val owner = result.owner
    val qrText = remember(ver) {
        buildString {
            appendLine("[N빵 드라이브 정산]")
            appendLine("총 ${km(result.totalKm)} / 합계 ${won(result.roundedSum)}")
            result.people.forEach { p ->
                if (p.rider.id == result.ownerId)
                    appendLine("${p.rider.name}(차주) 받을 돈 ${won(result.ownerReceives)}" + if (p.total > 0) " (본인 몫 ${won(p.total)} 차감)" else "")
                else appendLine("${p.rider.name} ${won(p.total)}")
            }
            owner?.let { appendLine("받는 사람: ${it.rider.name}") }
            if (link.isNotBlank()) append("송금: $link")
        }
    }
    val qr = remember(qrText) { makeQr(qrText, 400) }
    // QR ② 송금 링크 (찍으면 바로 송금 화면으로)
    val linkQr = remember { if (link.isNotBlank()) makeQr(link, 400) else null }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
            Title("정산 완료")
            Sub("총 ${km(result.totalKm)} · $energyLabel ${won(result.totalEnergyCost)} · 톨비 등 ${won(result.totalExpense.toLong())}")
            val s = engine.settings
            Text(
                if (s.fuelType == FuelType.GASOLINE) "연비 ${s.kmPerLiter}km/L (${engine.fuelSource}) · 기름값 ${"%,d".format(s.fuelPricePerLiter)}원/L"
                else "전비 ${s.kmPerKwh}km/kWh (${engine.fuelSource}) · 충전 단가 ${s.pricePerKwh}원/kWh",
                color = Accent, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)
            )
            // 운전자 포함 여부 (도착할 때 고른 값, 여기서도 바꿀 수 있음)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (engine.driverPays) "운전자도 같이 냄" else "운전자 제외") +
                        (if (result.driverChanged) " · 운전자 교대: 각자 운전한 구간 기준" else "") +
                        (if (result.soloKm > 0.0) " · 운전자 혼자 ${km(result.soloKm)}(${won(result.soloCost)})는 제외" else ""),
                    color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f)
                )
                Text("운전자도 내기", fontSize = 14.sp, color = Muted)
                Spacer(Modifier.width(6.dp))
                Switch(checked = engine.driverPays, onCheckedChange = { engine.driverPays = it; ver++; refresh() })
            }
            if (s.fuelType == FuelType.EV) {
                val used = engine.batteryUsedPct
                Text(
                    if (used > 0.0)
                        "배터리 실측 (EvBattery SDK): ${"%.0f".format(engine.tripStartBatteryPct)}% → ${"%.0f".format(engine.lastBatteryPct)}% · " +
                            "${"%.1f".format(engine.batteryUsedKwh)}kWh 사용 ≈ ${won(engine.batteryUsedKwh * s.pricePerKwh)} (참고)"
                    else "배터리 실측: 사용량 변화 없음 (참고용, 계산은 전비 기준)",
                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            // 표 머리
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("이름", Modifier.weight(1.2f), color = Muted)
                Text("탄 거리", Modifier.weight(1f), color = Muted)
                Text(energyLabel, Modifier.weight(1f), color = Muted)
                Text("톨비 등", Modifier.weight(1f), color = Muted)
                Text("낼 돈", Modifier.weight(1.2f), color = Muted)
            }
            HorizontalDivider(color = Line)
            LazyColumn(Modifier.weight(1f)) {
                items(result.people) { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isOwner = p.rider.id == result.ownerId
                        Column(Modifier.weight(1.2f)) {
                            Text(p.rider.name + if (isOwner) " (차주)" else "", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            if (p.driveKm > 0.0) Text("운전 ${km(p.driveKm)}", fontSize = 13.sp, color = Muted)
                        }
                        Text(km(p.rideKm), Modifier.weight(1f), fontSize = 17.sp)
                        Text(won(p.energyShare), Modifier.weight(1f), fontSize = 17.sp)
                        Text(won(p.expenseShare), Modifier.weight(1f), fontSize = 17.sp)
                        if (isOwner) Column(Modifier.weight(1.2f)) {
                            Text("받을 돈 ${won(result.ownerReceives)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                            if (p.total > 0) Text("본인 몫 ${won(p.total)} 차감", fontSize = 13.sp, color = Muted)
                        }
                        else Text(won(p.total), Modifier.weight(1.2f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                    HorizontalDivider(color = Line)
                }
            }
            val adj = result.adjusted
            Text(
                "실제 비용 ${"%,.1f".format(result.totalCost)}원 → 10원 단위 합계 ${won(result.roundedSum)}" +
                    (adj?.let { " · 차액 ${if (it.adjust > 0) "+" else ""}${it.adjust}원은 ${it.rider.name} 몫에서 맞춤" } ?: ""),
                color = Muted, fontSize = 14.sp
            )
        }
        Column(Modifier.width(if (linkQr != null) 460.dp else 260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Panel(Modifier.fillMaxWidth()) {
                Text("폰으로 찍어서 정산", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(bitmap = qr, contentDescription = "정산표 QR", modifier = Modifier.size(200.dp))
                        Text("① 정산표", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    if (linkQr != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(bitmap = linkQr, contentDescription = "송금 QR", modifier = Modifier.size(200.dp))
                        Text("② ${owner?.rider?.name ?: "차주"}에게 송금", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                }
                if (linkQr == null) {
                    Spacer(Modifier.height(6.dp))
                    Text("설정에서 송금 링크를 넣으면 송금 QR이 따로 생겨요", color = Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            // 빠뜨린 비용 (주차비·톨비 등) — 도착해서 한 번에
            Panel(Modifier.fillMaxWidth()) {
                Text("빠뜨린 비용이 있나요?", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("주차비·톨비를 여기서 넣고 나눌 사람을 골라요", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                BigButton("+ 비용 추가", Modifier.fillMaxWidth()) { addOpen = true }
                engine.expenses.lastOrNull()?.let { last ->
                    Spacer(Modifier.height(6.dp))
                    SmallButton("마지막 입력 취소 (${last.type.label} ${won(last.amount.toLong())})", Modifier.fillMaxWidth()) {
                        engine.removeLastExpense(); ver++; refresh()
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            BigButton("새 여행 시작", Modifier.fillMaxWidth(), onClick = onNewTrip)
        }
    }
    if (addOpen) AddExpenseDialog(engine, onDismiss = { addOpen = false }) { ver++; refresh() }
}

/**
 * 도착 후 비용 추가 창: 종류·금액 → 낸 지점(선택, 그때 탄 사람 자동 체크) → 나눌 사람 체크 → 추가
 */
@Composable
fun AddExpenseDialog(engine: TripEngine, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val total = ((engine.segments.lastOrNull()?.endKm ?: engine.startKm) - engine.startKm).coerceAtLeast(0.0)
    var type by remember { mutableStateOf(ExpenseType.PARKING) }
    var amount by remember { mutableStateOf("") }
    var useKm by remember { mutableStateOf(false) }          // 낸 지점 고르기
    var atTrip by remember { mutableStateOf(total.toFloat()) }
    val everyone = engine.riders.map { it.id }.toSet()
    var picked by remember { mutableStateOf(everyone) }
    val amt = amount.toIntOrNull() ?: 0
    val drvId = engine.driverAt(engine.startKm + (if (useKm) atTrip.toDouble() else total))
    val pay = engine.payers(picked, drvId)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = CardWhite,
        title = { Text("빠뜨린 비용 추가", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpenseType.entries.forEach { t ->
                        if (t == type) Button(onClick = {}, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(10.dp)) { Text(t.label, fontSize = 16.sp) }
                        else SmallButton(t.label, Modifier.weight(1f)) { type = t }
                    }
                }
                NumberField("금액 (원)", amount, { amount = it }, Modifier.fillMaxWidth())

                // 낸 지점 → 그때 타고 있던 사람 자동 체크
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("낸 지점 고르기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (useKm) "그때 타고 있던 사람이 자동으로 체크돼요" else "끄면 직접 사람을 골라요", color = Muted, fontSize = 13.sp)
                    }
                    Switch(checked = useKm, onCheckedChange = {
                        useKm = it
                        picked = if (it) engine.ridersAt(engine.startKm + atTrip) else everyone
                    })
                }
                if (useKm && total > 0.0) {
                    Text("${km(atTrip.toDouble())} 지점", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Accent)
                    Slider(
                        value = atTrip, onValueChange = {
                            atTrip = it
                            picked = engine.ridersAt(engine.startKm + it)
                        },
                        valueRange = 0f..total.toFloat()
                    )
                }

                // 실제로 돈을 내는 사람 수 (운전자 제외면 그때 운전한 사람은 빠짐)
                Text("나눌 사람 (${pay.size}명)" + if (pay.size < picked.size) " · 함께 탄 ${picked.size}명 중" else "",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
                engine.riders.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (r.id in picked) picked - r.id else picked + r.id }
                            .background(if (r.id in picked) AccentSoft else Line, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = r.id in picked, onCheckedChange = { c -> picked = if (c) picked + r.id else picked - r.id })
                        Text(r.name + if (r.id == drvId) " (그때 운전)" else "", fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (amt > 0 && r.id in pay) Text("≈ ${won(amt.toDouble() / pay.size)}", color = Muted, fontSize = 14.sp)
                        else if (r.id in picked && r.id !in pay && pay.isNotEmpty()) Text("운전 중이라 안 냄", color = Muted, fontSize = 14.sp)
                    }
                }
                if (picked.isNotEmpty() && pay.isEmpty())
                    Text("운전자 혼자라 나눌 사람이 없어요 · 다른 사람도 체크해 주세요", color = Warn, fontSize = 13.sp)
                else if (pay.size < picked.size)
                    Text("'운전자 제외'라 그때 운전한 ${engine.riders.firstOrNull { it.id == drvId }?.name ?: "운전자"} 님은 빠지고 나머지가 나눠요", color = Muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    engine.addExpenseFor(type, amt, engine.startKm + (if (useKm) atTrip.toDouble() else total), picked)
                    onAdded(); onDismiss()
                },
                enabled = amt > 0 && pay.isNotEmpty(),
                modifier = Modifier.height(52.dp), shape = RoundedCornerShape(12.dp)
            ) { Text("추가하고 다시 계산", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", fontSize = 16.sp) } }
    )
}

// ---------------------------------------------------------------------------
// 좌석 감지 안내 카드
// ---------------------------------------------------------------------------
@Composable
fun SeatHint(
    message: String,
    actionText: String,
    actionEnabled: Boolean,
    secondText: String? = null,
    onSecond: () -> Unit = {},
    choices: List<Rider> = emptyList(),          // 이미 등록했지만 자리가 없는 사람 → 눌러서 이 자리에 연결
    onChoice: (Rider) -> Unit = {},
    onAction: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(AccentSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("좌석 감지", color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("  $message", color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        choices.forEach { r ->
            SmallButton("${r.name} 자리") { onChoice(r) }
            Spacer(Modifier.width(8.dp))
        }
        if (secondText != null) {
            SmallButton(secondText, onClick = onSecond)
            Spacer(Modifier.width(8.dp))
        }
        Button(onClick = onAction, enabled = actionEnabled, shape = RoundedCornerShape(10.dp)) { Text(actionText) }
    }
}

// ---------------------------------------------------------------------------
// 진짜 차량 신호 상태 막대
// ---------------------------------------------------------------------------
@Composable
fun SignalBar(car: CarSource) {
    Row(
        Modifier.fillMaxWidth().background(AccentSoft).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(car.signalSummary(), color = Accent, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(
            Seats.TRACKED.joinToString("  ") { (if (car.seats[it] == true) "●" else "○") + Seats.short(it) },
            color = Accent, fontSize = 15.sp
        )
    }
}

// ---------------------------------------------------------------------------
// 모의 차량 막대 (SDK 없이 테스트용)
// ---------------------------------------------------------------------------
@Composable
fun DevVehicleBar(car: FakeVehicle) {
    Row(
        Modifier.fillMaxWidth().background(WarnSoft).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("모의 차량", color = Warn, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Gear.entries.forEach { g ->
            if (car.gear == g)
                Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Warn)) { Text(g.name) }
            else
                OutlinedButton(onClick = { car.gear = g }) { Text(g.name) }
        }
        OutlinedButton(onClick = { car.drive(1.0) }, enabled = car.gear == Gear.D) { Text("+1km") }
        OutlinedButton(onClick = { car.drive(10.0) }, enabled = car.gear == Gear.D) { Text("+10km") }
        Text("좌석", color = Warn, fontSize = 15.sp)
        Seats.TRACKED.forEach { seat ->
            val on = car.seats[seat] == true
            if (on)
                Button(onClick = { car.toggleSeat(seat) }, colors = ButtonDefaults.buttonColors(containerColor = Warn)) { Text(Seats.short(seat)) }
            else
                OutlinedButton(onClick = { car.toggleSeat(seat) }) { Text(Seats.short(seat)) }
        }
        Spacer(Modifier.weight(1f))
        Text("${"%,.1f".format(car.odometerKm)}km · ${"%.1f".format(car.batteryPct)}%", color = Warn, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------
// 차 좌석 센서 표 (실제 차량 신호일 때): 착좌 센서와 안전벨트를 따로 보여줌
// ---------------------------------------------------------------------------
@Composable
fun SeatSensorPanel(car: PleosCar) {
    fun mark(b: Boolean?) = when (b) { true -> "●"; false -> "○"; null -> "–" }
    Panel(Modifier.fillMaxWidth()) {
        Text("차 좌석 센서", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("", Modifier.weight(1.4f))
            Text("착좌", Modifier.weight(1f), color = Muted, fontSize = 16.sp)
            Text("벨트", Modifier.weight(1f), color = Muted, fontSize = 16.sp)
        }
        Seats.TRACKED.forEach { seat ->
            val (occ, belt) = car.seatRaw(seat)
            Row(Modifier.fillMaxWidth()) {
                Text(Seats.label(seat), Modifier.weight(1.4f), fontSize = 18.sp)
                Text(mark(occ), Modifier.weight(1f), fontSize = 20.sp, color = Accent)
                Text(mark(belt), Modifier.weight(1f), fontSize = 20.sp, color = Accent)
            }
        }
    }
}
