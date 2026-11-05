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
 * Turns a failure into what its record is made of: the fingerprint that names it, and its stack trace.
 *
 * <p>
 * The fingerprint identifies the fault, not one occurrence of it. It is a hash of the component and the operation the
 * caller named, the classes of the throwable chain, and the frames each throwable was thrown from. The message is
 * left out, with everything else that varies with the data. A message that quotes a path would otherwise create a
 * record for every path, and records are never deleted.
 * </p>
 *
 * <p>
 * The JVM numbers the classes it generates, so frame class names lose those numbers before hashing. The same lambda
 * is {@code $$Lambda$14} in one run and {@code $$Lambda$27} in the next, and proxies and reflective accessors are
 * numbered as the JVM creates them.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Fingerprint
{
    /** How many throwables of the cause chain take part. */
    private static final int MAX_CAUSES = 10;

    /**
     * How many frames of each throwable take part, from the top. The frames near the top identify the fault, and
     * those at the bottom belong to whatever started the thread.
     */
    private static final int MAX_FRAMES = 50;

    /** How many characters of a stack trace are kept. */
    private static final int MAX_TRACE_LENGTH = 64 * 1024;

    /** The digest that turns a fault's identity into a node name. Every Java platform must provide it. */
    private static final String HASH_ALGORITHM = "SHA-256";

    /** The package prefix of IAP's classes, one of which is taken as the component when the caller names none. */
    private static final String OWN_PACKAGE = "io.uhndata.iap.";

    /**
     * The parts of a generated class name that the JVM varies between runs. They are the hexadecimal address in a
     * hidden class's name, and the counters in {@code $$Lambda$14}, {@code $Proxy17} and
     * {@code GeneratedMethodAccessor42}.
     */
    private static final Pattern GENERATED_ID_PATTERN = Pattern.compile(
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
     * @param component the fully qualified name of the class that was running, or {@code null} if unknown
     * @param operation the label of what it was doing, or {@code null} if unknown
     * @return a hexadecimal digest, usable as a JCR node name
     */
    static String of(final Throwable error, final String component, final String operation)
    {
        final StringBuilder identity = new StringBuilder();
        identity.append(component).append('\n').append(operation).append('\n');
        // By identity, since a throwable that overrides equals() may throw from it. It also stops a cause chain that
        // loops back on itself
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
     * The fingerprint of a problem, which nothing was thrown for. With no frames, it is a hash of what the caller
     * said: where the problem was noticed, doing what, and what is wrong.
     *
     * @param problem the name of the problem, a phrase chosen in code
     * @param component the fully qualified name of the class that noticed it, or {@code null} if unknown
     * @param operation the label of what it was doing, or {@code null} if unknown
     * @return a hexadecimal digest, usable as a JCR node name
     */
    static String ofProblem(final String problem, final String component, final String operation)
    {
        return digest(component + "\n" + operation + "\n" + problem);
    }

    /**
     * The stack trace of a throwable, causes included, as a log file prints it.
     *
     * @param error the throwable to print
     * @return the trace, cut after {@value #MAX_TRACE_LENGTH} characters and then marked as cut
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
     * The component of a failure whose caller named none: the class of the topmost frame in an IAP package. The same
     * trace always gives the same class.
     *
     * @param error the throwable to inspect
     * @return a fully qualified class name, or {@code null} if no frame of the outermost throwable is in an IAP
     *         package
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
     * Appends one throwable's frames to an identity, one {@code class#method:line} per line.
     *
     * @param identity the identity being built
     * @param error the throwable whose frames to append
     */
    private static void appendFrames(final StringBuilder identity, final Throwable error)
    {
        final StackTraceElement[] frames = error.getStackTrace();
        final int count = Math.min(frames.length, MAX_FRAMES);
        for (int i = 0; i < count; i++) {
            identity.append(GENERATED_ID_PATTERN.matcher(frames[i].getClassName()).replaceAll(""))
                .append('#').append(frames[i].getMethodName())
                .append(':').append(frames[i].getLineNumber())
                .append('\n');
        }
    }

    /**
     * Hashes an identity into a node name. Finding the record of a fault is then a lookup of one child by name.
     *
     * @param identity everything that tells this fault from another
     * @return a hexadecimal digest
     */
    private static String digest(final String identity)
    {
        return digest(identity, HASH_ALGORITHM);
    }

    /**
     * Hashes an identity with a named algorithm. If the platform lacks the algorithm, it falls back to the identity's
     * {@code hashCode()}, so that the fault is still recorded.
     *
     * @param identity everything that tells this fault from another
     * @param algorithm the name of a {@link MessageDigest} algorithm, such as {@code SHA-256}
     * @return a hexadecimal digest, or eight hexadecimal digits from the fallback
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
