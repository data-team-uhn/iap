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
package io.uhndata.iap.submissions.internal.evaluators;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.submissions.internal.CompletenessFixture;
import io.uhndata.iap.submissions.internal.Referenceables;
import io.uhndata.iap.submissions.models.Document;

import static io.uhndata.iap.submissions.internal.CompletenessFixture.DOCTORS_NOTE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.SUBMISSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.TYPE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.VERSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.document;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DocumentCompletenessEvaluator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentCompletenessEvaluatorTest
{
    private static final String SPONSOR_LETTER = "sponsorLetter";

    /** The schema parts these tests hide; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DocumentCompletenessEvaluator evaluator = new DocumentCompletenessEvaluator();

    @BeforeEach
    void setUp() throws Exception
    {
        CompletenessFixture.inject(this.evaluator, "conditions", CompletenessFixture.setUp(this.context, this.hidden));
    }

    @Test
    void reportsTheSubmissionWhenARequiredRequirementHasNoDocument() throws Exception
    {
        assertEquals(List.of(SUBMISSION_PATH),
            this.evaluator.evaluate(submission(this.context)).stream().map(Resource::getPath).toList());
        // Judging creates nothing
        assertEquals(List.of(), filed());
    }

    @Test
    void doesNotDemandAnOptionalDocument() throws Exception
    {
        optional();
        document(this.context, DOCTORS_NOTE, true);

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    @Test
    void reportsTheEmptyDocumentOfARequiredRequirement() throws Exception
    {
        final Resource empty = document(this.context, DOCTORS_NOTE, false);

        final List<Resource> incomplete = this.evaluator.evaluate(submission(this.context));

        assertEquals(List.of(empty.getPath()), incomplete.stream().map(Resource::getPath).toList());
        assertEquals(1, filed().size());
    }

    @Test
    void reportsNothingOnceAFileIsAttached() throws Exception
    {
        document(this.context, DOCTORS_NOTE, true);

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    @Test
    void asksNothingOfARequirementThatDoesNotApply() throws Exception
    {
        this.hidden.add(DOCTORS_NOTE);

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    @Test
    void ignoresADocumentFulfillingNothing() throws Exception
    {
        document(this.context, DOCTORS_NOTE, true);
        this.context.create().resource(SUBMISSION_PATH + "/loose", Map.of(TYPE, Document.RESOURCE_TYPE));

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    private void optional() throws RepositoryException, PersistenceException
    {
        this.context.create().resource(VERSION_PATH + "/" + SPONSOR_LETTER, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, "sling:resourceSuperType", "sch/Requirement",
            "label", "Sponsor letter", "required", false));
        Referenceables.identify(this.context, VERSION_PATH + "/" + SPONSOR_LETTER);
    }

    private List<String> filed()
    {
        return CompletenessFixture.filedRequirements(this.context);
    }
}
