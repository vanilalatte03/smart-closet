# 추천 계산 반복 작업 제거

## 문제와 변경

추천 후보 예산과 스트리밍 선택은 유지하지만, 같은 후보의 이력 비교마다 `itemIds()`가 새 집합/stream을 만들고 후보마다 같은 옷의 style tag JSON을 다시 읽고 있었다.

- `OutfitCandidate.hasSameItemSet/intersects`는 최대 세 슬롯의 ID를 직접 비교한다. null ID 제외와 중복 ID 제거라는 기존 집합 의미를 유지한다.
- `RecommendationScorer.prepare`는 추천 요청마다 새 계산 함수를 만든다. 선호/상황 태그를 한 번 정규화하고, 옷별 태그 파싱 결과를 identity 기준 map으로 재사용한다.
- 캐시는 함수의 지역 상태다. 요청이나 사용자 사이에 공유하지 않으며, 옷 변경 중이나 여러 스레드에서 재사용하지 않는다.
- `RecommendationService`는 후보 순회 전에 함수를 준비한다. 후보 예산, 생성 순서, 점수식, tie-break, 추천 이유, API/DB 계약은 유지한다.

## 측정 범위

- 측정일: 2026-10-30. Windows, Oracle JDK 21.0.9, JVM heap `-Xms512m -Xmx512m`.
- 기준 소스: `82b7ed09e58368e809d5d9c84b028ef03fc78330`. 변경 전에 해당 main class 산출물을 `build/benchmark-baseline/main`에 보존했다.
- 기준 `RecommendationScorer.class` SHA-256: `F5F2463BE775E2D69F9DE20EF38EB80DDD049E5CCC578512269243ADA0C5245D`.
- 동일 fixture와 test runtime classpath에서 기준 클래스와 변경 클래스를 별도 JVM으로 번갈아 3쌍 실행했다.
- 각 JVM은 조건별 warmup 3회, 측정 7회를 수행한다. 아래 표는 JVM별 중앙값 세 개의 중앙값이다.
- 상의/하의/아우터 각각 8·16·32개, 각 옷에 태그 3개, 18°C/CLOUDY, WORK 상황을 사용한다.
- 이력 50 조건은 착용 이력 50건과 피드백이 있는 추천 이력 50건이다. 요청 시각은 고정한다.
- 타이머에는 요청별 함수 준비, 후보 생성, 점수화, best 선택을 포함한다. fixture 생성, DB, HTTP, 날씨 조회, 후보 예산 선정, 결과 저장은 포함하지 않는다.
- 선호 목록은 두 경로 모두 요청당 한 번 생성한다. 독립 리뷰에서 발견한 baseline의 후보별 목록 생성 차이를 보정한 뒤 3쌍을 다시 측정한 결과다.
- JaCoCo 없는 `JavaExec` 프로세스로 측정한다. 객체 할당량은 `ThreadMXBean`의 현재 스레드 누적 할당 바이트 차이다. 최대 heap 점유량이나 RSS가 아니다.

## 결과

| 카테고리별 옷 | 이력 | 후보 | 이전 ms | 이후 ms | 시간 감소 | 이전 할당 bytes | 이후 할당 bytes | 할당 감소 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 0 | 576 | 6.759 | 3.702 | 45.23% | 4,658,944 | 1,596,920 | 65.72% |
| 8 | 50 | 576 | 32.320 | 2.855 | 91.17% | 102,377,416 | 3,212,832 | 96.86% |
| 16 | 0 | 4,352 | 20.492 | 6.687 | 67.37% | 33,797,240 | 11,180,104 | 66.92% |
| 16 | 50 | 4,352 | 203.101 | 17.909 | 91.18% | 776,531,128 | 22,782,872 | 97.07% |
| 32 | 0 | 33,792 | 81.593 | 17.910 | 78.05% | 255,515,928 | 81,731,064 | 68.01% |
| 32 | 50 | 33,792 | 1,565.935 | 54.786 | 96.50% | 6,050,287,960 | 170,540,664 | 97.18% |

각 실행의 중앙값과 선택 결과 signature는 [CSV](evidence/recommendation-scoring-context.csv)에 보관한다. 여섯 조건 모두 3쌍에서 최종 옷 ID, 총점, 세부 점수가 같았다.

이 수치는 특정 fixture의 로컬 계산 비교다. 짧은 warmup의 수동 벤치마크이므로 JIT/GC 및 PC 부하 영향을 받으며, 작은 조건끼리 이력 유무의 절대 시간을 비교하지 않는다. 실제 HTTP p95, 동시 처리량, 운영 메모리 사용량의 개선율로 해석하지 않는다. 시간 임계값을 CI 실패 조건으로 추가하지 않는다.

## 재현

현재 구현의 계산 측정:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
./gradlew.bat recommendationBenchmark
```

기준 소스와 비교하려면 별도 checkout에서 기준 main class를 먼저 만든다. 아래 경로는 재현용 예시이며 해당 경로가 비어 있을 때 사용한다.

```powershell
git worktree add --detach ../smartcloset-scoring-baseline 82b7ed09e58368e809d5d9c84b028ef03fc78330
Push-Location ../smartcloset-scoring-baseline
./gradlew.bat classes
Pop-Location
foreach ($run in 1..3) {
    ./gradlew.bat recommendationBenchmark
    ./gradlew.bat recommendationBenchmark -PbenchmarkMode=baseline '-PbenchmarkMainClasses=../smartcloset-scoring-baseline/build/classes/java/main'
}
```

`baseline`은 기존 단일 후보 `score` 경로를 사용한다. 변경 전 비교에는 반드시 기준 checkout의 `benchmarkMainClasses`를 함께 지정한다. 지정하지 않으면 현재 클래스의 단일 후보 경로를 측정한다. 기준 class 디렉터리는 측정 JVM의 Java 21 및 runtime dependency와 호환되어야 한다.

## 회귀 검증

- `OutfitCandidateItemSetTest`: null/중복/서로 다른 ID와 null 포함 이력 집합을 조합해, 직접 비교 결과를 기존 `itemIds()` 집합 연산과 대조한다.
- `RecommendationPreparedScoringTest`: 12/13/18/19/25°C, 모든 상황과 기본 상황, 빈/최근 이력, 1/3/7/14일 경계, 한국어/ASCII/blank/null 태그에서 세부 점수와 최종 선택을 단일 후보 경로와 대조한다.
- 같은 옷의 JSON은 요청당 한 번만 읽는지, 서로 다른 옷의 같은 ID나 다음 요청에 캐시가 섞이지 않는지, 잘못된 JSON을 계속 거부하는지도 확인한다.
- `./gradlew.bat test checkstyleMain checkstyleTest jacocoTestCoverageVerification build`: 테스트 370개, 실패/오류/skip 0. line coverage 4,073/4,613 (88.29%), 60% 게이트 통과.
