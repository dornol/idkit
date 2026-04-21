# idkit

Kotlin/JVM용 간단하고 빠른 ID 생성기 컬렉션.

현재 제공 기능:
- **Snowflake** (`Long`, 64bit) — 트위터 Snowflake 알고리즘과 동일한 41/5/5/12 비트 구성의 엄격 단조 증가 ID
- **Flake** (`Long`, 64bit) — 비트 수와 에폭, 분해능을 사용자 정의할 수 있는 Snowflake 파생 구현
- **UUID v7** (`java.util.UUID`) — RFC 9562 §6.2 Method 2 기반, 동일 ms 내 단조 증가 보장

## 프로젝트 메타

- 언어/런타임: Kotlin on JVM (JDK 11)
- Kotlin: 2.3.10, Gradle Kotlin DSL
- 테스트: JUnit 5
- 그룹/아티팩트/버전: `io.github.dornol:idkit:2.0.0`

> **1.x 에서 업그레이드 시 주의**: 2.0.0 은 다수의 breaking change 를 포함합니다.
> [CHANGELOG.md](CHANGELOG.md) 의 2.0.0 섹션을 먼저 확인하세요.

## 설치

Maven Central 에 게시된 아티팩트를 사용하세요.

Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.dornol:idkit:2.0.0")
}
```

Gradle (Groovy):
```groovy
dependencies {
    implementation 'io.github.dornol:idkit:2.0.0'
}
```

Maven:
```xml
<dependency>
  <groupId>io.github.dornol</groupId>
  <artifactId>idkit</artifactId>
  <version>2.0.0</version>
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

### 1) Snowflake (`Long`)

스레드 안전하며, 동일 인스턴스에서 생성되는 ID 는 엄격 단조 증가합니다.

```kotlin
import io.github.dornol.idkit.flake.SnowflakeIdGenerator

fun main() {
    // workerId, datacenterId 는 0..31 범위 (Int)
    val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2)

    val id: Long = gen.nextId()
    println("snowflake = $id")
}
```

Snowflake 비트 구성 (고정):
- `timestamp(41) | datacenterId(5) | workerId(5) | sequence(12)`
- 동일 ms 에서 4096 개를 넘기면 다음 ms 로 롤오버합니다.
- 기본 에폭은 UNIX epoch (1970-01-01T00:00:00Z). 커스텀 에폭 지정 가능.

```kotlin
import java.time.LocalDateTime
import java.time.ZoneId

val customEpoch = LocalDateTime.of(2020, 1, 1, 0, 0)
    .atZone(ZoneId.of("UTC")).toInstant()

val gen = SnowflakeIdGenerator(
    workerId = 0,
    datacenterId = 0,
    epochStart = customEpoch,
)
```

### 2) Flake (`Long`, 커스터마이즈 가능)

`FlakeIdGenerator` 는 비트 레이아웃과 타임스탬프 분해능을 자유롭게 조절할 수 있는 Snowflake 파생 구현입니다.

```kotlin
import io.github.dornol.idkit.flake.FlakeIdGenerator
import java.time.Instant

val gen = FlakeIdGenerator(
    timestampBits = 41,        // 타임스탬프 비트 수 (>0)
    datacenterIdBits = 5,      // 데이터센터 비트 수 (1..5)
    workerIdBits = 5,          // 워커 비트 수 (1..31)
    timestampDivisor = 1L,     // ms 를 이 값으로 나눠 저장 (예: 10 → 10ms 단위)
    epochStart = Instant.EPOCH,
    datacenterId = 1,          // Int
    workerId = 1,              // Int
)
val id: Long = gen.nextId()
```

제약:
- 전체 비트 합 (미사용 1bit 포함) 은 63 을 넘을 수 없고, 최소 1bit 의 sequence 가 확보되어야 합니다.
- `timestampDivisor` 를 키우면 타임스탬프 표현 범위가 넓어지는 대신 분해능이 거칠어집니다.
- 타임스탬프 필드에는 `(now - epoch) / divisor` 가 정밀하게 저장됩니다 (2.0.0 교정).

### 3) UUID v7 (`java.util.UUID`)

RFC 9562 호환 UUID v7 을 생성합니다. `version = 7`, `variant = 0b10` 을 올바르게 설정합니다.

```kotlin
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import java.util.UUID

fun main() {
    val gen = UuidV7IdGenerator()
    val u: UUID = gen.nextId()
    println("uuid7 = $u")
}
```

단조성 보장 (2.0.0 부터):
- 상위 12bit `rand_a` 위치를 **전용 monotonic counter** 로 사용 (RFC 9562 Method 2).
- `(timestamp:52 | counter:12)` 을 단일 `AtomicLong` 에 패킹하고 CAS 로 원자적으로 갱신합니다.
- 동일 ms 내에서 카운터가 4096 을 초과하면 타임스탬프를 1ms 앞으로 "빌림(borrow)" 하며, 실제 시계가 따라잡으면 자연 복귀합니다.
- 결과적으로 동일 generator 에서 생성된 UUID 는 `mostSignificantBits` 기준 **엄격 단조 증가** 합니다 — DB 인덱스 친화적.

## 공통 인터페이스

모든 생성기는 `IdGenerator<T>` 를 구현합니다.

```kotlin
interface IdGenerator<T> {
    fun nextId(): T
}
```

## 동작 특성 및 주의사항

### 스레드 안전
- **Snowflake / Flake**: `nextId()` 는 `@Synchronized`
- **UUID v7**: 내부 `AtomicLong` + CAS

### 시계 역행 (`System.currentTimeMillis()` 가 이전 관측값보다 작은 값 반환)
- **Snowflake / Flake**: `ClockMovedBackwardsException` (extends `IllegalStateException`) 을 던집니다. 예외 발생 시점에 내부 상태는 변경되지 않으므로, 시계가 회복되면 같은 인스턴스를 계속 사용할 수 있습니다.
  ```kotlin
  try {
      val id = gen.nextId()
  } catch (e: ClockMovedBackwardsException) {
      // e.driftAmount 만큼 역행 감지 — 짧은 백오프 후 재시도 또는 운영 알림
  }
  ```
- **UUID v7**: 이전 타임스탬프를 유지하고 카운터를 증가시켜 단조성을 보장합니다.

### 타임스탬프 소진
Flake/Snowflake 의 `timestampBits` 는 표현 범위가 유한합니다. 범위를 초과하면 `IllegalStateException` 이 발생하며, 시계가 앞으로만 흐르기 때문에 **복구 불가능** 합니다. 더 넓은 `timestampBits` 또는 더 최근의 `epochStart` 로 재구성하세요.

### 구성 제한
- `timestampBits > 0`
- `datacenterIdBits in 1..5`
- `workerIdBits in 1..31`
- `unused(1) + timestamp + datacenter + worker <= 63` (sequence 최소 1bit 보장)

## 테스트

JUnit 5 테스트:
- `src/test/kotlin/io/github/dornol/idkit/flake/SnowflakeIdGeneratorTest.kt`
- `src/test/kotlin/io/github/dornol/idkit/flake/FlakeIdGeneratorTest.kt`
- `src/test/kotlin/io/github/dornol/idkit/uuidv7/UuidV7IdGeneratorTest.kt`

실행:
```bash
./gradlew.bat test  # Windows
./gradlew test      # macOS/Linux
```

## 성능 팁

- 가능한 한 generator 인스턴스를 재사용 (싱글턴) 하여 동기화/원자 연산 비용을 최소화하세요.
- 시스템 시계 (NTP 동기화) 를 안정적으로 유지하세요.
- Snowflake 는 ms 당 4096 개의 sequence 상한을 가집니다.
- UUID v7 은 ms 당 4096 개의 counter 상한이 있으며, 초과 시 타임스탬프를 앞당겨 단조성을 유지합니다 (지속적 초과 부하 시 타임스탬프가 wall clock 보다 앞설 수 있음).

## 로깅

이 라이브러리는 SLF4J API 를 사용합니다. 바인딩이 없으면 NOP 로거로 동작합니다. 필요 시 `slf4j-simple` 또는 `logback-classic` 등을 추가하세요.

## 배포/퍼블리싱 (유지보수자 메모)

Vanniktech Maven Publish 로 Central Publishing Portal 에 업로드하도록 구성되어 있습니다. `~/.gradle/gradle.properties` 에 다음 키를 설정하세요.

```
mavenCentralUsername=YOUR_CENTRAL_TOKEN
mavenCentralPassword=YOUR_CENTRAL_SECRET
signing.keyId=...
signing.password=...
signing.secretKeyRingFile=/path/to/secring.gpg
```

업로드:
```bash
./gradlew publish
```

GitHub Actions 가 `*.*.*` 태그 푸시 시 자동 배포합니다.

## 라이선스

MIT License — 자세한 내용은 루트의 `LICENSE` 파일을 참조하세요.

## 변경 이력

상세 이력은 [CHANGELOG.md](CHANGELOG.md) 를 참조하세요.
