# Notification polling survives web failures

**Goal**: Keep Eng Hub notification polling alive when a transient web/network failure occurs so notifications recover without restarting the app.

**Context**:
- The observed failure was `EngHubViewModelCommon - Error polling notifications` followed by `java.nio.channels.UnresolvedAddressException` from Ktor CIO networking.
- Notification polling is implemented in `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`. The polling flow runs a `while (true)` loop, but `.catch { ... emit(Result.failure(e)) }` is outside that loop, so an uncaught polling exception emits one failure and completes the flow.
- Notification polling currently retries only `POLLING_RETRY_COUNT = 5` times before the outer catch. That still allows the notification stream to stop permanently after repeated failures.
- Retriable polling classification is shared through `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PollingRetry.kt` and `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PollingRetry.jvm.kt`; the JVM implementation currently treats only `IOException` as retriable, while `UnresolvedAddressException` is not an `IOException`.
- Existing notification ViewModel tests live under `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/`, with notification API fakes in `EngHubNotificationPersistenceViewModelTest.kt` and `EngHubViewModelTestFixtures.kt`.

## Acceptance tests

1. **Notification polling recovers after a transient notification-list web failure**
   - Given Eng Hub is observing notifications, and the notification API first fails with `UnresolvedAddressException`, and the next poll returns notification `thread-1234`,
   - When the notification flow is collected without recreating the ViewModel,
   - Then the flow first exposes an error state and later exposes a successful notification list containing `thread-1234`.

## Stories

### 1. Keep notification polling alive after a transient web failure

**Acceptance criteria:** Given Eng Hub is observing notifications, and the notification API first fails with `UnresolvedAddressException`, and the next poll returns notification `thread-1234`, when the notification flow is collected without recreating the ViewModel, then the flow first exposes an error state and later exposes a successful notification list containing `thread-1234`.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`
- `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PollingRetry.jvm.kt` if the fix classifies `UnresolvedAddressException` as a web/retriable polling failure
- `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationEnrichmentViewModelTest.kt` or a new focused notification polling test file
- `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt` if `NotificationPersistenceGitHubApi` needs sequential list-failure behavior for the test fake

**Scope:**
- In: notification polling continues after transient network/web exceptions from the notification poll cycle; users see recovery when a later poll succeeds.
- In: preserve cancellation semantics; coroutine cancellation must still cancel rather than being swallowed as an ordinary failure.
- In: test the regression with a deterministic fake API that fails once and then succeeds.
- Out: changing pull-request polling behavior unless we explicitly decide to apply the same resilience there.
- Out: backoff tuning, offline banners, retry counters, or UI polish beyond existing `Result.failure` handling.

**Notes:**
- The least risky implementation is likely to handle exceptions per poll iteration inside `NotificationPolling.kt`: log/emit `Result.failure`, delay for `config.pollIntervalMs`, and continue the `while (true)` loop. Keep `CancellationException` rethrown.
- If the implementation keeps using `.retry`, update JVM retriable classification so `java.nio.channels.UnresolvedAddressException` is handled as a web failure. Be careful: the outer `.catch` must not be terminal if the desired behavior is continuous recovery.
- This is intentionally limited to notifications. Pull-request polling has a similar outer `.catch` pattern in `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PullRequestPolling.kt`, but expanding scope doubles the regression surface and should be a separate story if wanted.

## Open questions

1. Should `UnresolvedAddressException` be treated as recoverable for **all** polling flows, or only notifications for this PR?
2. When a notification poll fails, should the UI continue showing the last successful notification list instead of replacing it with an error state? The current API shape supports `Result.failure`; keeping last-good data would be a separate user-visible behavior change.
3. Is the web failure definitely from `listNotifications`, or could it be from `processNotification`/PR enrichment? The per-iteration catch would cover the full notification poll cycle, but the test can target the most likely source unless we need separate scenarios.

## Original failure log

```text
19:26:01.441 ERROR com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModelCommon - Error polling notifications
java.nio.channels.UnresolvedAddressException: null
at java.base/sun.nio.ch.Net.checkAddress(Net.java:137) ~[?:?]
at java.base/sun.nio.ch.Net.checkAddress(Net.java:145) ~[?:?]
at java.base/sun.nio.ch.SocketChannelImpl.checkRemote(SocketChannelImpl.java:842) ~[?:?]
at java.base/sun.nio.ch.SocketChannelImpl.connect(SocketChannelImpl.java:865) ~[?:?]
at io.ktor.network.sockets.SocketImpl.connect$ktor_network(SocketImpl.kt:44) ~[ktor-network-jvm-3.5.0.jar:3.5.0]
at io.ktor.network.sockets.ConnectUtilsJvmKt.tcpConnect(ConnectUtilsJvm.kt:21) ~[ktor-network-jvm-3.5.0.jar:3.5.0]
at io.ktor.network.sockets.TcpSocketBuilder.connect(TcpSocketBuilder.kt:48) ~[ktor-network-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.cio.ConnectionFactory.connect(ConnectionFactory.kt:30) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.cio.Endpoint$connect$2$connect$1.invokeSuspend(Endpoint.kt:215) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.cio.Endpoint$connect$2$connect$1.invoke(Endpoint.kt) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.cio.Endpoint$connect$2$connect$1.invoke(Endpoint.kt) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatched(Undispatched.kt:66) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturnIgnoreTimeout(Undispatched.kt:50) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.TimeoutKt.setupTimeout(Timeout.kt:233) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.TimeoutKt.withTimeoutOrNull(Timeout.kt:159) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at io.ktor.client.engine.cio.Endpoint.connect(Endpoint.kt:223) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.cio.Endpoint.makeDedicatedRequest(Endpoint.kt:102) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.cio.Endpoint.execute(Endpoint.kt:67) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.cio.CIOEngine.execute(CIOEngine.kt:89) ~[ktor-client-cio-jvm-3.5.0.jar:3.5.0]
at io.ktor.client.engine.HttpClientEngine$executeWithinCallContext$2.invokeSuspend(HttpClientEngine.kt:183) ~[ktor-client-core-jvm-3.5.0.jar:3.5.0]
at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34) ~[kotlin-stdlib-2.3.21.jar:2.3.21-release-298]
at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.internal.LimitedDispatcher$Worker.run(LimitedDispatcher.kt:124) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.scheduling.TaskImpl.run(Tasks.kt:89) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:586) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:798) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:717) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:704) ~[kotlinx-coroutines-core-jvm-1.11.0.jar:1.11.0]
```
