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
package io.uhndata.iap.links.internal;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LinksCopyParticipant}: the links container stays out of copies, other children do not.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LinksCopyParticipantTest
{
    private final LinksCopyParticipant participant = new LinksCopyParticipant();

    @Test
    void leavesOutTheLinksContainer() throws RepositoryException
    {
        assertTrue(this.participant.skips(child("link:links")));
        assertFalse(this.participant.skips(child("form")));
    }

    private static Node child(final String name) throws RepositoryException
    {
        final Node child = Mockito.mock(Node.class);
        Mockito.when(child.getName()).thenReturn(name);
        return child;
    }
}
