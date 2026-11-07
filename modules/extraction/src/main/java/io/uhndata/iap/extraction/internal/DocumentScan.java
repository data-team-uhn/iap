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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * A parsed document, prepared once so many quotes can be checked against it. It also knows the heading above
 * each line and, for joined documents, where each one starts.
 *
 * <p>The normalized text must match what {@link QuoteVerifier#normalize} makes of the whole document, or a
 * correct quote would not be found.</p>
 *
 * <p>It cannot be changed, so it is safe to share.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentScan
{
    /** A scan with no text, for a document that has none. */
    static final DocumentScan EMPTY_SCAN = new DocumentScan("");

    /** The document as it was parsed. This is what is sent to the model. */
    private final String text;

    /** The whole document normalized. Quotes are compared against this. */
    private final String normalized;

    /** Where each line ends in {@link #normalized}, used to find which line a position is on. */
    private final int[] lineEndsAt;

    /** Every heading title in the document, in order. */
    private final List<String> headings;

    /** For each line, the index in {@link #headings} of the nearest heading at or above it, or -1 if none. */
    private final int[] headingAbove;

    /** Where each joined document starts in {@link #normalized}, empty for a single document. */
    private final int[] partStartsAt;

    private DocumentScan(final String text)
    {
        this(text, new int[0]);
    }

    /**
     * Prepares a text and records where each joined document starts.
     *
     * @param text the whole text
     * @param partLines the line each joined document starts on, empty for a single document
     */
    private DocumentScan(final String text, final int[] partLines)
    {
        this.text = text;
        final int lines = countLines(text);
        this.lineEndsAt = new int[lines];
        this.headingAbove = new int[lines];
        this.headings = new ArrayList<>();
        final StringBuilder normalizedText = new StringBuilder(text.length());
        int from = 0;
        for (int line = 0; line < lines; line++) {
            final int breakAt = text.indexOf('\n', from);
            final int to = breakAt < 0 ? text.length() : breakAt;
            final String lineText = text.substring(from, to);
            // A blank line adds nothing, or a quote that crosses a paragraph break would never match
            final String normalizedLine = QuoteVerifier.normalize(lineText);
            if (!normalizedLine.isEmpty()) {
                normalizedText.append(normalizedLine).append(' ');
            }
            this.lineEndsAt[line] = normalizedText.length();
            final Matcher isHeading = DocumentText.matchHeading(lineText);
            if (isHeading.matches()) {
                this.headings.add(isHeading.group(1).strip());
            }
            this.headingAbove[line] = this.headings.size() - 1;
            from = to + 1;
        }
        this.normalized = normalizedText.toString();
        this.partStartsAt = new int[partLines.length];
        for (int part = 0; part < partLines.length; part++) {
            this.partStartsAt[part] = partLines[part] == 0 ? 0 : this.lineEndsAt[partLines[part] - 1];
        }
    }

    /**
     * Joins several documents into one text, each under its own top heading. It records where each document
     * starts, so a quote can be traced back to the document it came from.
     *
     * @param headings the heading for each document
     * @param texts the documents, in the same order
     * @return the scan of the joined text
     */
    static DocumentScan join(final List<String> headings, final List<String> texts)
    {
        final StringBuilder text = new StringBuilder();
        final int[] partLines = new int[texts.size()];
        for (int part = 0; part < texts.size(); part++) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            partLines[part] = countLines(text.toString()) - 1;
            text.append("# ").append(headings.get(part)).append("\n\n").append(texts.get(part));
        }
        return new DocumentScan(text.toString(), partLines);
    }

    /**
     * Which joined document a position is in.
     *
     * @param at a position from {@link #locate}
     * @return the document's index, or -1 when the text is a single document or the position is -1
     */
    int getPart(final int at)
    {
        int found = -1;
        for (int part = 0; part < this.partStartsAt.length && at >= 0; part++) {
            if (this.partStartsAt[part] <= at) {
                found = part;
            }
        }
        return found;
    }

    /**
     * Prepares one document for checking quotes against.
     *
     * @param text the parsed document, may be {@code null}
     * @return the scan, or {@link #EMPTY_SCAN} when the text is {@code null} or empty
     */
    static DocumentScan of(final String text)
    {
        return text == null || text.isEmpty() ? EMPTY_SCAN : new DocumentScan(text);
    }

    /**
     * The document as it was parsed.
     *
     * @return the text
     */
    String getText()
    {
        return this.text;
    }

    /**
     * Whether the document has no text to read.
     *
     * @return {@code true} when the document is empty or only whitespace
     */
    boolean isBlank()
    {
        return this.text.isBlank();
    }

    /**
     * Finds where a quote is in this document, allowing small copying mistakes.
     *
     * @param quote the quote from the model
     * @return a position to pass to {@link #getHeading} and {@link #getPart}, or -1 when the quote is not found
     */
    int locate(final String quote)
    {
        return QuoteVerifier.locateIn(quote, this.normalized, this.partStartsAt);
    }

    /**
     * The title of the nearest Markdown heading at or above a position, which tells a reader where a quote is.
     *
     * @param at a position from {@link #locate}
     * @return the heading's title, or an empty string when there is no heading above or the position is -1
     */
    String getHeading(final int at)
    {
        final int line = getLine(at);
        return line < 0 || this.headingAbove[line] < 0 ? "" : this.headings.get(this.headingAbove[line]);
    }

    /**
     * Which line a position in the normalized text is on: the first line that ends after it.
     *
     * <p>A binary search, which works because {@link #lineEndsAt} never goes down.</p>
     *
     * @param at the position, or -1
     * @return the line index, or -1 when the position is -1
     */
    private int getLine(final int at)
    {
        if (at < 0) {
            return -1;
        }
        int low = 0;
        int high = this.lineEndsAt.length - 1;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (this.lineEndsAt[middle] <= at) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Counts the lines in a text, including an empty last line after a final line break.
     *
     * @param text the text
     * @return the number of lines, at least one
     */
    private static int countLines(final String text)
    {
        int lines = 1;
        for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
            lines++;
        }
        return lines;
    }
}
