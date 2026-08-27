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

/**
 * The names a finished parse is stored under, once it lands on a {@code sub:File} or {@code sub:Chunk} node.
 *
 * <p>Most of these are also the key the daemon uses for the same thing in its JSON, since the two vocabularies
 * were deliberately kept the same: a name here usually serves as both. The few that differ are spelled out as
 * {@code JSON_} constants, and are the only place a rename happens on the way in.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ParsePropertyNames
{
    /** How far the parse got, on a {@code sub:File}. */
    static final String PARSE_STATUS = "parseStatus";

    /** The {@link #PARSE_STATUS} of a file whose parse finished and has been read in. */
    static final String STATUS_COMPLETED = "completed";

    /** The size of the whole document, in tokens. */
    static final String TOKENS = "tokens";

    /** Whether the document was split into chunks. */
    static final String CHUNKED = "chunked";

    /** Why a document was left whole, when it was. */
    static final String UNCHUNKED_REASON = "unchunkedReason";

    /**
     * The {@link #UNCHUNKED_REASON} recorded when a document was left whole simply because it was small. The
     * other two reasons the chunker leaves a document whole - a deliberate skip, or a splitter that found
     * nothing to cut - say nothing about its size, so only this one reason can be trusted on its own.
     */
    static final String REASON_BELOW_MIN_STRUCTURE_TOKENS = "below_min_structure_tokens";

    /**
     * Set when a document went unchunked for a reason other than {@link #REASON_BELOW_MIN_STRUCTURE_TOKENS} and
     * is still past the active model's {@code wholeDocumentTokenLimit} - the same threshold that decides
     * whether a document is small enough to leave whole in the first place. Computed once, at ingest, so
     * nothing downstream has to re-derive it: there is nothing safe to show the gate for a document like
     * this, not even its bookmarks, so it is a flat "cannot process" rather than a per-input-form decision.
     */
    static final String UNCHUNKED_OVER_LIMIT = "unchunkedOverLimit";

    /** The headings the document carried in its own bookmarks, in document order. */
    static final String BOOKMARKS = "bookmarks";

    /** A chunk's summary, written later by the summarizer rather than by the parse. */
    static final String SUMMARY = "summary";

    /** The rubric tags a chunk carries, as stored on the node. */
    static final String RUBRIC_TAGS = "rubricTags";

    /** The child of a {@code sub:File} holding its chunk tree. */
    static final String CHUNKS_CHILD = "chunks";

    /** How a chunk's tags were arrived at: from headings alone, or from a model reading the text. */
    static final String TAG_BASIS = "tagBasis";

    /** The {@link #TAG_BASIS} of tags guessed from the list of headings rather than from the text. */
    static final String BASIS_HEADING = "heading";

    /** Set when a chunk's tags are a weak guess rather than something a model read for. */
    static final String UNCERTAIN = "uncertain";

    /** The first page a chunk covers, absent for a document that carries no page markers. */
    static final String PAGE_START = "pageStart";

    /** The last page a chunk covers. */
    static final String PAGE_END = "pageEnd";

    /** How the catalog names a chunk: its file name. The node drops the extension. */
    static final String JSON_CHUNK_ID = "chunk_id";

    /** How the catalog spells {@link #RUBRIC_TAGS}. */
    static final String JSON_RUBRIC_TAGS = "rubric_tags";

    private ParsePropertyNames()
    {
        // Constants only
    }
}
