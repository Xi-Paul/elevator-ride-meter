# elevator-ride-meter

스마트폰으로 승강기의 속도·가속도·저크·3축 진동·소음을 측정하고 리포트를 만드는 도구.
웹앱과 Android 앱을 한 저장소에서 관리한다.

## 구조

```
web/                        웹앱 (GitHub Pages 로 배포)
  index.html                앱 본체
  sw.js                     서비스 워커 — 오프라인 동작 + 새 버전 감지
  version.json              배포 버전 (CI 가 커밋 해시로 채움)
  manifest.json, icon.svg
android/                    Android 앱 소스
  app/src/main/java/kr/xi/ridemeter/
    MainActivity.kt         UI·센서 수집·기록
    Dsp.kt                  구간 판정, FFT, 통계
    Iso.kt                  ISO 18738 산출 (VPPV, A95, 등속 구간)
    Wbv.kt                  ISO 2631-1 전신진동 가중 (Wk/Wd)
    Rollback.kt             안티롤백 분석
    Quality.kt              측정 유효성 자동 판정
    VibEvent.kt             진동 이벤트 위치 검출
    FreqSource.kt           회전 기인 / 레일 기인 주파수 추정
    Noise.kt, NoiseStat.kt  마이크 소음 측정
    Report.kt               HTML 리포트 생성
    ChartView.kt            실시간 그래프
    Updater.kt              Releases 기반 자동 업데이트 확인
  app/keystore/             고정 서명 키 (사내 배포용, 스토어 업로드용 아님)
.github/workflows/
  pages.yml                 web/ 변경 시 Pages 자동 배포
  android.yml               android/ 변경 또는 v* 태그 시 APK 빌드
```

## 배포

- **웹앱**: `web/` 아래 파일을 고쳐 push 하면 자동 배포.
  Settings → Pages → Source 는 **GitHub Actions** 여야 한다.
  앱을 다시 열면 상단에 새 버전 배너가 뜨고, 적용을 누르면 교체된다.
- **Android**: `android/` 를 고쳐 push 하면 Actions 탭에서 APK 를 받을 수 있다.
  배포판은 Releases → Create a new release → 태그 `v1.2` → Publish.
  태그가 그대로 앱 버전이 되며, 앱이 Releases API 로 새 버전을 확인한다.

## 측정 원리 요약

1. 시작 후 2초 정지 상태의 3축 평균으로 중력 단위벡터 ĝ 와 |g| 산출
2. 연직 가속도 = a·ĝ − |g| (폰 자세와 무관), 수평 2축은 수평면 직교 기저에 투영
3. 2단 1차 저역통과 → 가속도, 미분 후 별도 LPF → 저크
4. 사다리꼴 적분 → 속도·거리, 종료 시 v=0 조건으로 선형 드리프트 제거
5. 진동 리포트는 ISO 2631-1 가중(수직 Wk / 수평 Wd) 후 milli-g 로 표기
6. FFT 는 필터 전 원시 가속도에 Hann 윈도우 적용

스마트폰 센서는 교정되어 있지 않으므로 ISO 18738 계측기 요구사항은 충족하지 않는다. 참고값이다.
