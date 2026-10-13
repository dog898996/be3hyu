# 이미지 전달 분석의 복원 상태 검증

최신 `MemoryReviewCoordinatorTest` 43개를 실제 SDK28 Robolectric·SQLite로 실행해 완료된 이미지 전달 분석의 복원 시험 한 개가 실패하는 것을 재현했다. 복원 뒤 제공자 요청은 `accepted`에서 `uncertain`으로 바뀌고 응답 raw hash는 유지됐다. 텍스트 검토 watermark도 유지됐지만 asset은 `provider_analysis_proof_missing`으로 다시 대기했다.

이는 완료된 상태를 가정한 fixture가 제공자 요청을 아직 `accepted`로 저장한 데서 발생했다. 생산 Runtime은 응답을 `accepted`로 보존한 상태에서 분석을 처리한 뒤 마지막에 `completed`로 저장한다. 백업 복원은 아직 접수·대기·실행 중인 요청을 자동 재실행하지 않기 위해 `uncertain`으로 격리한다. 제공자 상태를 검사하는 분석 증거 조건을 완화하지 않았다.

정착된 복원 fixture에서는 제공자 요청을 같은 원문·raw hash로 `completed`까지 저장한 뒤 백업한다. 원본 SHA와 전달 표현 SHA가 각각 유지되고, 원본 전체 업로드·전체 내용 확인·서버 바이트 독립 검증을 주장하는 네 상태는 모두 typed false이며 복원 뒤 pending이 없다. 이후 명시적 재분석 요청은 다시 pending이 된다.

반대 사례도 추가했다. 제공자 요청이 아직 `accepted`인 백업은 복원 뒤 `uncertain`과 asset pending으로 돌아간다. 기존 분석 원문·record hash·body hash·derived observation은 삭제되지 않고 이전 coverage도 system 원문으로 보존된다. 큐에 요청을 자동으로 돌려놓지 않는다. 두 사례를 포함한 최종 44개가 실패·skip 없이 통과했으며 실행 시간은 7.708초였다.

생산 코드는 수정하지 않았다. 이미지 분석과 업로드 관측 자료는 사람이 작성한 fixture이므로 실제 모델의 이미지 인식이나 서버 수신 바이트 검증을 입증하지 않는다. source SHA와 재현 결과는 [검증 기록](benchmarks/memory-review-representation-restore-regressions.json)에 있다.
