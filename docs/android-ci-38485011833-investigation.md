# Android CI investigation — 2026-10-14

## Evidence and scope

Run: https://github.com/dog898996/be3hyu/actions/runs/38485011833
Failed revision: 653665bd. Patch base: 2d41564a950141b5a972b860365e6775d0e1c126.
The downloaded job log confirms 14 device tests, one UI failure, successful explicit force-stop data recovery, successful restart seed and forceReopen, then a failed automatic Java-crash recovery probe. No subsequent reboot proof was produced by that revision because the shell exited at the crash failure.

## UI failure

The immediate cause is an unchecked null Files-tab lookup at DeviceRuntimeIntegrationTest.java:199. Settings persistence and acknowledgement lookup completed before that lookup. MainActivity settings Save only persists settings and displays an AlertDialog; its positive-button listener is null, and it does not intentionally finish the Activity or open Home.

Important evidence limitation: TestWatcher.failed executes after the test method unwinds. The try-with-resources ActivityScenario has already closed the Activity by then. Therefore launcher3 in the failure screenshot is consistent with test cleanup and DOES NOT establish that the launcher covered the Activity at the failed click. Quickstep ANR recovery is recorded, but neither a shared root cause nor a MainActivity lifecycle defect is established.

Latest main already added a bounded Files-tab wait and a pre-close screenshot. This patch narrows acknowledgement to the app-owned android:button1 and Files to the app package; records lifecycle state, Activity identity, focus, finishing/destroyed state before/after dismiss; requires RESUMED without relaunch; adds an actual pause/stop/resume navigation regression. Timeouts are unchanged. The diagnostic package after close must not be treated as pre-failure state.

## Restart failure

The failing injection is `am crash`, not `am force-stop`. The log shows an intentional CrashedByAdbException in PID 3043. Explicit reopen passed, including actual foreground service and persisted identity/draft. The failed log does not include enough AMS service-restart/crash-dialog state to distinguish OS crash policy from app startup/service failure. START_STICKY is not proof of arbitrary Java-crash recovery within 120 seconds; official API describes system recreation after process death without such a deadline: https://developer.android.com/reference/android/app/Service#START_STICKY

Latest main already separates SIGKILL, Java crash, explicit force-stop/reopen, and reboot, captures crash state, and retains failure flags through independent checks. This patch retains all those requirements and deadlines, records AMS state before Java injection, and makes FGS proof require the exact AutonomyService record rather than any foreground field anywhere in the dump. Six host regression tests reject unrelated service, history, package and component matches. CI runs these tests.

## What is and is not fixed

Confirmed test defects: null dereference, unscoped UI selection, cleanup-sensitive failure evidence and overbroad FGS proof. No spontaneous application crash is proved by this log. No production recovery mechanism is changed speculatively. UI navigation and automatic Java-crash recovery remain unverified until actual API35 CI executes this patch; this is a minimal test/proof correction, not a claim that the underlying Android failures are resolved.

## CI acceptance and triage

Require at least 16 passing primary tests (latest main 15 plus new lifecycle regression), all existing proof markers, a new PID and exact foreground AutonomyService before any recovery Activity launch, retained failed phase flags, and unchanged bounded waits. No skip, relaunch, own-app error dismissal or explicit reopen counts as automatic recovery.

If UI fails: use the pre-close dump and HOYOUNG_SETTINGS_ACTIVITY/WINDOW records. RESUMED plus missing app window suggests window/fixture interference; changed lifecycle/finishing needs Activity/AMS investigation. If crash recovery fails: compare pre-java-crash-services.log with process/exit-info/service/window/logcat diagnostics. SIGKILL success alone cannot satisfy the separate Java-crash requirement. A scheduled AMS restart/dialog block is an OS path; a replacement process with failed startup or missing FGS needs application investigation. Preserve failure in either case.
