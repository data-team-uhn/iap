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

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReadingSizeDescriber}: when the form warns that an upload is too long for the model.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReadingSizeDescriberTest
{
    /** The active model's context window, which leaves a little over 100,000 tokens for one question's document. */
    private static final long WINDOW = 131_072L;

    /** More than any one call can carry. */
    private static final long HUGE = 1_000_000L;

    /** A size that fits alone, but not twice over. */
    private static final long MOST_OF_THE_ROOM = 60_000L;

    private static final String PROPOSAL = "proposal";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ReadingSizeDescriber describer = new ReadingSizeDescriber();

    private SubmissionTree tree;

    private Resource proposal;

    private IOException settingsFailure;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.tree.createSubmission();
        this.proposal = this.tree.createDocumentRequirement(PROPOSAL);
        TestComponents.inject(this.describer, "formsToRead", new FormsToRead());
        TestComponents.inject(this.describer, "reader", new RequirementReader());
        TestComponents.inject(this.describer, "configurationService", (LLMConfigurationService) () -> {
            if (this.settingsFailure != null) {
                throw this.settingsFailure;
            }
            return new LLMSettings("local", new LLMSettings.ProviderSettings(null, null, 0, null), "m",
                new LLMSettings.ModelSettings(WINDOW, 0.0, null, null));
        });
    }

    private void upload(final Resource requirement, final Long tokens)
    {
        final Resource file = this.tree.createParsedFileFor(requirement, "The text.");
        if (tokens != null) {
            file.adaptTo(ModifiableValueMap.class).put("tokens", tokens);
        }
    }

    private JsonObject describe(final Resource requirement)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.describer.describe(requirement.adaptTo(Requirement.class),
            this.context.resourceResolver().getResource(SubmissionTree.SUBMISSION_PATH).adaptTo(Submission.class),
            json);
        return json.build();
    }

    private boolean isWarned(final Resource requirement)
    {
        return describe(requirement).containsKey(ReadingSizeDescriber.READING_NOTICE_KEY);
    }

    @Test
    void describesOnlyDocumentRequirements()
    {
        assertTrue(this.describer.handles(this.proposal.adaptTo(Requirement.class)));
        assertFalse(this.describer.handles(this.tree.createValidationRequirement("is_proposal", PROPOSAL, "Is it?")
            .adaptTo(Requirement.class)));
    }

    @Test
    void warnsWhenACheckReadsAnUploadTooLongForTheModel()
    {
        this.tree.createValidationRequirement("is_proposal", PROPOSAL, "Is it a proposal?");
        upload(this.proposal, HUGE);

        assertEquals(ReadingSizeDescriber.TOO_LONG_NOTICE,
            describe(this.proposal).getString(ReadingSizeDescriber.READING_NOTICE_KEY));
    }

    @Test
    void warnsWhenAClassificationReadsAnUploadTooLongForTheModel()
    {
        this.tree.createClassificationQuestion("study_kind", PROPOSAL, "What kind of study?", "observational");
        upload(this.proposal, HUGE);

        assertTrue(isWarned(this.proposal));
    }

    @Test
    void saysNothingWhenTheUploadFits()
    {
        this.tree.createValidationRequirement("is_proposal", PROPOSAL, "Is it a proposal?");
        upload(this.proposal, 1_000L);

        assertFalse(isWarned(this.proposal));
    }

    // A document no check or form reads is never sent to the model, so its size does not matter
    @Test
    void saysNothingAboutADocumentNothingReads()
    {
        upload(this.proposal, HUGE);

        assertFalse(isWarned(this.proposal));
    }

    @Test
    void saysNothingBeforeAnUploadIsParsed()
    {
        this.tree.createValidationRequirement("is_proposal", PROPOSAL, "Is it a proposal?");

        assertFalse(isWarned(this.proposal), "nothing is uploaded");
        upload(this.proposal, null);
        assertFalse(isWarned(this.proposal), "the parse recorded no size");
    }

    // The reading reports settings it cannot read; a guessed window could warn about a document the model reads
    @Test
    void saysNothingWhenTheSettingsCannotBeRead()
    {
        this.tree.createValidationRequirement("is_proposal", PROPOSAL, "Is it a proposal?");
        upload(this.proposal, HUGE);
        this.settingsFailure = new IOException("no settings");

        assertFalse(isWarned(this.proposal));
    }

    // Each document fits alone, but the form sends them joined in one call
    @Test
    void warnsWhenAFormJoinsDocumentsThatTogetherDoNotFit()
    {
        final Resource consent = this.tree.createDocumentRequirement("consent");
        final Resource form = this.tree.createForm("study", "Study", PROPOSAL, "consent");
        this.tree.createQuestion(form, "title", "Find the title.");
        upload(this.proposal, MOST_OF_THE_ROOM);
        upload(consent, MOST_OF_THE_ROOM);

        assertTrue(isWarned(this.proposal));
        assertTrue(isWarned(consent));
    }

    @Test
    void saysNothingWhenAFormReadsOneDocumentThatFits()
    {
        final Resource form = this.tree.createForm("study", "Study", PROPOSAL);
        this.tree.createQuestion(form, "title", "Find the title.");
        upload(this.proposal, MOST_OF_THE_ROOM);

        assertFalse(isWarned(this.proposal));
    }
}
