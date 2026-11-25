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

import io.uhndata.iap.llm.LLMReply;

/**
 * What produced a run: the model, the prompt, the template and the threshold. Kept on the run, so a change in how
 * answers turn out can be traced to a new model, a new prompt, a new template or new documents.
 *
 * <p>Built in steps, each adding what it knows: the plan the prompt ({@link #ofPrompt}), the call the model
 * ({@link #withReply}), and a decision its threshold ({@link #withThreshold}).</p>
 *
 * @param model the model asked for, as configured; {@code null} when no call produced the run
 * @param servedModel the model the provider said answered; {@code null} when it did not say
 * @param systemFingerprint the provider's fingerprint of its serving setup; {@code null} when it gave none
 * @param promptVersion the version of the prompt sent, see {@link ExtractionPrompt#computePromptVersion};
 *     {@code null} when no call produced the run
 * @param templateVersion a short hash of the reference text sent; {@code null} when none was
 * @param confidenceThreshold the threshold the answer was held to; {@code null} when none applies, as for a form
 * @version $Id$
 * @since 0.1.0
 */
record RunOrigin(String model, String servedModel, String systemFingerprint, String promptVersion,
    String templateVersion, Double confidenceThreshold)
{
    /** The origin of a result no call produced and no threshold was applied to. */
    static final RunOrigin NO_ORIGIN = new RunOrigin(null, null, null, null, null, null);

    /**
     * The origin of a call before it is made: what it sends.
     *
     * @param promptVersion the version of the prompt it sends
     * @param templateVersion the version of the reference text it sends, or {@code null} for none
     * @return the origin, with no model yet
     */
    static RunOrigin ofPrompt(final String promptVersion, final String templateVersion)
    {
        return new RunOrigin(null, null, null, promptVersion, templateVersion, null);
    }

    /**
     * The same origin, with the model that answered.
     *
     * @param reply the model's reply
     * @return a copy naming the model
     */
    RunOrigin withReply(final LLMReply reply)
    {
        return new RunOrigin(reply.model(), reply.servedModel(), reply.systemFingerprint(), this.promptVersion,
            this.templateVersion, this.confidenceThreshold);
    }

    /**
     * The same origin, held to a threshold.
     *
     * @param threshold the confidence threshold the answer was compared with
     * @return a copy with the threshold
     */
    RunOrigin withThreshold(final double threshold)
    {
        return new RunOrigin(this.model, this.servedModel, this.systemFingerprint, this.promptVersion,
            this.templateVersion, threshold);
    }
}
