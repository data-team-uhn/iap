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
package io.uhndata.iap.extraction.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.submissions.models.Chunk;

/**
 * Chunk content utilities.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ChunkContent
{
    /**
     * Roughly how many characters make a token. Only ever used to decide how much text will fit, so an estimate
     * this rough is enough, and it costs nothing next to tokenizing.
     */
    static final int CHARS_PER_TOKEN = 4;

    /** An ATX heading: up to six hashes, a space, then the title. */
    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(\\S.{0,300})$");

    /** A page marker the parser leaves behind, which says nothing about where a section starts. */
    private static final Pattern PAGE_MARKER = Pattern.compile("^<!--\\s{0,10}page:\\s{0,10}\\d{1,9}\\s{0,10}-->$");

    /** A horizontal rule, which separates without titling anything. */
    private static final Pattern RULE_LINE = Pattern.compile("^[-*_]{3,300}$");

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_DATA = "jcr:data";

    private ChunkContent()
    {
        // Utility
    }

    /**
     * Read a chunk's Markdown.
     *
     * @param chunk the chunk to read
     * @return the text, or an empty string when the chunk carries none
     * @throws IOException if the stored text cannot be read
     */
    static String readText(final Chunk chunk) throws IOException
    {
        if (chunk == null) {
            return "";
        }
        return readText(chunk.getContent());
    }

    /**
     * Read the text of a stored file.
     *
     * @param file the {@code nt:file} holding it, or {@code null}
     * @return the text, or an empty string when there is no file to read
     * @throws IOException if the stored text cannot be read
     */
    static String readText(final Resource file) throws IOException
    {
        if (file == null) {
            return "";
        }
        final Resource content = file.getChild(JCR_CONTENT);
        if (content == null) {
            return "";
        }
        final InputStream stored = content.getValueMap().get(JCR_DATA, InputStream.class);
        if (stored == null) {
            return "";
        }
        try (InputStream data = stored) {
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data.readAllBytes())).toString();
        }
    }

    /**
     * The nearest ATX heading above a quote, which is where a reader would say the quote lives.
     *
     * <p>The quote is matched after the same normalising the verifier does, because the model rarely copies
     * whitespace exactly. Once the line it starts on is known, this walks back up for the first heading.
     *
     * @param markdown the chunk's text
     * @param quote what the model quoted
     * @return the heading's title, or an empty string when the quote is not there or nothing titles it
     */
    static String findHeadingAbove(final String markdown, final String quote)
    {
        if (markdown == null || quote == null || quote.isBlank()) {
            return "";
        }
        final String[] lines = markdown.split("\n");
        for (int above = findLineOf(lines, quote); above >= 0; above--) {
            final Matcher match = ATX_HEADING.matcher(lines[above].strip());
            if (match.matches()) {
                return match.group(2).strip();
            }
        }
        return "";
    }

    /**
     * Which line a quote starts on. One normalized copy of the text is built, plus how far into it each line
     * ends, so the quote is found once and turned back into a line number without re-normalizing per line.
     *
     * @param lines the chunk's lines
     * @param quote what the model quoted
     * @return the line index, or -1 when the quote is not in the text
     */
    private static int findLineOf(final String[] lines, final String quote)
    {
        final StringBuilder normalized = new StringBuilder();
        final int[] endsAt = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            normalized.append(QuoteVerifier.normalizeForSearch(lines[i])).append(' ');
            endsAt[i] = normalized.length();
        }
        final String wanted = QuoteVerifier.normalizeForSearch(quote).strip();
        final int at = wanted.isEmpty() ? -1 : normalized.toString().indexOf(wanted);
        if (at < 0) {
            return -1;
        }
        int line = 0;
        while (line < lines.length - 1 && endsAt[line] <= at) {
            line++;
        }
        return line;
    }

    /**
     * The headings a chunk holds anywhere in its body, restricted to the two topmost levels present. A
     * section chunk often covers subsections too, and both belong in its name; for a chunk holding
     * {@code ##}, {@code ###} and {@code ####} headings, that keeps the {@code ##} and {@code ###} ones and
     * leaves the {@code ####} out, so a chunk that runs deep does not drown its name in the deepest
     * subsections.
     *
     * <p>This looks at the whole body, whether or not the chunk opens with a heading of its own -
     * {@link #opensWithHeading} is what tells a chunk's caller whether it is a continuation that needs a
     * heading carried over from an earlier chunk, in front of whatever this returns.
     *
     * @param markdown the chunk's text
     * @return the headings, in document order, empty when the chunk holds none
     */
    static List<String> getHeadings(final String markdown)
    {
        if (markdown == null) {
            return List.of();
        }
        final List<Heading> found = new ArrayList<>();
        for (final String line : markdown.split("\n")) {
            final Matcher match = ATX_HEADING.matcher(line.strip());
            if (match.matches()) {
                found.add(new Heading(match.group(1).length(), match.group(2).strip()));
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        final int topLevel = found.stream().mapToInt(Heading::level).min().orElseThrow();
        final int secondLevel = found.stream().mapToInt(Heading::level)
            .filter(level -> level > topLevel).min().orElse(topLevel);
        return found.stream()
            .filter(heading -> heading.level() == topLevel || heading.level() == secondLevel)
            .map(Heading::title)
            .toList();
    }

    /**
     * Whether a chunk opens with its own heading, rather than continuing a section a previous chunk started.
     * A chunk is cut at a heading, so its opening line is checked: blank lines, page markers and rules are
     * passed over, and the first line that says anything either is a heading or means it has none.
     *
     * @param markdown the chunk's text
     * @return {@code true} if the chunk's first non-neutral line is itself an ATX heading
     */
    static boolean opensWithHeading(final String markdown)
    {
        if (markdown == null) {
            return false;
        }
        for (final String line : markdown.split("\n")) {
            final String stripped = line.strip();
            if (isNeutral(stripped)) {
                continue;
            }
            return ATX_HEADING.matcher(stripped).matches();
        }
        return false;
    }

    /**
     * One ATX heading found in a chunk's body.
     *
     * @param level how many hashes it opened with
     * @param title the heading's text
     * @since 0.1.0
     */
    private record Heading(int level, String title)
    {
    }

    /**
     * Roughly how many tokens a piece of text will cost.
     *
     * @param text the text to size, may be {@code null}
     * @return the estimated token count
     */
    static int estimateTokens(final String text)
    {
        return text == null ? 0 : text.length() / CHARS_PER_TOKEN;
    }

    /**
     * Whether a line neither titles anything nor is part of the prose: a blank, a page marker, or a rule.
     *
     * @param stripped the line, already trimmed
     * @return {@code true} when the line should be passed over
     */
    private static boolean isNeutral(final String stripped)
    {
        return stripped.isEmpty() || PAGE_MARKER.matcher(stripped).matches()
            || RULE_LINE.matcher(stripped).matches();
    }
}
