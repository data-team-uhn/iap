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
package io.uhndata.iap.submissions.models;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:Extraction} node: one run of the model against one {@link Answer} or
 * {@link Validation}. It holds what the model read out of the submitted documents, and which document revisions
 * it read. A run that found nothing has no {@link #getExtractedValues() extracted values}, and
 * {@link #getCreated()} is when it ran.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Extraction.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Extraction extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Extraction} node. */
    public static final String RESOURCE_TYPE = "sub/Extraction";

    /** The property naming the model asked for, see {@link #getModel}. */
    public static final String MODEL_PROPERTY = "model";

    /** The property naming the model the provider said answered, see {@link #getServedModel}. */
    public static final String SERVED_MODEL_PROPERTY = "servedModel";

    /** The property holding the provider's fingerprint, see {@link #getSystemFingerprint}. */
    public static final String SYSTEM_FINGERPRINT_PROPERTY = "systemFingerprint";

    /** The property holding the prompt's version, see {@link #getPromptVersion}. */
    public static final String PROMPT_VERSION_PROPERTY = "promptVersion";

    /** The property holding the template's version, see {@link #getTemplateVersion}. */
    public static final String TEMPLATE_VERSION_PROPERTY = "templateVersion";

    /** The property holding the threshold the answer was held to, see {@link #getConfidenceThreshold}. */
    public static final String CONFIDENCE_THRESHOLD_PROPERTY = "confidenceThreshold";

    @ValueMapValue
    private String[] sources;

    @ValueMapValue
    private String[] extractedValues;

    @ValueMapValue
    private Double modelConfidence;

    @ValueMapValue
    private Double confidence;

    @ValueMapValue
    private String reasoning;

    @ValueMapValue
    private boolean evidenceRejected;

    @ValueMapValue
    private Long editDistance;

    @ValueMapValue
    private Double percentageDistance;

    @ValueMapValue
    private String model;

    @ValueMapValue
    private String servedModel;

    @ValueMapValue
    private String systemFingerprint;

    @ValueMapValue
    private String promptVersion;

    @ValueMapValue
    private String templateVersion;

    @ValueMapValue
    private Double confidenceThreshold;

    /**
     * The document revisions this run read, whether or not it found an answer in them. A newer revision of one of
     * them makes the run stale.
     *
     * @return the revisions; empty if none are recorded, or none of them resolve
     */
    @NotNull
    public List<DocumentVersion> getSources()
    {
        if (this.sources == null) {
            return List.of();
        }
        final List<DocumentVersion> result = new ArrayList<>();
        for (final String source : this.sources) {
            final DocumentVersion version = this.getReference(source, DocumentVersion.class);
            if (version != null) {
                result.add(version);
            }
        }
        return result;
    }

    /**
     * The values the model read out of the documents: one for a question taking one value, one per item for a
     * list. The run holds option values, not labels: the extraction matches them before it saves the run.
     *
     * @return the values, empty if this run found nothing
     */
    @NotNull
    public List<String> getExtractedValues()
    {
        return this.extractedValues == null ? List.of() : List.of(this.extractedValues);
    }

    /**
     * How sure the model said it is of the extracted values, as it gave it.
     *
     * @return a value from 0 to 1; {@code null} if the model reported none
     */
    @Nullable
    public Double getModelConfidence()
    {
        return this.modelConfidence;
    }

    /**
     * How sure to be of the extracted values: the model's confidence, lowered for the quotes that were not found in
     * the documents. This is the one shown, and the one thresholds compare with.
     *
     * @return a value from 0 to 1; {@code null} if the model reported none
     */
    @Nullable
    public Double getConfidence()
    {
        return this.confidence;
    }

    /**
     * The model's explanation of how it arrived at the extracted values.
     *
     * @return the explanation; {@code null} if the model gave none
     */
    @Nullable
    public String getReasoning()
    {
        return this.reasoning;
    }

    /**
     * Whether the submitter said the quoted passage does not support the answer. It is a note about the evidence
     * only: it does not accept or reject the answer.
     *
     * @return {@code true} if the passage was reported as wrong
     */
    public boolean isEvidenceRejected()
    {
        return this.evidenceRejected;
    }

    /**
     * How far the submitter's own value ended up from the extracted answer, as a raw edit distance. Written when
     * the request is sent, against the answer as it was sent.
     *
     * @return the edit distance; {@code null} until the request is sent with a value measured against this run
     */
    @Nullable
    public Long getEditDistance()
    {
        return this.editDistance;
    }

    /**
     * How far the submitter's own value ended up from the extracted answer, as a percentage of the extracted
     * answer's length.
     *
     * @return the percentage; {@code null} until the request is sent with a value measured against this run
     */
    @Nullable
    public Double getPercentageDistance()
    {
        return this.percentageDistance;
    }

    /**
     * The model the run asked, as it was configured.
     *
     * @return the model's name; {@code null} when the client did not say
     */
    @Nullable
    public String getModel()
    {
        return this.model;
    }

    /**
     * The model the provider said answered. A provider can change the model behind a name, and this may name the
     * exact version where {@link #getModel} does not.
     *
     * @return the provider's name for it; {@code null} when the provider did not say
     */
    @Nullable
    public String getServedModel()
    {
        return this.servedModel;
    }

    /**
     * The provider's fingerprint of its serving setup, which changes when the setup does.
     *
     * @return the fingerprint; {@code null} when the provider gave none
     */
    @Nullable
    public String getSystemFingerprint()
    {
        return this.systemFingerprint;
    }

    /**
     * The version of the prompt's fixed text the run sent: a short hash, which changes whenever the text does.
     *
     * @return the version; {@code null} for a run that recorded none
     */
    @Nullable
    public String getPromptVersion()
    {
        return this.promptVersion;
    }

    /**
     * The version of the reference text the run sent, such as the requirement's template: a short hash.
     *
     * @return the version; {@code null} when no reference text was sent
     */
    @Nullable
    public String getTemplateVersion()
    {
        return this.templateVersion;
    }

    /**
     * The confidence the answer had to reach to count, as it was when the run was made.
     *
     * @return the threshold; {@code null} when none applies, as for a form's answer
     */
    @Nullable
    public Double getConfidenceThreshold()
    {
        return this.confidenceThreshold;
    }

    /**
     * The context backing the extracted answer.
     *
     * @return the quotes; empty if the run cited nothing
     */
    @NotNull
    public List<Context> getContext()
    {
        return this.getChildren(Context.RESOURCE_TYPE, Context.class);
    }

    /**
     * Whether the answer sent was measured against this run, which happens once, when the request is sent. A draft
     * the submitter edited is not measured yet. A distance of zero means the extracted answer was accepted as it
     * stood. Anything higher means it was edited or replaced.
     *
     * @return {@code true} once the answer sent was measured against this run
     */
    public boolean isActedOn()
    {
        return this.editDistance != null;
    }
}
