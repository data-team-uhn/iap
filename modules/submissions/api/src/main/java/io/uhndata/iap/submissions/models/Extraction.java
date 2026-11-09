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
 * {@link #getExtractedValues() extracted values}, and {@link #getCreated()} is when it ran.
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
    private String[] extractedAnswer;

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
        return this.extractedAnswer == null ? List.of() : List.of(this.extractedAnswer);
    }

    /**
     * How sure the model is of the extracted answer.
     *
     * @return a value from 0 to 1; {@code null} if the model reported none
     */
    @Nullable
    public Double getConfidence()
    {
        return this.confidence;
    }

    /**
     * The model's explanation of how it arrived at the extracted answer.
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
     * the submitter accepts or edits the extracted answer.
     *
     * @return the edit distance; {@code null} while nobody has acted on this run
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
     * @return the percentage; {@code null} while nobody has acted on this run
     */
    @Nullable
    public Double getPercentageDistance()
    {
        return this.percentageDistance;
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
     * Whether the submitter has acted on this run, which an edit distance records. A distance of zero means the
     * extracted answer was accepted as it stood. Anything higher means it was edited or replaced.
     *
     * @return {@code true} once the submitter has accepted or edited the extracted answer
     */
    public boolean isActedOn()
    {
        return this.editDistance != null;
    }
}
