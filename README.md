# idkit

Kotlin/JVM 기반의 간단하고 빠른 Snowflake ID 생성기.

이 저장소는 64비트 Long 정수로 고유 ID를 생성하는 `SnowFlakeIdGenerator`를 제공합니다. 트위터 Snowflake 알고리즘을 바탕으로 타임스탬프 + 데이터센터 ID + 워커 ID + 시퀀스 조합으로 충돌 없이 ID를 생성합니다.

- 언어/런타임: Kotlin, JVM (JDK 21)
- 빌드: Gradle Kotlin DSL
- 테스트: JUnit 5

## 빠른 시작

### Gradle 설정
프로젝트에 라이브러리를 포함시키려면, 현재는 샘플/템플릿 저장소이므로 소스 복사 또는 로컬 포함을 권장합니다. (배포 아티팩트가 없다면 `src/main/kotlin`의 클래스를 프로젝트로 복사하세요.)

이 저장소를 그대로 사용한다면:
```bash
# Windows
./gradlew.bat build

# macOS/Linux
./gradlew build
```

테스트 실행:
```bash
# Windows
./gradlew.bat test

# macOS/Linux
./gradlew test
```

Gradle 주요 설정은 `build.gradle.kts`에 있으며, JDK 21 toolchain과 JUnit 5 구성을 사용합니다.

## 사용법
`SnowFlakeIdGenerator`는 스레드 안전합니다. 하나의 인스턴스를 여러 스레드에서 함께 사용하여도 됩니다.

```kotlin
fun main() {
    // 워커/데이터센터 ID는 0..31 범위
    val generator = dev.dornol.idkit.snowflake.SnowFlakeIdGenerator(workerId = 1, dataCenterId = 2)

    val id: Long = generator.nextId()
    println("generated id = $id")
}
```

원한다면 Epoch(기준 시각)를 지정할 수 있습니다. 기본 Epoch는 `Asia/Seoul` 타임존의 `2025-01-01T00:00:00`입니다.

```kotlin
val customEpoch = java.time.LocalDateTime.of(2020, 1, 1, 0, 0)
    .atZone(java.time.ZoneId.of("UTC"))
val generator = dev.dornol.idkit.snowflake.SnowFlakeIdGenerator(workerId = 0, dataCenterId = 0, epochStart = customEpoch)
```

## 비트 구성
64비트 ID는 다음과 같이 구성됩니다:

- 타임스탬프: 상위 비트들 (커스텀 Epoch 이후 경과 밀리초)
- 데이터센터 ID(`dataCenterId`): 5비트 (0..31)
- 워커 ID(`workerId`): 5비트 (0..31)
- 시퀀스(`sequence`): 12비트 (0..4095)

상수(코드에서 공개됨):
- `DATA_CENTER_ID_BITS = 5`
- `WORKER_ID_BITS = 5`
- `SEQUENCE_BITS = 12`
- `MAX_DATA_CENTER_ID = (1L shl 5) - 1 // 31`
- `MAX_WORKER_ID = (1L shl 5) - 1 // 31`
- `MAX_SEQUENCE = (1L shl 12) - 1 // 4095`

시프트 폭:
- `TIMESTAMP_LEFT_SHIFT = 5 + 5 + 12`
- `DATA_CENTER_ID_LEFT_SHIFT = 5 + 12`
- `WORKER_ID_LEFT_SHIFT = 12`

## 동작 특성
- 단조 증가: 동일 인스턴스에서 생성되는 ID는 엄격히 증가합니다.
- 스레드 안전: `nextId()`는 `@Synchronized`로 보호되어 멀티스레드 환경에서도 안전합니다.
- 시퀀스 오버플로: 같은 밀리초 내에 4096개를 생성하면 다음 밀리초가 될 때까지 대기합니다.
- 시계 역행(clock rollback) 완화: 시스템 시간이 잠시 과거로 이동해도, 내부적으로 마지막 타임스탬프 이상으로 보정하여 비단조 ID 생성을 방지합니다.

## 한계와 주의사항
- 시스템 시계에 크게 의존합니다. 긴 역행이나 비정상적인 시간 변경은 지연을 유발할 수 있습니다.
- 데이터센터/워커 ID 범위는 0..31이어야 합니다. 범위를 벗어나면 `IllegalArgumentException`이 발생합니다.
- 단일 프로세스 내 여러 인스턴스를 쓴다면, 워커/데이터센터 ID를 고유하게 할당해야 전역 충돌을 방지할 수 있습니다.

## 테스트
다음과 같은 시나리오를 검증하는 JUnit 5 테스트가 포함되어 있습니다 (`src/test/kotlin/dev/dornol/idkit/snowflake/SnowFlakeIdGeneratorTest.kt`).

- 생성자 파라미터 검증 (경계값 포함)
- 단조 증가성 (20,000개 연속 생성)
- 비트 필드 배치 정확성 (마스크/시프트 확인)
- 동시성(8 스레드 × 5,000개)에서의 전역 유일성 보장

실행:
```bash
./gradlew.bat test  # Windows
./gradlew test      # macOS/Linux
```

## 성능 팁
- 고 QPS 상황에서는 단일 인스턴스를 애플리케이션 전역 싱글턴으로 재사용하세요.
- 충분한 CPU와 안정적인 시스템 시계(예: NTP 동기화)를 유지하세요.
- 초당 4,096 × 1,000 ≈ 4.096M ID/초(밀리초 당 4096개)의 상한은 밀리초 기준 시퀀스 폭에 의해 결정됩니다. 실제 처리량은 잠금과 스케줄러에 따라 달라질 수 있습니다.

## 라이선스
이 프로젝트는 MIT 라이선스로 배포됩니다. 자세한 내용은 프로젝트 루트의 `LICENSE` 파일을 참고하세요.
- Copyright (c) 2025 dhkim
- 요약: 소프트웨어를 자유롭게 사용, 복제, 수정, 병합, 게시, 배포, 서브라이선스 및 판매할 수 있으며, 저작권 고지와 허가 고지를 포함해야 합니다. 소프트웨어는 "있는 그대로(AS IS)" 제공됩니다.

## 변경 이력
- 2025-10-25: LICENSE(MIT) 추가, README 라이선스 섹션 반영.
- 2025-10-25: JUnit 5 기반 포괄 테스트 추가, README 초안 작성.

## Maven Central 배포 가이드 (OSSRH)
다음 설정은 이 저장소를 Maven Central(Sonatype OSSRH)에 배포하기 위한 것입니다. 본 저장소에는 관련 Gradle 설정이 포함되었습니다.

1) OSSRH 계정 발급 및 프로젝트 등록
- https://issues.sonatype.org/ 에서 계정 생성
- 새 Project ticket 생성 (GroupId: `dev.dornol`) 후 승인 대기

2) GPG 키 생성 (서명용)
- gpg 설치 후 키 생성: `gpg --full-generate-key`
- 키 확인: `gpg --list-secret-keys --keyid-format=long`
- ASCII armor로 개인키 내보내기: `gpg --armor --export-secret-keys <KEY_ID>`

3) Gradle 자격증명/서명키 설정 (로컬 사용자 홈의 `~/.gradle/gradle.properties` 권장)
```
# Sonatype
ossrhUsername=YOUR_OSSRH_USERNAME
ossrhPassword=YOUR_OSSRH_PASSWORD

# Signing
signing.keyId=YOUR_KEY_ID
signing.password=YOUR_KEY_PASSWORD
signing.key=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...
```
환경변수를 사용하려면 `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, `SIGNING_KEY`를 설정하세요.

4) 프로젝트 메타데이터 채우기
- `build.gradle.kts`의 POM 부분 TODO를 실제 값으로 변경하세요.
  - `url`, `scm.connection`, `scm.developerConnection`, `issueManagement.url`, 개발자 이메일 등

5) 로컬 검증
```
./gradlew.bat clean build publishToMavenLocal   # Windows
./gradlew clean build publishToMavenLocal       # macOS/Linux
```

6) 스냅샷/릴리스 배포
- 스냅샷: 버전을 `1.0-SNAPSHOT`으로 두고 `./gradlew -Prelease=false nexusPublish`
- 릴리스: 버전을 `1.0.0`처럼 SNAPSHOT이 아닌 값으로 설정 후 다음 실행
```
./gradlew nexusPublish
```
플러그인이 자동으로 staging → close → release를 수행합니다.

주의: 비공개 키, 패스워드 등 비밀정보는 절대 저장소에 커밋하지 마세요.

배포 좌표(예상):
- `groupId`: `dev.dornol`
- `artifactId`: `idkit`
- `version`: `1.0.0` (예시)

## 파일 구조
- `src/main/kotlin/dev/dornol/idkit/snowflake/SnowFlakeIdGenerator.kt` — Snowflake ID 생성기 구현
- `src/test/kotlin/dev/dornol/idkit/snowflake/SnowFlakeIdGeneratorTest.kt` — 단위/동시성 테스트
- `build.gradle.kts` — Gradle 설정 (JDK 21, JUnit 5)

---
문의/피드백은 이슈로 남겨주세요.