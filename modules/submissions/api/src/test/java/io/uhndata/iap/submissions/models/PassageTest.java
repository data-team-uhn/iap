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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Passage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class PassageTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String PASSAGE_PATH = "/Submissions/submission/protocol/v1/funding/p1";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Passage.class, Context.class);
    }

    @Test
    void quotesWhereItStartsAndEnds()
    {
        final Resource resource = this.context.create().resource(PASSAGE_PATH, TYPE, Passage.RESOURCE_TYPE);
        this.context.create().resource(PASSAGE_PATH + "/start", TYPE, Context.RESOURCE_TYPE, "quote", "4. Funding");
        this.context.create().resource(PASSAGE_PATH + "/end", TYPE, Context.RESOURCE_TYPE, "page", 12L);
        final Passage passage = resource.adaptTo(Passage.class);

        assertEquals("4. Funding", passage.getStart().getQuote());
        assertEquals(12L, passage.getEnd().getPage());
    }

    @Test
    void refusesToMakeUpAMissingEnd()
    {
        final Resource resource = this.context.create().resource(PASSAGE_PATH, TYPE, Passage.RESOURCE_TYPE);
        final Passage passage = resource.adaptTo(Passage.class);

        assertThrows(NullPointerException.class, passage::getStart);
        assertThrows(NullPointerException.class, passage::getEnd);
    }
}
