package com.copybot.plugin.api;

import com.copybot.engine.CopybotEngine;
import com.copybot.engine.plugin.ConfirmationHandler;
import com.copybot.engine.plugin.Confirmations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The questions of the plugins to the user (spec remote §2). */
@Timeout(30)
public class UserConfirmationTest {

    @AfterEach
    public void noHandler() {
        CopybotEngine.setConfirmationHandler(null);
    }

    @Test
    public void withoutHandlerTheAnswerIsNo() {
        CopybotEngine.setConfirmationHandler(null);
        assertFalse(UserConfirmation.ask("Trust host?"));
    }

    @Test
    public void theHandlerGetsTheQuestionAndGivesTheAnswer() {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        CopybotEngine.setConfirmationHandler(question -> {
            asked.add(question);
            return CompletableFuture.completedFuture(question.startsWith("yes"));
        });
        assertTrue(UserConfirmation.ask("yes please?"));
        assertFalse(UserConfirmation.ask("no thanks?"));
        assertEquals(List.of("yes please?", "no thanks?"), asked);
    }

    @Test
    public void setReturnsTheHandlerItReplaces() {
        ConfirmationHandler first = question -> CompletableFuture.completedFuture(true);
        assertNull(CopybotEngine.setConfirmationHandler(first));
        assertSame(first, CopybotEngine.setConfirmationHandler(null));
    }

    @Test
    public void aBlankQuestionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> UserConfirmation.ask(" "));
        assertThrows(IllegalArgumentException.class, () -> UserConfirmation.ask(null));
    }

    @Test
    public void aFailingOrCancelledAnswerIsNo() {
        Logger logger = Logger.getLogger(Confirmations.class.getName());
        List<LogRecord> logged = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        logger.setUseParentHandlers(false); // the expected warnings are checked, not printed
        try {
            CopybotEngine.setConfirmationHandler(question -> CompletableFuture.failedFuture(new IllegalStateException("x")));
            assertFalse(UserConfirmation.ask("q?"));
            CopybotEngine.setConfirmationHandler(question -> {
                throw new IllegalStateException("broken");
            });
            assertFalse(UserConfirmation.ask("q?"));
            CopybotEngine.setConfirmationHandler(question -> {
                CompletableFuture<Boolean> answer = new CompletableFuture<>();
                answer.cancel(false);
                return answer;
            });
            assertFalse(UserConfirmation.ask("q?"));
            CopybotEngine.setConfirmationHandler(question -> null);
            assertFalse(UserConfirmation.ask("q?"));
        } finally {
            logger.removeHandler(capture);
            logger.setUseParentHandlers(true);
        }
        assertEquals(2, logged.size(), "the failing handler and the failed answer are logged");
        assertTrue(logged.stream().allMatch(r -> r.getLevel() == Level.WARNING));
    }

    @Test
    public void aThreadThatMustNotWaitGetsNoWithoutBeingAsked() {
        AtomicInteger asked = new AtomicInteger();
        CopybotEngine.setConfirmationHandler(new ConfirmationHandler() {
            @Override
            public CompletableFuture<Boolean> ask(String question) {
                asked.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public boolean canWaitOnCurrentThread() {
                return false;
            }
        });
        assertFalse(UserConfirmation.ask("q?"));
        assertEquals(0, asked.get());
    }

    @Test
    public void theQuestionsComeOneAtATime() throws Exception {
        LinkedBlockingQueue<CompletableFuture<Boolean>> pending = new LinkedBlockingQueue<>();
        AtomicInteger open = new AtomicInteger();
        AtomicInteger maxOpen = new AtomicInteger();
        CopybotEngine.setConfirmationHandler(question -> {
            maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            answer.whenComplete((v, e) -> open.decrementAndGet());
            pending.add(answer);
            return answer;
        });
        List<Boolean> answers = Collections.synchronizedList(new ArrayList<>());
        Thread first = Thread.ofVirtual().start(() -> answers.add(UserConfirmation.ask("first?")));
        Thread second = Thread.ofVirtual().start(() -> answers.add(UserConfirmation.ask("second?")));

        CompletableFuture<Boolean> one = pending.poll(5, TimeUnit.SECONDS);
        assertNotNull(one);
        Thread.sleep(200);
        assertTrue(pending.isEmpty(), "the second question waits for the answer to the first one");
        one.complete(true);
        CompletableFuture<Boolean> two = pending.poll(5, TimeUnit.SECONDS);
        assertNotNull(two, "the second question comes once the first one is answered");
        two.complete(false);
        first.join();
        second.join();

        assertEquals(1, maxOpen.get());
        assertEquals(2, answers.size());
        assertTrue(answers.contains(true) && answers.contains(false));
    }

    @Test
    public void anInterruptWhileWaitingWithdrawsTheQuestionAndAnswersNo() throws Exception {
        AtomicReference<CompletableFuture<Boolean>> pending = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        CopybotEngine.setConfirmationHandler(question -> {
            CompletableFuture<Boolean> answer = new CompletableFuture<>(); // never answered
            pending.set(answer);
            asked.countDown();
            return answer;
        });
        AtomicBoolean answer = new AtomicBoolean(true);
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread asker = Thread.ofVirtual().start(() -> {
            answer.set(UserConfirmation.ask("q?"));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(asked.await(5, TimeUnit.SECONDS));

        asker.interrupt();
        asker.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(asker.isAlive());
        assertFalse(answer.get());
        assertTrue(pending.get().isCancelled(), "the question is withdrawn");
        assertTrue(stillInterrupted.get(), "the interrupt is left for the plugin to see");
    }

    @Test
    public void anInterruptWhileWaitingForAnotherQuestionAnswersNoWithoutAsking() throws Exception {
        CompletableFuture<Boolean> firstAnswer = new CompletableFuture<>();
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch firstAsked = new CountDownLatch(1);
        CopybotEngine.setConfirmationHandler(question -> {
            asked.incrementAndGet();
            firstAsked.countDown();
            return firstAnswer;
        });
        Thread first = Thread.ofVirtual().start(() -> UserConfirmation.ask("first?"));
        assertTrue(firstAsked.await(5, TimeUnit.SECONDS));
        AtomicBoolean answer = new AtomicBoolean(true);
        Thread second = Thread.ofVirtual().start(() -> answer.set(UserConfirmation.ask("second?")));
        Thread.sleep(100);

        second.interrupt();
        second.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(second.isAlive());
        assertFalse(answer.get());
        assertEquals(1, asked.get(), "the second question was never put");
        firstAnswer.complete(true);
        first.join();
    }

    @Test
    public void insideADecliningScopeTheAnswerIsNoWithoutAskingAndOtherThreadsAreNotAffected() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        CopybotEngine.setConfirmationHandler(question -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        });
        CountDownLatch otherDone = new CountDownLatch(1);
        AtomicBoolean other = new AtomicBoolean();
        AtomicBoolean marker = new AtomicBoolean();

        boolean declined = Confirmations.declineDuring(marker, () -> {
            Thread.ofVirtual().start(() -> {
                other.set(UserConfirmation.ask("elsewhere?")); // a thread of its own: not in the scope
                otherDone.countDown();
            });
            assertTrue(otherDone.await(5, TimeUnit.SECONDS));
            assertFalse(marker.get(), "nothing declined yet");
            return UserConfirmation.ask("in the sample?");
        });
        AtomicBoolean task = new AtomicBoolean(true);
        Thread wrapped = Thread.ofVirtual().start(Confirmations.declining(() -> task.set(UserConfirmation.ask("q?"))));
        wrapped.join();

        assertFalse(declined);
        assertTrue(marker.get(), "the operation is told a question was declined");
        assertFalse(task.get());
        assertTrue(other.get());
        assertEquals(1, asked.get(), "only the question from outside the scope was put");
        assertTrue(UserConfirmation.ask("after?"), "the scope ends with its operation");
    }

    @Test
    public void aTaskWrappedInADecliningOperationTellsThatOperation() throws Exception {
        AtomicBoolean marker = new AtomicBoolean();
        Thread task = Confirmations.declineDuring(marker,
                () -> Thread.ofVirtual().unstarted(Confirmations.declining(() -> UserConfirmation.ask("listing?"))));
        assertFalse(marker.get());
        task.start(); // after the operation: the marker of the operation is still the one set
        task.join();
        assertTrue(marker.get());
    }

    @Test
    public void theHandlerIsReadAgainOnceTheTurnComes() throws Exception {
        CompletableFuture<Boolean> firstAnswer = new CompletableFuture<>();
        CountDownLatch firstAsked = new CountDownLatch(1);
        CopybotEngine.setConfirmationHandler(question -> {
            firstAsked.countDown();
            return firstAnswer;
        });
        Thread first = Thread.ofVirtual().start(() -> UserConfirmation.ask("first?"));
        assertTrue(firstAsked.await(5, TimeUnit.SECONDS));
        AtomicInteger askedAfter = new AtomicInteger();
        AtomicBoolean answer = new AtomicBoolean(true);
        Thread second = Thread.ofVirtual().start(() -> answer.set(UserConfirmation.ask("second?")));
        Thread.sleep(100); // the second question waits for its turn
        CopybotEngine.setConfirmationHandler(new ConfirmationHandler() {
            @Override
            public CompletableFuture<Boolean> ask(String question) {
                askedAfter.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public boolean canWaitOnCurrentThread() {
                return false;
            }
        });
        firstAnswer.complete(true);
        first.join();
        second.join();

        assertFalse(answer.get(), "the new handler does not let this thread wait");
        assertEquals(0, askedAfter.get());
    }
}
