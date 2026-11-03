# Proportional Share Test · Tomcat on cgroup v2

Spring Boot + Tomcat 애플리케이션에서 **"단일 JVM 안에서 워커 스레드만 분리"** 하는 방식과 **"JVM 자체를 둘로 분리"** 하는 방식의 **CPU proportional sharing 품질** 을 실측 비교하는 실험입니다.

> cgroup v2 의 `cpu.weight` 300:700 이 두 접근 방식에서 각각 **얼마나 깨끗하게** 지켜지는지, 그리고 상위 cgroup 에서 **"가중치 바깥의 CPU 압력"** 이 발생했을 때 어느 쪽이 더 흔들리는지를 실제 EC2 인스턴스 (t3.medium, 2 vCPU) 위에서 측정했습니다.

![Java](https://img.shields.io/badge/java-25-orange)
![Spring Boot](https://img.shields.io/badge/spring--boot-4.1.2-brightgreen)
![cgroup v2](https://img.shields.io/badge/cgroup-v2-blue)
![EC2](https://img.shields.io/badge/EC2-t3.medium-yellow)

## 결과 한눈에

| 조건 | 1 JVM (threaded cgroup) | 2 JVM (slice 분리) |
|---|---|---|
| **baseline (burner 없음)** | ratio ≈ **2.08** | ratio ≈ **2.53** |
| **burner 4개 (outside pool)** | ratio ≈ **1.98** | ratio ≈ **2.41** |

이상적 비율은 `700 / 300 = 2.33`.

| 1 JVM baseline | 1 JVM + outside burner |
|---|---|
| ![](./result/jvm1-result/normal/Screenshot%202026-11-04%20at%2001-58-01%20EC2%20Instance%20Connect%20ap-northeast-2.png) | ![](./result/jvm1-result/cpu-burst/Screenshot%202026-11-04%20at%2002-11-13%20EC2%20Instance%20Connect%20ap-northeast-2.png) |

| 2 JVM baseline | 2 JVM + outside burner |
|---|---|
| ![](./result/jvm2-result/normal/Screenshot%202026-11-04%20at%2001-17-01%20EC2%20Instance%20Connect%20ap-northeast-2.png) | ![](./result/jvm2-result/cpu-burst/Screenshot%202026-11-04%20at%2001-38-30%20EC2%20Instance%20Connect%20ap-northeast-2.png) |

전체 수치/해석/운영 메모는 [`result/README.md`](./result/README.md) 참고.

## 1. 테스트 목적

- 하나의 Spring Boot 애플리케이션 안에 **사용자 트래픽 (가중치 300)** 과 **관리자 트래픽 (가중치 700)** 을 분리해야 할 때, 두 가지 설계 선택지의 **proportional sharing 정확도** 를 수치로 비교.
- "**Acceptor / Poller / GC 같이 pool 밖에서 돌아가는 공유 스레드가 CPU 를 쓰면 비율이 깨진다**" 는 가설을 **인위적 CPU hog 스레드** 로 재현하여 검증.
- 두 방식의 trade-off (메모리/운영 복잡도 vs 격리 품질) 를 그래프로 보여줄 수 있는 재현 가능한 벤치 세트 구축.

## 2. 테스트 대상

| 모드 | 구성 | cgroup 배치 |
|---|---|---|
| **1 JVM** (`split` profile) | 하나의 JVM 안에 Tomcat Connector 2개 (8080 user / 8081 admin) + 각자 FixedThreadPool | `app.service` 아래 **threaded cgroup** `user-pool` (weight 300) · `admin-pool` (weight 700). 워커 스레드는 생성 시점에 자기 TID 를 각 cgroup 에 등록 |
| **2 JVM** | 같은 jar 를 서로 다른 포트로 **두 개의 systemd service** 로 기동 | 각 JVM 이 통째로 `userpool.slice` (weight 300) / `adminpool.slice` (weight 700) 에 소속 |

공통 워크로드: `GET /work` 가 **약 20ms CPU** 를 쓰고 `hello {x} tid={native-tid}` 반환.

추가 도구:
- `POST /burn/start?threads=N&outside=true|false` — CPU hog 스레드 N개 생성. `outside=true` 면 `/sys/fs/cgroup/system.slice/app.service` 로 이동시켜 **가중치 적용 바깥**에서 CPU 를 소모.
- `POST /burn/stop` — 전부 interrupt.

## 3. 테스트 순서

### EC2 세팅
AMI: Amazon Linux 2023 (cgroup v2 필수) / Instance: **t3.medium** (2 vCPU · 4 GB, burstable) / Java 25 Corretto / `wrk` 소스 빌드.

> `t3` 는 CPU credit 기반 burstable 인스턴스라 장시간 100% 사용 시 성능이 떨어질 수 있습니다. 본 테스트는 **30초 단일 벤치** 수준이라 credit 소진 전 측정이 완료되지만, 반복 실행 시에는 다음 벤치 전에 CPU credit 회복 (몇 분 유휴) 을 권장합니다.

```bash
sudo yum install -y java-25-amazon-corretto-devel git gcc make openssl-devel \
                    perl-FindBin perl-IPC-Cmd perl-File-Compare perl-Pod-Html
git clone https://github.com/wg/wrk && (cd wrk && make -j && sudo cp wrk /usr/local/bin/)

git clone https://github.com/jnj3j3/proportionalShareTestTomcat.git
cd proportionalShareTestTomcat/proportionalShareTestTomcat
./gradlew bootJar
sudo mkdir -p /opt/app && sudo cp build/libs/*.jar /opt/app/app.jar
```

### 모드별 배포

**① 2 JVM 모드**
```bash
sudo cp ops/two-jvm/userpool.slice ops/two-jvm/adminpool.slice \
        ops/two-jvm/user-app.service ops/two-jvm/admin-app.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start user-app.service admin-app.service
```

**② 1 JVM 모드**
```bash
sudo cp ops/one-jvm/setup-cgroups.sh /opt/app/ && sudo chmod +x /opt/app/setup-cgroups.sh
sudo cp ops/one-jvm/app.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start app.service
```

### 측정

```bash
# baseline
./ops/bench/load.sh 30 50 2

# with 4 outside burners
curl -X POST 'http://127.0.0.1:8080/burn/start?threads=4&outside=true'
./ops/bench/load.sh 30 50 2
curl -X POST 'http://127.0.0.1:8080/burn/stop'
```

벤치 도구 `ops/bench/load.sh` 는 **wrk 2개 인스턴스** 를 양 포트에 병렬로 날리고 `admin:user` ratio 를 출력. 자세한 명령 체인/스크립트/유닛 파일은 [`ops/`](./ops/) 디렉터리 참고.

## 4. 테스트 결과

> baseline ↔ cpu-burst 비교에서 **ratio 변화량**이 핵심 지표. 2 JVM 쪽이 외부 압력 하에서도 비율을 더 잘 보존.

| | user rps | admin rps | **ratio** | baseline 대비 |
|---|---:|---:|---:|---:|
| **1 JVM** baseline | 69.6 | 144.5 | **2.08** | — |
| **1 JVM** + 4 burner outside | 52.8 | 104.4 | **1.98** | ratio **−4.8%** · 처리량 **−27%** |
| **2 JVM** baseline | 63.0 | 159.2 | **2.53** | — |
| **2 JVM** + 4 burner outside | 66.3 | 159.7 | **2.41** | ratio **−4.7%** · 처리량 ≈ 유지 |

핵심 관찰:
- **2 JVM 쪽이 baseline 에서 이상 비율 (2.33) 에 더 가까움.** 1 JVM 은 Acceptor/Poller/GC 가 user-pool/admin-pool 밖에서 상시 돌면서 비율을 아래로 끌어내림.
- **outside burner 는 두 모드 모두 비율 자체는 크게 안 흔들음** — Linux CFS 가 상위 cgroup CPU 감소를 자식 가중치 비율에 **보존해서** 전파함. 다만 **1 JVM 쪽은 절대 처리량이 크게 하락** (burner 영향이 모든 pool 에 번짐), **2 JVM 쪽은 거의 유지** (burner 가 어느 한 slice 안에 가둠).
- 즉 cgroup v2 의 CPU proportional sharing 은 생각보다 견고하고, **1 JVM vs 2 JVM 의 실질적 차이는 "비율 정확도" 보다 "격리/안정성"** 쪽에 있음.

자세한 수치 해석/그래프 재현 방법/주의사항은 [`result/README.md`](./result/README.md) 를 참고하세요.

## 디렉토리 구조

```
.
├── src/main/java/com/proportionalsharetesttomcat/
│   ├── ProportionalShareTestTomcatApplication.java
│   ├── WorkController.java                 # GET /work  (20ms CPU burn)
│   ├── BurnController.java                 # POST /burn/start · /burn/stop
│   ├── CgroupPool.java                     # /proc/thread-self → cgroup.threads 등록
│   ├── CgroupThreadFactory.java            # 생성과 동시에 cgroup 조인
│   └── TomcatPoolsConfig.java              # split profile: 8080/8081 커넥터 + user/admin executor
│
├── ops/
│   ├── one-jvm/
│   │   ├── app.service                     # Delegate=cpu + ExecStartPre
│   │   └── setup-cgroups.sh                # user-pool / admin-pool threaded cgroup 생성
│   ├── two-jvm/
│   │   ├── userpool.slice · adminpool.slice   # CPUWeight 300 / 700
│   │   └── user-app.service · admin-app.service
│   └── bench/
│       └── load.sh                         # wrk 2개 병렬 벤치
│
├── result/
│   ├── README.md                           # 전체 수치 · 해석 · 재현 노트
│   ├── jvm1-result/{normal,cpu-burst}/     # 1 JVM 모드 스크린샷
│   └── jvm2-result/{normal,cpu-burst}/     # 2 JVM 모드 스크린샷
│
├── build.gradle / settings.gradle
└── README.md
```

## 개발자

- **주병규 (jnj3j3)** — [github.com/jnj3j3](https://github.com/jnj3j3)
