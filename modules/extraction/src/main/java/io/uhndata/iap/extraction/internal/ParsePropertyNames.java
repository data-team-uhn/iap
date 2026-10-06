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
 * The names a finished parse is stored under, once it lands on a {@code sub:File} node.
 *
 * <p>These are also the keys the daemon uses for the same things in its JSON: the two vocabularies were
 * deliberately kept the same, so a name here serves as both.
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

    /** The parse state of a file whose parse has been asked for and not answered yet. */
    static final String STATUS_QUEUED = "queued";

    /** The parse state of a file whose parse ended without producing anything. */
    static final String STATUS_FAILED = "failed";

    /** What went wrong, on a file whose parse failed. */
    static final String PARSE_ERROR = "parseError";

    /** The size of the whole document, in tokens. */
    static final String TOKENS = "tokens";

    private ParsePropertyNames()
    {
        // Constants only
    }
}
