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
package io.uhndata.iap.build;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.json.JsonException;

import org.apache.sling.jcr.contentloader.ContentCreator;
import org.apache.sling.jcr.contentloader.internal.readers.JsonReader;

/**
 * Reads every bundle's initial content the way the content loader will, and fails the build if it cannot.
 *
 * <p>This is a verification rather than a test, and it runs in the {@code verify} phase of every build, including
 * one that skips tests. What it guards against is a failure with no earlier symptom: the loader aborts a bundle at
 * the first file it cannot read, so <em>none</em> of that bundle's content is installed and the remaining bad files
 * are never reported. Nothing upstream complains — the reactor, the licence check and the feature analyser are all
 * happy with a file that is valid to them — and the first sign of trouble is an instance that starts and is
 * missing things, or an integration suite timing out on health checks minutes later.</p>
 *
 * <p>It works by handing each file to {@link JsonReader}, the loader's own reader, rather than by describing what
 * that reader accepts. The rules are peculiar enough that a second description of them would be wrong: text is
 * wrapped in braces if it does not already start with one, single quotes are rewritten into double ones by a
 * converter that understands block comments but not line ones, and comment support is switched on through a
 * property only Johnzon honours. Asking the real reader is the only way to stay accurate as it changes.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class InitialContentCheck
{
    /** Where a bundle's initial content lives. Only this tree is read by the content loader. */
    private static final String CONTENT = "src/main/resources/SLING-INF/content";

    private InitialContentCheck()
    {
        // Not instantiable: a check, entered through InitialContentCheckMain
    }

    /**
     * Runs the check over a source tree.
     *
     * @param root the top of the source tree
     * @return what is wrong with the content, empty if the loader can read all of it
     * @throws IOException if the tree cannot be walked
     * @throws IllegalStateException if there is no content to check at all, since a check that quietly found
     *             nothing to check would pass for ever
     */
    public static List<String> run(final Path root) throws IOException
    {
        final List<Path> files = contentFiles(root);
        if (files.size() <= 1) {
            throw new IllegalStateException("Found no initial content under " + root.toAbsolutePath());
        }
        return files.stream()
            .map(file -> complaint(root, file))
            .flatMap(Optional::stream)
            .collect(Collectors.toList());
    }

    /**
     * Reads one file the way the loader will, and reports what went wrong if anything did.
     *
     * @param root the top of the source tree, used to name the file in the complaint
     * @param file the content file to read
     * @return the complaint to report, empty if the loader can read the file
     */
    public static Optional<String> complaint(final Path root, final Path file)
    {
        try (InputStream content = new ByteArrayInputStream(Files.readAllBytes(file))) {
            new JsonReader().parse(content, nowhere());
            return Optional.empty();
        } catch (final Exception e) {
            return unreadable(e)
                ? Optional.of(String.format("  %s%n    %s: %s",
                    root.relativize(file), e.getClass().getSimpleName(), e.getMessage()))
                : Optional.empty();
        }
    }

    /**
     * A creator that records nothing, since this asks whether a file can be <em>read</em>, not whether it could be
     * installed into a repository. The reader hands whatever it parses straight to a creator, so something has to
     * be there to receive it; a proxy answering with nothing keeps that from being a second implementation of the
     * repository.
     *
     * @return a content creator that does nothing at all
     */
    private static ContentCreator nowhere()
    {
        return (ContentCreator) Proxy.newProxyInstance(
            ContentCreator.class.getClassLoader(),
            new Class<?>[] {ContentCreator.class},
            (proxy, method, arguments) -> Boolean.TYPE.equals(method.getReturnType()) ? Boolean.FALSE : null);
    }

    /**
     * Whether a failure means the file itself cannot be read, rather than that a creator recording nothing could
     * not enact it.
     *
     * <p>Content declaring an access control list makes the reader reach for a parent node that only a real
     * repository has. That is this check standing on one leg, not a defect in the content — whereas a JSON failure
     * is the file, and is the thing worth catching.</p>
     *
     * @param failure what the reader threw
     * @return {@code true} if the file is at fault
     */
    private static boolean unreadable(final Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every initial-content file in the source tree, of every module.
     *
     * @param root the top of the source tree
     * @return the files to check
     * @throws IOException if the tree cannot be walked
     */
    public static List<Path> contentFiles(final Path root) throws IOException
    {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree
                .map(path -> path.toString().replace('\\', '/'))
                .filter(path -> path.endsWith(".json"))
                .filter(path -> path.contains(CONTENT))
                // Build output is a copy of what is already being checked
                .filter(path -> !path.contains("/target/"))
                .sorted()
                .map(Path::of)
                .collect(Collectors.toList());
        }
    }
}
