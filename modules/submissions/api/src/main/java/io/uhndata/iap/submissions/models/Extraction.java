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
 * A Sling Model wrapping a {@code sub:Extraction} node: one run of answer extraction against one
 * {@link Answer} — what a model read out of the submitted documents, and which document revisions it read to get
 * there. The run's existence is the record that extraction happened, so a run that found nothing is one with no
 * {@link #getExtractedAnswer() extracted answer}, and {@link #getCreated()} is when it ran.
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

    @ValueMapValue
    private String[] sources;

    @ValueMapValue
    private String extractedAnswer;

    @ValueMapValue
    private Double confidence;

    @ValueMapValue
    private String reasoning;

    @ValueMapValue
    private Long editDistance;

    @ValueMapValue
    private Double percentageDistance;

    /**
     * The document revisions this run read, whether or not it found an answer in them. A revision is what a
     * submitter replaces, so this is what tells a later reader that the run is stale.
     *
     * @return a list of revisions, empty if none were recorded or none of them resolve
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
     * The answer the model read out of the documents.
     *
     * @return an extracted answer, or {@code null} if this run found nothing
     */
    @Nullable
    public String getExtractedAnswer()
    {
        return this.extractedAnswer;
    }

    /**
     * How sure the model is of the extracted answer, from 0 to 1.
     *
     * @return a confidence, or {@code null} if the model reported none
     */
    @Nullable
    public Double getConfidence()
    {
        return this.confidence;
    }

    /**
     * The model's explanation of how it arrived at the extracted answer.
     *
     * @return an explanation, or {@code null} if the model gave none
     */
    @Nullable
    public String getReasoning()
    {
        return this.reasoning;
    }

    /**
     * How far the submitter's own value ended up from the extracted answer, as a raw edit distance. Written when
     * the submitter accepts or edits the extracted answer.
     *
     * @return an edit distance, or {@code null} while nobody has acted on this run
     */
    @Nullable
    public Long getEditDistance()
    {
        return this.editDistance;
    }

    /**
     * How far the submitter's own value ended up from the extracted answer, as a percentage of the extracted
     * answer's own length.
     *
     * @return a percentage, or {@code null} while nobody has acted on this run
     */
    @Nullable
    public Double getPercentageDistance()
    {
        return this.percentageDistance;
    }

    /**
     * The passages backing the extracted answer.
     *
     * @return a list of evidence, empty if the run cited nothing
     */
    @NotNull
    public List<Evidence> getEvidence()
    {
        return this.getChildren(Evidence.RESOURCE_TYPE, Evidence.class);
    }

    /**
     * Whether the submitter has acted on this run, which is what an edit distance records. A distance of zero
     * means the extracted answer was accepted as it stood; anything higher means it was edited or replaced.
     *
     * @return {@code true} once the submitter has accepted or edited the extracted answer
     */
    public boolean isActedOn()
    {
        return this.editDistance != null;
    }
}
