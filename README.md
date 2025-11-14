# idkit

Kotlin/JVM용 간단하고 빠른 ID 생성기 컬렉션.

현재 제공 기능:
- Snowflake(Long, 64비트) — 트위터 Snowflake 알고리즘과 동일한 41/5/5/12 비트 구성의 단조 증가 ID
- Flake(Long, 64비트) — 비트 수와 에폭, 분해능을 사용자 정의할 수 있는 Snowflake 파생 구현
- UUID v7(Java UUID) — 시간 정렬 가능한(UUID-sortable) v7 구현

프로젝트 메타
- 언어/런타임: Kotlin on JVM (JDK 11)
- Kotlin: 1.9.25, Gradle Kotlin DSL
- 테스트: JUnit 5
- 그룹/아티팩트/버전: `io.github.dornol:idkit:1.1.0`

## 설치
Maven Central에 게시된 아티팩트를 사용하세요.

Gradle(Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.dornol:idkit:1.1.0")
}
```

Gradle(Groovy):
```groovy
dependencies {
    implementation 'io.github.dornol:idkit:1.1.0'
}
```

Maven:
```xml
<dependency>
  <groupId>io.github.dornol</groupId>
  <artifactId>idkit</artifactId>
  <version>1.1.0</version>
</dependency>
```

## 빠른 시작
로컬에서 바로 빌드/테스트:
```bash
# Windows
./gradlew.bat build
# macOS/Linux
./gradlew build

# 테스트만 실행
./gradlew.bat test  # Windows
./gradlew test      # macOS/Linux
```

## 사용법
### 1) Snowflake(Long)
스레드 안전하며, 같은 인스턴스에서 생성되는 ID는 단조 증가(엄밀히는 비감소)합니다.

```kotlin
fun main() {
    // 워커/데이터센터 ID는 0..31 범위
    val gen = io.github.dornol.idkit.flake.SnowflakeIdGenerator(workerId = 1, dataCenterId = 2)

    val id: Long = gen.nextId()
    println("snowflake = $id")
}
```

Snowflake의 비트 구성(고정):
- timestamp(41) | dataCenterId(5) | workerId(5) | sequence(12)
- 같은 밀리초에 4096개를 넘기면 다음 밀리초로 롤오버합니다.
- 기본 에폭(`epochStart`)은 UNIX epoch(1970-01-01T00:00:00Z)입니다. 커스텀 에폭을 지정할 수 있습니다.

```kotlin
val customEpoch = java.time.LocalDateTime.of(2020, 1, 1, 0, 0)
    .atZone(java.time.ZoneId.of("UTC")).toInstant()
val gen = io.github.dornol.idkit.flake.SnowflakeIdGenerator(
    workerId = 0,
    dataCenterId = 0,
    epochStart = customEpoch
)
```

### 2) Flake(Long, 커스터마이즈 가능)
`FlakeIdGenerator`는 비트 수와 타임스탬프 분해능을 자유롭게 조절할 수 있는 Snowflake 파생 구현입니다.

```kotlin
val gen = io.github.dornol.idkit.flake.FlakeIdGenerator(
    timestampBits = 41,        // 타임스탬프 비트 수
    datacenterIdBits = 5,      // 데이터센터 비트 수(1..5)
    workerIdBits = 5,          // 워커 비트 수(>0)
    timestampDivisor = 1L,     // 밀리초를 이 값으로 나눠 저장(예: 10 -> 10ms 단위)
    epochStart = java.time.Instant.EPOCH,
    datacenterId = 1,
    workerId = 1,
)
val id: Long = gen.nextId()
```

- 전체 비트 합(미사용 1비트 포함)은 63을 넘을 수 없습니다.
- `timestampDivisor`를 키우면 타임스탬프 범위를 줄이는 대신 분해능이 거칠어집니다(예: 10은 10ms 틱).
- 시간이 역행하면 마지막 타임스탬프 이상으로 보정하여 단조성을 유지합니다.

### 3) UUID v7(java.util.UUID)
시간 기반 상위 비트 + 랜덤 하위 비트 조합의 UUID v7을 생성합니다. IETF variant(0b10), version 7을 올바르게 설정합니다.

```kotlin
fun main() {
    val gen = io.github.dornol.idkit.uuidv7.UuidV7IdGenerator()
    val u: java.util.UUID = gen.nextId()
    println("uuid7 = $u")
}
```

이 구현은 멀티스레드 환경에서 시간 역행으로 인한 정렬 깨짐을 완화하기 위해, 마지막에 사용한 밀리초 타임스탬프를 `AtomicLong`으로 CAS 업데이트해 단조 증가(동순 이상)를 보장합니다.

## 공통 인터페이스
모든 생성기는 `IdGenerator<T>`를 구현합니다.

```kotlin
interface IdGenerator<T> {
    fun nextId(): T
}
```

## 동작 특성 및 주의사항
- 스레드 안전
  - Snowflake/Flake: `nextId()`가 `@Synchronized`
  - UUID v7: 내부 CAS로 타임스탬프 역행 방지
- 시계 의존성
  - 시스템 시간이 비정상적으로 역행하면 Snowflake/Flake는 대기 또는 보정, UUID v7은 마지막 타임스탬프 이상으로 고정합니다.
- 구성 제한
  - Snowflake의 `workerId`, `dataCenterId`는 0..31 범위를 벗어나면 `IllegalArgumentException`

## 테스트
포함된 JUnit 5 테스트로 핵심 동작을 검증합니다.
- `src/test/kotlin/io/github/dornol/idkit/flake/SnowflakeIdGeneratorTest.kt`
- `src/test/kotlin/io/github/dornol/idkit/flake/FlakeIdGeneratorTest.kt`
- `src/test/kotlin/io/github/dornol/idkit/uuidv7/UuidV7IdGeneratorTest.kt`

실행:
```bash
./gradlew.bat test  # Windows
./gradlew test      # macOS/Linux
```

## 성능 팁
- 가능한 한 생성기 인스턴스를 재사용(싱글턴)하여 동기화/원자 연산 비용을 최소화하세요.
- 시스템 시계(NTP 동기화)를 안정적으로 유지하세요.
- 고 QPS에서 Snowflake는 밀리초당 4096개의 상한을 가집니다(시퀀스 12비트).

## 로깅
이 라이브러리는 SLF4J API를 사용합니다. 바인딩이 없으면 NOP 로거로 동작합니다. 원하는 경우 `slf4j-simple` 또는 `logback-classic` 등을 추가하세요.

## 배포/퍼블리싱(프로젝트 유지보수자를 위한 메모)
이 저장소는 Vanniktech Maven Publish로 Central Publishing Portal에 업로드하도록 구성되어 있습니다. 필요한 경우 `~/.gradle/gradle.properties`에 다음 키를 설정하세요.

```
mavenCentralUsername=YOUR_CENTRAL_TOKEN
mavenCentralPassword=YOUR_CENTRAL_SECRET
# 선택: 서명 키(현재 스크립트는 signAllPublications)
signing.keyId=...
signing.password=...
signing.secretKeyRingFile=/path/to/secring.gpg
```

업로드:
```bash
./gradlew publish
```

자세한 POM/SCM/License 메타데이터는 `build.gradle.kts`의 `mavenPublishing` 블록을 확인하세요.

## 라이선스
MIT License — 자세한 내용은 루트의 `LICENSE` 파일을 참조하세요.

## 변경 이력
- 2025-11-14: `FlakeIdGenerator` 공개 및 테스트 추가, README 갱신.
- 2025-11-01: UUID v7 생성기 추가, README 전면 개편, 좌표/버전 갱신(1.1.0).
- 2025-10-25: 초기 Snowflake 구현 및 테스트, LICENSE 추가.