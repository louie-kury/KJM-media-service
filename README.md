# KJM-media-service

미디어 파일 업로드를 지원할 내부 파일 검사 기능입니다. HTTP API와 파일 저장 기능은 아직 제공하지 않습니다.

Java 17과 Gradle wrapper 9.9.2를 사용합니다. 최초 실행 시 Gradle 배포본과 테스트 의존성을 다운로드해야 할 수 있습니다.

Windows에서 테스트:

```powershell
.\gradlew.bat test
```

JPEG, PNG, WebP 및 게시글용 MP4의 기본 식별 정보와 실제 읽은 크기를 확인합니다. 파일 전체의 안전성이나 재생 가능성은 보장하지 않습니다. 검사 도중 출력된 데이터는 임시 데이터이며 호출자가 실패 시 폐기해야 합니다.
