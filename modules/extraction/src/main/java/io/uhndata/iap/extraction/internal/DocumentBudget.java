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
 * How much of a document one stage sends, and saying so when it is not all of it.
 *
 * <p>The three stages ask the same question - what is left of the active model's window once the prompt and the
 * answer have their room - and answer a document that does not fit the same way. Written out three times, one of
 * them checked whether anything was left to send and two did not, and none of them said in the log that a document
 * had been cut. The limit is real and worth knowing about: what is left out is not read, and the confidence says
 * nothing about it.</p>
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
     * As much of a document as one call may carry, and a log line when that is not the whole of it.
     *
     * @param configuration where the active model's settings are read from
     * @param stage what is asking, for the log and for the warning when the settings cannot be read
     * @param file the file being read, for what to call it in the log
     * @param document the whole parsed document
     * @param promptTokens the tokens everything other than the document takes
     * @param maxOutputTokens the room this call gives the answer
     * @return the text to send, empty when the prompt leaves no room for any of it
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
