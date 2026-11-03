# Test Results · 상세 수치 및 해석

프로젝트 전체 개요는 [`../README.md`](../README.md) 참고. 이 문서는 각 벤치 실행의 **원본 수치, 환경 변수, 해석, 재현 노트** 를 정리합니다.

## 실행 환경

| 항목 | 값 |
|---|---|
| 인스턴스 | AWS EC2 **t3.medium** (2 vCPU, 4 GB, burstable) |
| 리전 | ap-northeast-2 |
| OS / Kernel | Amazon Linux 2023, cgroup v2 |
| JVM | Amazon Corretto 25, `-Xms256m -Xmx512m` |
| Spring Boot | 4.1.2 |
| 벤치 도구 | `wrk 4.x` (양 포트에 각각 `-c 50 -t 2 -d 30s`) |
| 워크로드 | `GET /work` — 요청당 약 20ms CPU burn |
| user 커넥터 | 포트 8080, 워커 풀 50 |
| admin 커넥터 | 포트 8081, 워커 풀 5 |
| CPU 가중치 | user : admin = **300 : 700** → 이상 ratio `2.33` |

## 측정 지표

- `user rps` : 포트 8080 처리량
- `admin rps`: 포트 8081 처리량
- `admin:user ratio` : 두 값의 비. **2.33 에 가까울수록** proportional sharing 이 정확.

## 1 JVM 모드 (threaded cgroup)

`app.service` 하나의 JVM 안에서 Tomcat Connector 2개 + `user-pool` / `admin-pool` threaded cgroup 로 워커 분리.

### baseline (burner 없음)

![](./jvm1-result/normal/Screenshot%202026-11-04%20at%2001-58-01%20EC2%20Instance%20Connect%20ap-northeast-2.png)

```
user  rps = 69.63     admin rps = 144.54     admin:user = 2.08
latency p99 user = 1.54 s     admin = 874.86 ms
```

이상 비율 (2.33) 보다 **약간 낮음**. Acceptor / Poller / GC 등 `app.service` 상위 cgroup 에서 상시 돌아가는 공유 스레드가 CPU 를 소모하며, 그 영향이 user/admin 양쪽에 **대칭적으로** 반영되지만, 측정 노이즈/워커 수 비대칭 등으로 완전한 2.33 에는 미치지 못함.

### cpu-burst (`threads=4, outside=true`)

![](./jvm1-result/cpu-burst/Screenshot%202026-11-04%20at%2002-11-13%20EC2%20Instance%20Connect%20ap-northeast-2.png)

```
user  rps = 52.79     admin rps = 104.35     admin:user = 1.98
latency p99 user = 1.88 s     admin = 870.01 ms
```

4개 burner 를 `/sys/fs/cgroup/system.slice/app.service` (user-pool/admin-pool 밖) 에 배치. baseline 대비:

- **ratio** 2.08 → 1.98 (−5%): 소폭 하락 하지만 큰 변화는 아님
- **처리량** user −24%, admin −28% — 양쪽 모두 비슷한 폭으로 하락
- 즉 burner 가 **공유 cgroup CPU 를 뺏고, 그 손실이 두 pool 에 비례적으로 전파** — CPU weight 비율 자체는 거의 보존

## 2 JVM 모드 (slice 분리)

같은 jar 를 포트/슬라이스만 다르게 두 개의 systemd service 로 기동. 각 JVM 전체가 자기 slice 안에 소속.

### baseline

![](./jvm2-result/normal/Screenshot%202026-11-04%20at%2001-17-01%20EC2%20Instance%20Connect%20ap-northeast-2.png)

```
user  rps = 63.03     admin rps = 159.20     admin:user = 2.53
latency p99 user = 1.50 s     admin = 827.08 ms
```

이상 비율 2.33 **보다 약간 높음**. Acceptor/Poller/GC 가 각자 자기 JVM 의 slice 안에서 돌기 때문에 **각 JVM 의 CPU 할당이 깔끔하게 가중치대로 적용**됨. 2.33 에서 벗어나는 방향은 1 JVM 과 반대 — 공유 오버헤드가 양쪽에 정비례로 들어가는 1 JVM 과 달리, 2 JVM 은 **admin 쪽이 상대적으로 더 효율적** 으로 보임 (워커 수가 적어서 context switch 가 덜함).

### cpu-burst (`threads=4, outside=true`)

![](./jvm2-result/cpu-burst/Screenshot%202026-11-04%20at%2001-38-30%20EC2%20Instance%20Connect%20ap-northeast-2.png)

```
user  rps = 66.31     admin rps = 159.68     admin:user = 2.41
latency p99 user = 1.77 s     admin = 898.80 ms
```

2 JVM 환경에서는 `outside=true` 로 지정해도 `app.service` 라는 상위 cgroup 자체가 **존재하지 않아** burner 는 자기가 호출된 user JVM 의 `userpool.slice` 안에 머묾. baseline 대비:

- **ratio** 2.53 → 2.41 (−5%): 거의 변화 없음
- **user rps** 그대로 (63 → 66) · **admin rps** 그대로 (159 → 160)
- burner 가 user JVM 안에서 50개 워커와 **CFS 공정 경쟁** → 각 burner 가 1/51 지분만 받아 사실상 영향 미미

## 비교 요약

| 지표 | 1 JVM baseline | 1 JVM burst | 2 JVM baseline | 2 JVM burst |
|---|---:|---:|---:|---:|
| user rps | 69.63 | 52.79 | 63.03 | 66.31 |
| admin rps | 144.54 | 104.35 | 159.20 | 159.68 |
| **ratio** | **2.08** | **1.98** | **2.53** | **2.41** |
| user p99 | 1.54 s | 1.88 s | 1.50 s | 1.77 s |
| admin p99 | 875 ms | 870 ms | 827 ms | 899 ms |

### 결론

1. **cgroup v2 의 `cpu.weight` 는 두 모드 모두에서 "거의" 정확히 지켜진다.** 상위 cgroup 에서의 CPU 압력도 비율을 극적으로 깨지 않음 — CFS 가 하위 가중치 비율을 보존하면서 CPU 를 감축.
2. **1 JVM vs 2 JVM 의 실질적 차이는 "비율 정확도" 보다 "안정성/격리"** 쪽에 있다. 2 JVM 은 burner 가 한 JVM 에만 영향을 미치고 다른 쪽은 **완전히 무풍지대**로 유지되는 반면, 1 JVM 은 burner 가 공유 cgroup 에 있으면 **양쪽이 모두 처리량 손실**.
3. **운영 선택 기준**: CPU 비율 유지가 목적이면 둘 다 OK. 메모리/GC/장애 격리까지 필요하면 2 JVM. 메모리 절약과 운영 단순함이 중요하면 1 JVM.

## 재현 방법

프로젝트 루트의 [`../README.md`](../README.md) "테스트 순서" 섹션 참고. 각 벤치는 다음과 같은 흐름으로 수행:

```bash
# baseline
./ops/bench/load.sh 30 50 2

# with burners
curl -X POST 'http://127.0.0.1:8080/burn/start?threads=4&outside=true'
./ops/bench/load.sh 30 50 2
curl -X POST 'http://127.0.0.1:8080/burn/stop'
```

### 측정 품질 노트

- 각 벤치는 **첫 1회는 JIT warm-up 로 버리고** 2~3회 반복 후 안정 값 채택.
- `wrk` 는 keep-alive 커넥션 유지하므로 Acceptor 는 상대적으로 덜 압박 받음. Acceptor/Poller 를 극한까지 밀어보려면 `-H "Connection: close"` + `-c 2000+` 조합 필요.
- JVM 힙은 `-Xmx512m` 고정 — t3.medium (4 GB) 에서 2 JVM 띄울 때 총 ~1 GB 사용, OS 와 wrk 포함 여유 있음.
- **t3.medium 은 burstable** — baseline CPU 는 2 vCPU 의 20% (unlimited mode 가 아닌 standard 설정 기준) 수준이고, CPU credit 을 소모해 full 2 vCPU 를 쓸 수 있음. 본 테스트의 30초 벤치는 credit 소진 전 완료되므로 2 vCPU 성능이 유지되지만, 반복 실행 시에는 credit 회복 시간을 두거나 `c` 계열 (c6i.large 등) 로 교체하는 것이 측정 안정성에 유리.

## 폴더 구조

```
result/
├── README.md                  # 이 문서
├── jvm1-result/
│   ├── normal/                # 1 JVM baseline 스크린샷 (대표 1장)
│   └── cpu-burst/             # 1 JVM + outside burner 스크린샷
└── jvm2-result/
    ├── normal/                # 2 JVM baseline 스크린샷 (3장 중 대표 1장)
    └── cpu-burst/             # 2 JVM + outside burner 스크린샷
```

각 폴더에 여러 스크린샷이 있을 수 있으며 (반복 실행 결과), 본 문서는 **대표 1장**씩만 인용했습니다.
