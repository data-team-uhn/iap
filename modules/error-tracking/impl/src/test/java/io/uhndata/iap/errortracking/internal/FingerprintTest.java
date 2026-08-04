/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.errortracking.internal;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Fingerprint}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class FingerprintTest
{
    @Test
    void isAHexadecimalNameANodeCanTake()
    {
        final String fingerprint = Fingerprint.of(new IllegalStateException("boom"), "some.Thing", "doIt");

        assertTrue(fingerprint.matches("[0-9a-f]{64}"), fingerprint);
    }

    @Test
    void theSameFaultFingerprintsTheSameWay()
    {
        final Throwable error = new IllegalStateException("boom");

        assertEquals(Fingerprint.of(error, "some.Thing", "doIt"), Fingerprint.of(error, "some.Thing", "doIt"));
    }

    @Test
    void theMessageDoesNotDecideIdentity()
    {
        // The whole point: the same broken line reporting two different paths is one fault, not two. Both are
        // thrown from one call site, since throwing them from two would be two faults quite correctly
        final Throwable[] both = new Throwable[2];
        for (int i = 0; i < both.length; i++) {
            both[i] = failHere("Cannot read /Submissions/" + i);
        }

        assertEquals(Fingerprint.of(both[0], null, null), Fingerprint.of(both[1], null, null));
    }

    @Test
    void whereItWasThrownDoesDecideIdentity()
    {
        assertNotEquals(Fingerprint.of(failHere("boom"), null, null),
            Fingerprint.of(failElsewhere("boom"), null, null));
    }

    @Test
    void whatWasThrownDecidesIdentity()
    {
        assertNotEquals(Fingerprint.of(new IllegalStateException("boom"), "some.Thing", "doIt"),
            Fingerprint.of(new IllegalArgumentException("boom"), "some.Thing", "doIt"));
    }

    @Test
    void theComponentAndTheOperationDecideIdentity()
    {
        final Throwable error = new IllegalStateException("boom");

        assertNotEquals(Fingerprint.of(error, "some.Thing", "doIt"),
            Fingerprint.of(error, "some.Other", "doIt"));
        assertNotEquals(Fingerprint.of(error, "some.Thing", "doIt"),
            Fingerprint.of(error, "some.Thing", "doSomethingElse"));
    }

    @Test
    void theCausesDecideIdentityToo()
    {
        final Throwable plain = failHere("boom");
        final Throwable caused = new IllegalStateException("boom", new IllegalArgumentException("inner"));

        assertNotEquals(Fingerprint.of(plain, null, null), Fingerprint.of(caused, null, null));
    }

    @Test
    void survivesACauseChainThatLoopsBackOnItself()
    {
        final Throwable first = new IllegalStateException("first");
        final Throwable second = new IllegalStateException("second", first);
        first.initCause(second);

        assertNotNull(Fingerprint.of(first, null, null));
    }

    @Test
    void doesNotSplitAFaultOverTheNumbersTheJvmMakesUp()
    {
        // A lambda is $$Lambda$14 in one run and $$Lambda$27 in the next; left alone, that would make one fault
        // look like as many faults as the JVM felt like making classes
        final Throwable first = withFrame("some.Thing$$Lambda$14/0x00007f0badc0ffee");
        final Throwable second = withFrame("some.Thing$$Lambda$29/0x00007f0bdeadbeef");

        assertEquals(Fingerprint.of(first, null, null), Fingerprint.of(second, null, null));
    }

    @Test
    void doesNotSplitAFaultOverProxyOrAccessorNumbering()
    {
        assertEquals(Fingerprint.of(withFrame("com.sun.proxy.$Proxy17"), null, null),
            Fingerprint.of(withFrame("com.sun.proxy.$Proxy93"), null, null));
        assertEquals(Fingerprint.of(withFrame("jdk.internal.reflect.GeneratedMethodAccessor42"), null, null),
            Fingerprint.of(withFrame("jdk.internal.reflect.GeneratedMethodAccessor7"), null, null));
    }

    @Test
    void fallsBackToWhatTheCallerSaidWhenThereAreNoFramesAtAll()
    {
        // A throwable built without a writable stack trace has nothing to go on but the caller's own labels
        final Throwable first = noStackTrace();
        final Throwable second = noStackTrace();

        assertEquals(Fingerprint.of(first, "some.Thing", "doIt"), Fingerprint.of(second, "some.Thing", "doIt"));
        assertNotEquals(Fingerprint.of(first, "some.Thing", "doIt"),
            Fingerprint.of(second, "some.Thing", "doSomethingElse"));
    }

    @Test
    void aProblemIsNamedAfterWhatIsWrongAndWhereItWasNoticed()
    {
        assertEquals(Fingerprint.ofProblem("unknown comparator", "some.Thing", "evaluate"),
            Fingerprint.ofProblem("unknown comparator", "some.Thing", "evaluate"));
        assertNotEquals(Fingerprint.ofProblem("unknown comparator", "some.Thing", "evaluate"),
            Fingerprint.ofProblem("unknown operand source", "some.Thing", "evaluate"));
        assertNotEquals(Fingerprint.ofProblem("unknown comparator", "some.Thing", "evaluate"),
            Fingerprint.ofProblem("unknown comparator", "some.Other", "evaluate"));
    }

    @Test
    void printsTheTraceTheWayALogFileWould()
    {
        final String printed = Fingerprint.print(
            new IllegalStateException("outer", new IllegalArgumentException("inner")));

        assertTrue(printed.startsWith("java.lang.IllegalStateException: outer"));
        assertTrue(printed.contains("Caused by: java.lang.IllegalArgumentException: inner"));
        assertTrue(printed.contains("FingerprintTest"));
    }

    @Test
    void cutsAnEnormousTraceShort()
    {
        // A cyclic StackOverflowError chain runs to megabytes of the same few frames
        final Throwable enormous = new IllegalStateException("boom");
        final StackTraceElement[] frames = new StackTraceElement[20000];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("some.Deeply.Nested.Thing", "recurse", "Thing.java", i);
        }
        enormous.setStackTrace(frames);

        final String printed = Fingerprint.print(enormous);

        assertTrue(printed.length() < 70_000, "kept " + printed.length() + " characters");
        assertTrue(printed.endsWith("[... trace truncated]\n"));
    }

    @Test
    void guessesTheTopmostOfOurClasses()
    {
        assertEquals("io.uhndata.iap.errortracking.internal.FingerprintTest",
            Fingerprint.inferComponent(new IllegalStateException("boom")));
    }

    @Test
    void guessesNothingWhenNoneOfTheTraceIsOurs()
    {
        assertNull(Fingerprint.inferComponent(withFrame("java.util.HashMap")));
    }

    @Test
    void stillNamesAFaultWhenThePlatformCannotHashItProperly()
    {
        // A record under a lesser name is worth a great deal more than no record at all
        final String named = Fingerprint.digest("some identity", "NO-SUCH-ALGORITHM");

        assertNotNull(named);
        assertEquals(named, Fingerprint.digest("some identity", "NO-SUCH-ALGORITHM"));
        assertNotEquals(named, Fingerprint.digest("another identity", "NO-SUCH-ALGORITHM"));
    }

    @Test
    void isAUtilityClass() throws ReflectiveOperationException
    {
        final Constructor<Fingerprint> constructor = Fingerprint.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertNotNull(constructor.newInstance());
    }

    /**
     * A throwable thrown from one particular line, so that two of them share their frames exactly.
     *
     * @param message what to say
     * @return the throwable
     */
    private static Throwable failHere(final String message)
    {
        return new IllegalStateException(message);
    }

    /**
     * A throwable thrown from a different line to {@link #failHere}.
     *
     * @param message what to say
     * @return the throwable
     */
    private static Throwable failElsewhere(final String message)
    {
        return new IllegalStateException(message);
    }

    /**
     * A throwable whose trace is exactly one frame, in the given class.
     *
     * @param className the class the single frame is in
     * @return the throwable
     */
    private static Throwable withFrame(final String className)
    {
        final Throwable error = new IllegalStateException("boom");
        error.setStackTrace(new StackTraceElement[] {
            new StackTraceElement(className, "run", "Thing.java", 1)
        });
        return error;
    }

    /**
     * A throwable with no frames at all, as produced when stack traces are switched off.
     *
     * @return the throwable
     */
    private static Throwable noStackTrace()
    {
        final Throwable error = new IllegalStateException("boom");
        error.setStackTrace(new StackTraceElement[0]);
        return error;
    }
}
