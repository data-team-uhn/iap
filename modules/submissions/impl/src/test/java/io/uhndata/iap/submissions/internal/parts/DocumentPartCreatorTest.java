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
package io.uhndata.iap.submissions.internal.parts;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.internal.CompletenessFixture;

import static io.uhndata.iap.submissions.internal.CompletenessFixture.DOCTORS_NOTE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.VERSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.document;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DocumentPartCreator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentPartCreatorTest
{
    /** The schema parts these tests hide; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DocumentPartCreator creator = new DocumentPartCreator();

    @BeforeEach
    void setUp() throws Exception
    {
        CompletenessFixture.inject(this.creator, "conditions", CompletenessFixture.setUp(this.context, this.hidden));
    }

    @Test
    void givesARequirementAnEmptyDocument() throws Exception
    {
        this.creator.createMissing(submission(this.context));

        assertEquals(List.of(VERSION_PATH + "/" + DOCTORS_NOTE), filed());
    }

    @Test
    void createsNoSecondDocument() throws Exception
    {
        document(this.context, DOCTORS_NOTE, false);

        this.creator.createMissing(submission(this.context));

        assertEquals(List.of(VERSION_PATH + "/" + DOCTORS_NOTE), filed());
    }

    @Test
    void givesNothingToARequirementThatDoesNotApply() throws Exception
    {
        this.hidden.add(DOCTORS_NOTE);

        this.creator.createMissing(submission(this.context));

        assertEquals(List.of(), filed());
    }

    private List<String> filed()
    {
        return CompletenessFixture.filedRequirements(this.context);
    }
}
