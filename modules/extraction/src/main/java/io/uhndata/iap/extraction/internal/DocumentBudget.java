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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.llm.CallBudget;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.submissions.models.File;

/**
 * Cuts a document to fit in one model call, and logs when it had to cut.
 *
 * <p>The model never sees the part that was cut, and the confidence does not show it, so every cut is logged.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentBudget
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentBudget.class);

    private DocumentBudget()
    {
        // Utility
    }

    /**
     * The part of a document that fits in one call. A document that is too long keeps its start and its end,
     * and the middle is left out.
     *
     * @param configuration where the active model's settings are read from
     * @param stage the name of the step asking, for the logs
     * @param file the file being read, used to name it in the log
     * @param document the whole parsed document
     * @param promptTokens the tokens used by everything other than the document
     * @param maxOutputTokens the most tokens this call lets the answer use
     * @return the text to send; empty when the document is empty or the prompt leaves no room for it
     */
    static String fitDocument(final LLMConfigurationService configuration, final String stage, final File file,
        final String document, final long promptTokens, final long maxOutputTokens)
    {
        final long budget =
            CallBudget.calculateDocumentTokenBudget(configuration, promptTokens, maxOutputTokens, stage);
        final CallBudget.FittedText fitted = CallBudget.fitToTokens(document, budget);
        if (fitted.wasCut()) {
            LOGGER.info("Document cut to fit: stage={} file={} documentChars={} sentChars={} omittedChars={}",
                stage, file.getPath(), document.length(), fitted.text().length(), fitted.omittedCharacters());
        }
        return fitted.text();
    }
}
