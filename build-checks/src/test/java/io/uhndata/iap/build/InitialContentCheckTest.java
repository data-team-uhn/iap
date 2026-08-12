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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check's own negative controls. Without these it could pass by accepting everything, which is exactly how a
 * verification stops being one.
 */
class InitialContentCheckTest
{
    @Test
    void catchesAnApostropheInALineComment() throws IOException
    {
        // The loader rewrites single quotes into double quotes before parsing, with a converter that understands
        // block comments and is blind to line ones. An apostrophe here is read as a quote, and everything after it
        // comes out escaped — reported as a stray backslash at a position matching nothing in the file.
        assertTrue(rejects("{\n  // the widget's own header\n  \"a\": 1\n}\n"),
            "An apostrophe in a // comment must be caught");
    }

    @Test
    void catchesAHeaderAboveTheOpeningBrace() throws IOException
    {
        // Text that does not begin with a brace is wrapped in another object, which puts a brace where a key
        // belongs. A copied licence header fails this way whatever comment syntax it uses.
        assertTrue(rejects("// Copyright 2026\n{\n  \"a\": 1\n}\n"),
            "Anything above the opening brace must be caught");
    }

    @Test
    void acceptsTheCommentsTheLoaderActuallySupports() throws IOException
    {
        // Comments are enabled deliberately, so a check that rejected them would fail content that loads
        assertFalse(rejects("{\n  // a plain line comment\n  \"a\": 1\n}\n"), "// comments are supported");
        assertFalse(rejects("{\n  /* a block comment, with an apostrophe's worth of risk */\n  \"a\": 1\n}\n"),
            "/* */ comments are supported, apostrophes and all");
        assertFalse(rejects("{\n  \"url\": \"https://example.org/x\"\n}\n"), "a URL is not a comment");
    }

    @Test
    void refusesToPassWhenItFindsNothingToCheck(@TempDir final Path empty)
    {
        // The failure mode a check like this dies of: pointed at the wrong tree, it finds no files, has no
        // complaints to make, and reports success for ever
        assertThrows(IllegalStateException.class, () -> InitialContentCheck.run(empty));
    }

    @Test
    void readsTheRepositoryItIsRunOver() throws IOException
    {
        // The same walk the build runs, so a change to where content lives fails here rather than silently
        // narrowing what is checked
        assertTrue(InitialContentCheck.contentFiles(root()).size() > 1, "Found no initial content under " + root());
    }

    @Test
    void reportsNothingAboutContentTheLoaderCanRead() throws IOException
    {
        // The check the build actually runs, over the tree it actually runs it on
        assertTrue(InitialContentCheck.run(root()).isEmpty(), "This repository's own content must be readable");
    }

    /**
     * Whether the check refuses the given content.
     *
     * @param content the file's text
     * @return {@code true} if the file is at fault
     * @throws IOException if the temporary file cannot be written
     */
    private boolean rejects(final String content) throws IOException
    {
        final Path file = Files.createTempFile("content", ".json");
        try {
            Files.writeString(file, content);
            return InitialContentCheck.complaint(file.getParent(), file).isPresent();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The top of the source tree, which the build passes in because this reads other modules' sources.
     *
     * @return the repository root
     */
    private static Path root()
    {
        return Path.of(System.getProperty("iap.root", ".."));
    }
}
