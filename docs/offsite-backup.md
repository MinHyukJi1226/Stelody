# 외부 DB 백업: Backblaze B2 준비

## 목적과 현재 상태

운영 Supabase 프로젝트와 별개인 비공개 저장소에 암호화한 PostgreSQL 백업을 둔다. Render가 잠들거나 개발 Mac이 꺼져도 GitHub Actions에서 실행하도록 구성했다. **비공개 B2 버킷·버킷과 경로에 제한된 전용 키·수명 주기를 실제 API로 확인했다. 시험 파일의 업로드·내려받기·복호화·영구 삭제도 성공했다. GitHub Secrets와 예약 백업은 아직 활성화하지 않았다.** 공개 저장소의 Actions artifact에는 백업이나 복호화 키를 올리지 않는다.

`database.py`의 보존·제외·복구 가드를 유지한다. 카탈로그·회원·즐겨찾기·개인 목록을 보존하며 로그인 세션, 재인증, YouTube 토큰·동의·내보내기, 수집 작업과 조회수 표본은 제외한다. 복구는 다른 이름의 빈 DB에만 수행하며 실제 운영 DB를 덮어쓰지 않는다.

## 비용과 보관

2026-10-05 확인한 [B2 무료 가입 안내](https://www.backblaze.com/sign-up/cloud-storage)는 신용카드 없이 가입할 수 있다고 명시한다. [요금 안내](https://www.backblaze.com/cloud-storage/pricing)에 따르면 처음 10GB 저장은 무료다. 이번 운영 시험 덤프는 817,599 bytes(약 0.8MB)였다. 일일 백업과 짧은 보관 구성을 쓰면 현재 크기에서 무료 저장량 안에 들어간다. 저장·다운로드·API 요청의 무료 한도는 각각 다르므로 실제 사용량을 확인한다. [Caps & Alerts](https://www.backblaze.com/docs/en/cloud-storage-data-caps-and-alerts)에서 무료 범위에 맞는 한도·알림을 유지하고 결제수단을 추가하지 않는다. 무료 한도 도달로 백업이나 다운로드가 중단되면 실패 알림을 확인한다.

- 비공개 전용 버킷을 사용한다. 공개 다운로드와 Object Lock은 켜지 않는다.
- `stelody/backups/` 접두어의 수명 주기는 업로드 **1일 후 숨김**, 숨김 **1일 후 영구 삭제**, 미완료 대용량 업로드 **1일 후 취소**로 설정한다.
- [B2 수명 주기](https://www.backblaze.com/docs/en/cloud-storage-lifecycle-rules)는 매일 실행한다. 두 단계 모두 다음 실행까지 지연될 수 있으므로 정확한 삭제 시각을 보장하지 않는다. 초기 구성은 최근 하루 정도의 복구 지점을 남긴다.
- GitHub 작업은 매시간 원래 만료 시각을 확인한다. 현재·이전 버전을 모두 조회하고 [VersionId를 지정해 영구 삭제](https://www.backblaze.com/apidocs/s3-delete-object)한다. VersionId 없는 삭제는 숨김 마커만 만들어 바이트가 남으므로 사용하지 않는다. 미만료·알 수 없는 자료가 남은 키의 숨김 마커도 유지한다.
- 업로드 직전 원본 API 자료의 보관 기한이 **5일 이상** 남은 백업만 허용한다. 하루 뒤 숨김과 추가 하루 뒤 삭제의 일일 처리 지연에 여유를 둔다. 원본을 수집으로 갱신하거나 만료 정리 후 재시도한다. 복호화 시에는 기존 최대 7일 및 원본 API 기한을 다시 검사한다.
- 덤프·암호화 파일은 각각 128MiB로 제한한다. 데이터가 커지면 사용량 검토 후 상한을 조정한다.

일일 백업은 최대 약 하루의 변경 손실이 생길 수 있다. 실패한 날짜에는 손실 범위가 커진다. 수명 주기와 예약 실행의 실제 삭제 결과도 확인한다.

## 운영자가 준비할 항목

1. [B2 무료 계정](https://www.backblaze.com/sign-up/cloud-storage)을 만들고 로그인한다. 계정 확인·약관은 운영자가 직접 처리한다. 백업용 지역은 가입 화면에서 제공되는 지역 중 선택하며 버킷 화면의 실제 S3 Endpoint를 기록한다.
2. 전역에서 중복되지 않는 이름으로 전용 버킷을 만든다. 예: `stelody-backups-<고유문자>`. 파일 공개 설정은 **Private**다.
3. 위 수명 주기를 먼저 설정한다. Native API 표현은 다음과 같다. 버킷 전체를 지우는 빈 접두어로 바꾸지 않는다.

```json
[{"fileNamePrefix":"stelody/backups/","daysFromUploadingToHiding":1,"daysFromHidingToDeleting":1,"daysFromStartingToCancelingUnfinishedLargeFiles":1}]
```

4. 해당 버킷과 접두어에만 접근하는 application key를 만든다. 파일 목록·읽기·쓰기·삭제 권한(`listFiles`, `readFiles`, `writeFiles`, `deleteFiles`)이 필요하다. master key를 사용하지 않는다. [Application key 안내](https://www.backblaze.com/docs/cloud-storage-application-key-capabilities). keyID와 applicationKey는 비공개 로컬 파일에 보관하고 채팅·PR·스크린샷에 넣지 않는다.
5. 복구용 age X25519 키 쌍을 비공개 로컬 파일에 생성한다. 공개키만 GitHub에 주고, 개인키는 운영 DB·Render·GitHub와 별개의 안전한 곳에도 보관한다. 개인키가 없으면 백업을 복원할 수 없다. 검증에서 만든 시험 개인키는 운영 키로 쓰지 않는다.
6. GitHub 실행기에 DB 백업 접속을 제공하는 범위를 별도로 승인한다. 현재 `database.py`는 app/session 스키마 소유자 접속을 요구한다. 기존 수집 Secrets를 재사용하거나 DB 역할 권한을 넓혀 우회하지 않는다. 별도 GitHub environment에 두고 접근을 제한한다.
7. 백업 이후 탈퇴한 UUID를 운영 DB와 독립적으로 보관하는 기록 경로를 확보한다. **이 백업 작업 자체는 탈퇴 기록을 자동 확보하지 않는다.** 기록이 완전하지 않으면 기존 복구 가드에 따라 회원 자료 공개 재개를 막는다. 주기적인 백업만으로 이 조건을 완료했다고 기록하지 않는다.

## GitHub 설정

Secrets와 변수는 environment `production-backup`에 두고 배포 브랜치를 `main`으로 제한한다. 실행 전 활성화 여부를 판단하는 `BACKUP_ENABLED`만 저장소 Variable로 둔다. 매 실행 승인 설정은 무인 백업을 멈추므로 운영 조건에 맞춰 environment 보호를 결정한다.

[예약 작업](../.github/workflows/database-backup.yml)은 `main`에서 `BACKUP_ENABLED=true`일 때만 실행한다. 수동 기본값은 만료 정리만 한다. `backup=true`를 선택해야 새 백업을 만든다. 일일 백업은 UTC 03:47, KST 12:47이며 만료 정리는 매시간 47분이다. 실행이 늦어질 수 있으므로 저장소 수명 주기를 함께 둔다. 실패 알림은 Actions 이메일·실패한 실행만 알림 설정을 사용한다.

| 종류 | 이름 | 내용 |
| --- | --- | --- |
| Secret | `BACKUP_DB_HOST` | Supabase session pooler 호스트 |
| Secret | `BACKUP_DB_USERNAME` | 승인된 스키마 소유자 접속 이름 |
| Secret | `BACKUP_DB_PASSWORD` | 백업 접속 비밀번호 |
| Secret | `BACKUP_DB_CA_CERTIFICATE_BASE64` | 공개 DB CA의 base64 |
| Secret | `BACKUP_B2_ACCESS_KEY_ID` | 버킷에 한정된 keyID |
| Secret | `BACKUP_B2_SECRET_ACCESS_KEY` | 버킷에 한정된 applicationKey |
| Variable | `BACKUP_B2_ENDPOINT` | 버킷의 실제 `https://s3.<region>.backblazeb2.com` |
| Variable | `BACKUP_B2_BUCKET` | 생성한 전용 버킷 이름 |
| Variable | `BACKUP_AGE_RECIPIENT` | `age1...` 공개키 |
| Variable | `BACKUP_ENABLED` | 준비·수동 검증 후 `true` |

`BACKUP_AGE_IDENTITY_FILE`과 개인키 내용은 GitHub에 등록하지 않는다. DB 비밀번호는 환경으로만 전달하며 URL·명령 인수에 넣지 않는다. CA는 만료와 TLS hostname을 검증한다. 작업 종료 후 평문 덤프·인증서와 암호화 임시 파일을 제거한다. 비밀값·DB 행 자료·provider 예외 원문은 로그로 출력하지 않는다.

백업 생성 → age 공개키 암호화 → B2 업로드 → 업로드 응답의 VersionId로 내려받은 암호문의 SHA-256 및 metadata 확인 순서로 수행한다. 업로드 전에 비공개 ACL과 해당 접두어의 활성 숨김 1일·이전 버전 삭제 1일 규칙을 검사한다. 공개 버킷이나 누락된 규칙에서는 전송을 거부한다. 업로드 응답만으로 성공 처리하지 않는다. 첫 설정에서는 수동 실행·다운로드·격리 복원·비공개 접근·영구 삭제까지 확인하고 예약 작업을 활성화한다.

## 오프라인 복구

PostgreSQL 17 클라이언트, Python 3.11 이상, age 도구가 필요하다. B2 작업에는 `requirements.txt`를 별도 가상 환경에 설치한다. 연결과 개인키 경로는 비공개 환경으로 지정한다.

```sh
python3 infra/backup/offsite.py download --key '<확인한 stelody/backups/...age 키>' --directory /private/recovery/encrypted
python3 infra/backup/offsite.py unseal --source /private/recovery/encrypted/<파일>.age --directory /private/recovery/plain
```

다운로드는 기한·checksum·metadata와 안전한 키를 검사하며 기존 파일을 덮어쓰지 않는다. 복호화는 지정 개인키와 age 인증 검사를 사용한다. tar는 `manifest.json`, `database.dump` 두 일반 파일만 허용하고 경로 이동·링크·추가 파일을 거부한다. 복호화 후 원래 manifest의 기한·checksum도 검사한다.

이후 [기존 복구 절차](deployment-operations.md#5-백업과-복구)를 따라 서비스를 멈추고 완전한 탈퇴 기록을 확인한 다음 다른 빈 DB에 `database.py restore`를 실행한다. `unseal` 성공이나 격리 복원 성공은 운영 트래픽 재개 승인이 아니다. 확인 후 시험 복원 DB·평문·시험 키를 정리한다.

## 검증 구분

- 로컬: 실제 age 암호화·복호화·변조 거부, 업로드 후 read-back 검사, 짧은 기한 거부, 안전한 다운로드, 이전 버전까지 영구 삭제하는 만료 정리 테스트. 원격 저장소 응답은 모의 응답으로 확인했다.
- 실제 운영 원본: Supabase 백업 → 로컬 격리 복원 → 세션·토큰 제외, 공개 카탈로그 보존. 백업 후 시험 계정 탈퇴 → 복구에서 해당 UUID와 개인 목록·즐겨찾기 삭제 재적용.
- B2 실제 확인: 버킷과 경로에 제한된 전용 키, 공개 ACL 없음, 활성 수명 주기(숨김 1일·이전 버전 삭제 1일), 암호화 시험 파일 업로드·다운로드·복호화 일치, 익명 접근 401, 만료된 이전 버전 영구 삭제. 시험 파일은 정리했다. 저장소의 일일 수명 주기 실행을 기다려 관찰한 시험은 아니다.
- 미완료: GitHub 예약 실행, 일일 수명 주기 삭제 관찰, 독립 탈퇴 기록의 완전성. 시험 계정용 재인증 자료를 직접 준비한 검증은 실제 Google 재인증 화면 전체 시험을 대체하지 않는다.

2026-10-05 B2 연결 후 운영 DB 백업의 암호화 업로드·다운로드·복호화 원본 일치 및 PostgreSQL archive 목록 검증도 완료했다. 시험 원격 복제본과 로컬 평문은 제거했다. 예약 백업은 활성화 전이다.
