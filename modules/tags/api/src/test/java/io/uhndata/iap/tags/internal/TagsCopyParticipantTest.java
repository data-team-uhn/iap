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
package io.uhndata.iap.tags.internal;

import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagsCopyParticipant}: computed tags stay out of copies, placed ones do not.
 *
 * @version $Id$
 * @since 0.1.0
 */
class TagsCopyParticipantTest
{
    private final TagsCopyParticipant participant = new TagsCopyParticipant();

    @Test
    void leavesOutComputedTags() throws RepositoryException
    {
        assertTrue(this.participant.skips(property("inheritedTags")));
        assertTrue(this.participant.skips(property("computedTags")));
        assertTrue(this.participant.skips(property("aggregatedTags")));
        assertTrue(this.participant.skips(property("tagComputationState")));
        assertFalse(this.participant.skips(property("tags")));
    }

    private static Property property(final String name) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenReturn(name);
        return property;
    }
}
