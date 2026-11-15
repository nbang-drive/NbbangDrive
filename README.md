# N빵 드라이브

> 여러 사람이 차를 나눠 타고 이동할 때, 좌석 센서와 주행거리·전비/연비 데이터로 **누가 어느 구간을 탔는지 기록하고 기름값·충전비·통행료·주차비를 탄 만큼만 자동으로 나눠 주는** Pleos Connect 차량용 동승 비용 정산 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. (팀명: 정둘김둘 / JeongdulKimdul)

---

## 주요 기능

| 단계 | 화면 | 하는 일 |
|---|---|---|
| 1 | 설정 | 차량 종류(전기차/내연기관), 전비·연비, 단가 설정 |
| 2 | 출발 | 좌석 센서로 감지된 자리에 탑승자 이름 등록, 운전자(차주) 지정 |
| 3 | 여행 중 | 정차(P) 때마다 좌석 변화를 감지해 중간 탑승·하차 확인, 운전 교대 감지, 통행료·주차비 추가 |
| 4 | 정산 | 구간별로 탄 거리만큼 비용 분배, 차주가 받을 금액 계산, QR로 정산표 공유 |

- **구간 정산**: 탑승자 구성이 바뀔 때마다 구간을 끊고, 구간별 에너지 비용을 그 구간에 탄 사람끼리 나눔
- **운전자 분담 제외(기본값)**: 도착 시 "운전자도 낼까요?"를 확인하고, 기본은 운전자 제외
- **운전 교대**: 정차 중 운전석이 비었다가 다시 채워지면 교대를 확인하고, 구간마다 그때의 운전자를 기록
- **운전자 혼자 남은 구간 제외**: 나눌 사람이 없는 구간은 정산에서 빠짐
- **주행 중 조작 제한**: 기어가 D일 때는 확인 창을 띄우지 않고, 정차(P) 후에 처리
- **상태 저장**: 앱이 꺼졌다 켜져도 진행 중인 이동 기록·탑승자·운전 교대 기록 유지

## 사용한 Pleos SDK

| SDK | 버전 | 사용처 |
|---|---|---|
| Vehicle SDK | 2.3.2 | 기어, 주행거리(Odometer), 배터리(SOC)·연비, 좌석 착좌·안전벨트 |
| SpeechToText SDK (Gleo AI) | 2.1.3.2 | 탑승자 이름을 말로 입력 (선택 기능, 아래 "음성 입력 키" 참고) |

선언한 권한: `CAR_DRIVE`, `CAR_ENERGY`, `CAR_SEATS`, `CAR_MILEAGE`, `STT_SERVICE`, `INTERNET`

---

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect Emulator v2.1.0** (Android 14, API 34, x86_64, android-automotive) |
| 화면 규격 | **2560 × 1440 px, 160 dpi** |
| 차량 상태 조작 | Pleos Connect Virtual Console |
| 개발 도구 | Android Studio, Gradle 9.6.0, AGP 9.5.1, Kotlin 2.2.10, JDK 17 |
| SDK 버전 | minSdk 28 / targetSdk 37 / compileSdk 37 |

## APK 위치

- **GitHub Release 링크: https://github.com/nbang-drive/NbbangDrive/releases/tag/v1.0**
- 파일명: `JeongdulKimdul_NbbangDrive_release.apk` (release 빌드)
- APK 용량(약 25.3MB)이 GitHub 웹 업로드 제한(25MB)을 넘어 저장소 `apk/` 폴더 대신 GitHub Releases에 등록했습니다.
- 공개용 APK이므로 **API 키가 들어 있지 않습니다.** 음성 입력(마이크 버튼)만 꺼지고 나머지 기능은 모두 동작합니다.

---

## 실행 방법

### A. APK로 바로 실행
1. Pleos Connect Emulator v2.1.0을 실행합니다.
2. 위 Release 링크에서 받은 APK를 에뮬레이터 화면에 끌어다 놓거나 `adb install JeongdulKimdul_NbbangDrive_release.apk`로 설치합니다.
3. 앱 목록에서 **N빵 드라이브**를 실행합니다.

### B. 소스에서 빌드
1. 이 저장소를 받아 Android Studio에서 엽니다.
2. Gradle Sync 후, 실행 대상을 Pleos Connect Emulator로 골라 ▶ Run 합니다.

### 시연 순서 (Virtual Console 사용)
1. **설정**: 차량 종류와 단가를 확인합니다.
2. **출발**: Virtual Console에서 좌석 착좌를 켜면 해당 자리가 "감지됨"으로 바뀝니다. 자리마다 이름을 넣고 출발합니다.
3. **여행 중**: 기어를 D로 바꾸고 주행거리를 늘린 뒤, P로 바꿔 정차합니다. 정차 중 좌석을 비우거나 채우면 하차·탑승 확인이 뜹니다. 운전석을 비웠다가 다시 채우면 운전 교대를 확인합니다.
4. **정산**: 도착을 누르면 운전자 분담 여부를 확인하고, 사람별 금액과 차주가 받을 금액, QR 정산표를 보여 줍니다.

### 음성 입력 키 (선택)
음성 입력은 Pleos Playground에서 발급받은 앱 키가 필요합니다. 키는 저장소에 넣지 않았습니다.
직접 테스트하려면 프로젝트 루트의 `local.properties`에 아래 두 줄을 넣고 **debug** 빌드로 실행합니다.

```properties
PLEOS_CLIENT_ID=발급받은_Client_ID
PLEOS_CLIENT_SECRET=발급받은_Client_Secret
```

키가 없으면 마이크 버튼이 숨겨지고, 이름은 직접 입력으로 넣으면 됩니다.

---

## 단위 테스트

정산 엔진(`TripEngine`)은 화면·차량 연동과 분리되어 있어 단독으로 테스트할 수 있습니다.

```bash
./gradlew test
```

`app/src/test/java/com/example/project1/TripEngineTest.kt` — 13개 상황 검증
(중간 탑승·하차, 운전자 분담 제외, 전기차 정산, 확인 순서 뒤바뀜, 도착 후 비용 추가, 운전자 교대, 운전자 혼자 남은 구간, 반올림 차액, 저장·복원 등)

## 프로젝트 구조

```
app/src/main/java/com/example/project1/
├── MainActivity.kt   앱 시작, Vehicle SDK·음성 입력 연결
├── PleosCar.kt       Pleos Vehicle SDK 연동 (기어·주행거리·배터리·좌석)
├── CarSource.kt      차량 데이터 공통 인터페이스
├── FakeVehicle.kt    SDK 연결 실패 시 쓰는 모의 차량
├── TripEngine.kt     구간 기록·정산 로직 (핵심)
├── TripStore.kt      진행 상태 저장·복원
├── Screens.kt        Compose 화면 (설정·출발·여행 중·정산)
├── VoiceInput.kt     Gleo AI 음성 인식 (이름 입력)
├── QrCode.kt         정산표 QR 생성
└── NbbangTheme.kt    색·글꼴
```
