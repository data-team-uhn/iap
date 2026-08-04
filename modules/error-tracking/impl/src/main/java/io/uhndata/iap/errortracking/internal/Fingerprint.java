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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a failure into the things a record is made of: the fingerprint naming it, and the stack trace describing it.
 *
 * <p>
 * The fingerprint identifies the <em>fault</em> — the code path that broke, and what it was asked to do — rather than
 * the <em>incident</em>. It is computed from the classes of the throwable chain, the frames those throwables were
 * thrown at, and the component and operation the caller named. The throwable's message is deliberately left out, and
 * so is everything else that varies with the data: a message that quotes a path would otherwise mint a new permanent
 * record for every path, and since nothing is ever deleted, that turns a single broken code path into an unbounded
 * container. With the message out, the number of records is bounded by the number of ways the build can fail.
 * </p>
 *
 * <p>
 * Frame class names are normalized before hashing, because the JVM numbers the classes it generates: the same lambda
 * is {@code $$Lambda$14} in one run and {@code $$Lambda$27} in the next, and a dynamic proxy or a reflective accessor
 * is numbered per instance. Left alone, those numbers would split one fault into as many records as the JVM felt like
 * making classes.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Fingerprint
{
    /**
     * How deep into the cause chain to look. Well past anything worth distinguishing, and a bound on the work done at
     * a failure site.
     */
    private static final int MAX_CAUSES = 10;

    /**
     * How many frames of each throwable take part. The frames near the throw identify the fault; the ones near the
     * bottom are the container that called it, and are the same for everything.
     */
    private static final int MAX_FRAMES = 50;

    /** How much stack trace is kept. A cyclic {@code StackOverflowError} chain runs to megabytes of repetition. */
    private static final int MAX_TRACE_LENGTH = 64 * 1024;

    /** How a fault's identity is hashed into a node name. Required of every Java platform. */
    private static final String ALGORITHM = "SHA-256";

    /** The prefix of the packages that are ours, used to guess which class is worth blaming. */
    private static final String OWN_PACKAGE = "io.uhndata.iap.";

    /**
     * The parts of a generated class name the JVM varies between runs and between instances: the hexadecimal
     * identity in a lambda's class name, and the counters in {@code $$Lambda$14}, {@code $Proxy17} and
     * {@code GeneratedMethodAccessor42}.
     */
    private static final Pattern GENERATED = Pattern.compile(
        "0x[0-9a-fA-F]+|(?<=\\$\\$Lambda\\$)\\d+|(?<=\\$Proxy)\\d+|(?<=Accessor)\\d+");

    /** Only static methods, no instances. */
    private Fingerprint()
    {
        // Utility class
    }

    /**
     * The fingerprint of a thrown failure.
     *
     * @param error the throwable that was caught
     * @param component which code was running, may be {@code null}
     * @param operation what it was trying to do, may be {@code null}
     * @return a hexadecimal digest, usable as a JCR node name
     */
    static String of(final Throwable error, final String component, final String operation)
    {
        final StringBuilder identity = new StringBuilder();
        identity.append(component).append('\n').append(operation).append('\n');
        // An IdentityHashMap-backed set rather than a HashSet: a throwable's equals() is its identity anyway, but a
        // custom one that consults a broken field would throw here, at the worst possible moment
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSES && seen.add(current); depth++) {
            identity.append(current.getClass().getName()).append('\n');
            appendFrames(identity, current);
            current = current.getCause();
        }
        return digest(identity.toString());
    }

    /**
     * The fingerprint of something found wrong that nothing was thrown for. There are no frames to go on, so the
     * whole identity is what the caller said: where it was noticed, doing what, and what was wrong.
     *
     * @param problem what is wrong, a phrase chosen in code
     * @param component which code noticed, may be {@code null}
     * @param operation what it was doing, may be {@code null}
     * @return a hexadecimal digest, usable as a JCR node name
     */
    static String ofProblem(final String problem, final String component, final String operation)
    {
        return digest(component + "\n" + operation + "\n" + problem);
    }

    /**
     * The stack trace of a throwable, causes included, as it would be printed to a log file.
     *
     * @param error the throwable to print
     * @return a multi-line string, truncated when enormous
     */
    static String print(final Throwable error)
    {
        final StringWriter trace = new StringWriter();
        try (PrintWriter writer = new PrintWriter(trace)) {
            error.printStackTrace(writer);
        }
        final String printed = trace.toString();
        return printed.length() <= MAX_TRACE_LENGTH ? printed
            : printed.substring(0, MAX_TRACE_LENGTH) + "\n\t[... trace truncated]\n";
    }

    /**
     * Guesses which of our classes to blame for a failure the caller did not name a component for: the topmost frame
     * belonging to us. Deterministic given the trace, so a guessed component is as stable a part of the fingerprint
     * as a stated one.
     *
     * @param error the throwable to inspect
     * @return a class name, or {@code null} when no frame of the trace is ours
     */
    static String inferComponent(final Throwable error)
    {
        for (final StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().startsWith(OWN_PACKAGE)) {
                return frame.getClassName();
            }
        }
        return null;
    }

    /**
     * Appends one throwable's frames to the identity being built.
     *
     * @param identity the identity under construction
     * @param error the throwable whose frames to append
     */
    private static void appendFrames(final StringBuilder identity, final Throwable error)
    {
        final StackTraceElement[] frames = error.getStackTrace();
        final int count = Math.min(frames.length, MAX_FRAMES);
        for (int i = 0; i < count; i++) {
            identity.append(GENERATED.matcher(frames[i].getClassName()).replaceAll(""))
                .append('#').append(frames[i].getMethodName())
                .append(':').append(frames[i].getLineNumber())
                .append('\n');
        }
    }

    /**
     * Hashes an identity into a node name. A digest rather than the identity itself, so that "have I already recorded
     * this?" is a lookup of one child by name, with no query and no index to maintain.
     *
     * @param identity everything that distinguishes this fault from another
     * @return a hexadecimal digest
     */
    private static String digest(final String identity)
    {
        return digest(identity, ALGORITHM);
    }

    /**
     * Hashes an identity with a named algorithm, falling back to a weaker name when the platform does not offer it.
     * A record under a lesser name is worth a great deal more than no record at all, so this never fails.
     *
     * @param identity everything that distinguishes this fault from another
     * @param algorithm the digest algorithm to name it with
     * @return a hexadecimal digest
     */
    static String digest(final String identity, final String algorithm)
    {
        try {
            final MessageDigest digest = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            return HexFormat.of().toHexDigits(identity.hashCode());
        }
    }
}
