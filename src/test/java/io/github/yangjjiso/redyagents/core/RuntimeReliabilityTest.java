package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

@Timeout(10)
class RuntimeReliabilityTest {
    private static final AgentConfig AGENT = new AgentConfig("test", "demo", "");

    @Test
    void safeRecoveryBacklogCannotExhaustStartup() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, byte[]> data = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String id = "sess_recovery_" + i;
            String turnId = "turn_recovery_" + i;
            Instant now = Instant.now();
            Session session = new Session(id, AGENT, "in_progress", turnId, now, now);
            Turn turn = new Turn(turnId, id, "recover", "in_progress", "", "", now, null);
            List<Message> messages = List.of(new Message("user", "recover"));
            data.put(id, json.writeValueAsBytes(new SessionSnapshot(session, List.of(turn), List.of(),
                    messages, List.of(), List.of(), new LoopCheckpoint(messages, 0, Map.of(), Map.of(), null),
                    Map.of(), List.of(), true)));
        }
        SessionStore store = new SessionStore() {
            public synchronized void save(String id, byte[] snapshot) { data.put(id, snapshot); }
            public synchronized Map<String, byte[]> loadAll() { return new LinkedHashMap<>(data); }
        };
        CountDownLatch release = new CountDownLatch(1);
        Model model = (token, agent, messages) -> {
            release.await();
            return Decision.finalMessage("recovered");
        };
        try (AgentService service = new AgentService(new LoopRunner(model), store, null,
                new RuntimeLimits(3, 1, 1, Duration.ofSeconds(5), 2))) {
            assertEquals("failed", service.getTurn("sess_recovery_2", "turn_recovery_2").status());
            release.countDown();
            assertEquals("completed", awaitTerminal(service, "sess_recovery_0", "turn_recovery_0").status());
            assertEquals("completed", awaitTerminal(service, "sess_recovery_1", "turn_recovery_1").status());
        } finally {
            release.countDown();
        }
    }

    @Test
    void deadlineFailsAndPairsInterruptedToolThenReusesWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        Tool slow = (cancellation, arguments) -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return "unreachable";
        };
        Model model = (cancellation, agent, messages) ->
                "quick".equals(messages.get(messages.size() - 1).content())
                        ? Decision.finalMessage("ok")
                        : Decision.toolCall("wait", new ToolCall("slow", "{}".getBytes(StandardCharsets.UTF_8), "call_slow"));
        RuntimeLimits limits = new RuntimeLimits(3, 1, 1, Duration.ofMillis(250), 2);
        try (AgentService service = new AgentService(new LoopRunner(model, Map.of("slow", slow), 3),
                null, null, limits)) {
            String id = service.createSession(AGENT).id();
            Turn slowTurn = service.startTurn(id, "slow");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Turn terminal = awaitTerminal(service, id, slowTurn.id());
            assertEquals("failed", terminal.status());
            assertEquals("turn execution timed out", terminal.error());
            assertEquals(1, service.items(id).stream().filter(item ->
                    "function_call_output".equals(item.type()) && Boolean.FALSE.equals(item.success())).count());
            Turn quick = service.startTurn(id, "quick");
            assertEquals("completed", awaitTerminal(service, id, quick.id()).status());
        }
    }

    @Test
    void overloadIsExplicitAndNeverExecutesRejectedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Runner runner = (cancellation, session, history, input, emit) -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return input;
        };
        try (AgentService service = new AgentService(runner, null, null,
                new RuntimeLimits(3, 1, 1, Duration.ofSeconds(5), 2))) {
            String a = service.createSession(AGENT).id();
            String b = service.createSession(AGENT).id();
            String c = service.createSession(AGENT).id();
            Turn first = service.startTurn(a, "first");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Turn queued = service.startTurn(b, "queued");
            AgentException error = assertThrows(AgentException.class, () -> service.startTurn(c, "rejected"));
            assertEquals(AgentException.Reason.UNAVAILABLE, error.reason());
            assertEquals(c, error.sessionId());
            assertEquals(service.listTurns(c).get(0).id(), error.turnId());
            assertEquals("failed", service.listTurns(c).get(0).status());
            assertEquals("idle", service.getSession(c).status());
            assertEquals(1, calls.get());
            AgentException initial = assertThrows(AgentException.class,
                    () -> service.createSession(AGENT, "initial input"));
            assertNotNull(initial.sessionId(), "overloaded creation must expose the newly persisted session");
            assertEquals("failed", service.getTurn(initial.sessionId(), initial.turnId()).status());
            release.countDown();
            assertEquals("completed", awaitTerminal(service, a, first.id()).status());
            assertEquals("completed", awaitTerminal(service, b, queued.id()).status());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void externalWaitDoesNotConsumeDeadlineAndContinuationGetsFreshBudget() throws Exception {
        Model model = (cancellation, agent, messages) -> "tool".equals(messages.get(messages.size() - 1).role())
                ? Decision.finalMessage("approved")
                : Decision.toolCall("approval", new ToolCall("approval", "{}".getBytes(StandardCharsets.UTF_8), "call_approval"));
        try (AgentService service = new AgentService(new LoopRunner(model,
                Map.of("approval", new ExternalFunctionTool() {}), 3), null, null,
                new RuntimeLimits(3, 1, 0, Duration.ofMillis(200), 1))) {
            String id = service.createSession(AGENT).id();
            Turn turn = service.startTurn(id, "approve");
            awaitStatus(service, id, turn.id(), "waiting");
            Thread.sleep(350);
            assertEquals("requires_action", service.getSession(id).status());
            service.submitToolResult(id, turn.id(), "call_approval", true, "yes", null);
            assertEquals("completed", awaitTerminal(service, id, turn.id()).status());
        }
    }

    @Test
    void eventSubscriberLimitRecoversWhenClientCloses() throws Exception {
        try (AgentService service = new AgentService(new LoopRunner(new DemoModel()), null, null,
                new RuntimeLimits(3, 1, 1, Duration.ofSeconds(5), 1))) {
            String id = service.createSession(AGENT).id();
            CountDownLatch delivered = new CountDownLatch(1);
            EventSubscription first = service.subscribe(id, 0, event -> delivered.countDown());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            AgentException error = assertThrows(AgentException.class, () -> service.subscribe(id, 0, ignored -> {}));
            assertEquals(AgentException.Reason.UNAVAILABLE, error.reason());
            first.close();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (true) {
                try (EventSubscription next = service.subscribe(id, 0, ignored -> {})) {
                    break;
                } catch (AgentException busy) {
                    if (System.nanoTime() >= deadline) throw busy;
                    Thread.sleep(10);
                }
            }
        }
    }

    static Turn awaitTerminal(AgentService service, String session, String turn) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < deadline) {
            Turn current = service.getTurn(session, turn);
            if (List.of("completed", "failed", "cancelled").contains(current.status())) return current;
            Thread.sleep(5);
        }
        throw new AssertionError("turn did not finish: " + service.getTurn(session, turn));
    }

    private static void awaitStatus(AgentService service, String session, String turn, String status)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (status.equals(service.getTurn(session, turn).status())) return;
            Thread.sleep(5);
        }
        throw new AssertionError("turn did not reach " + status);
    }
}
