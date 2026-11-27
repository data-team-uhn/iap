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
package io.uhndata.iap.submissions.internal;

import java.util.Arrays;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.deletion.spi.DeletionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ApprovedSubmissionVeto}.
 *
 * @version $Id$
 */
class ApprovedSubmissionVetoTest
{
    private static final String REFUSAL = "An approved submission cannot be deleted";

    private final ApprovedSubmissionVeto veto = new ApprovedSubmissionVeto();

    // This guard looks only at the node, so the requester it is handed never matters
    private final Session requester = mock(Session.class);

    @Test
    void hasAStableName()
    {
        assertEquals("approvedSubmission", this.veto.getName());
    }

    @Test
    void refusesAnApprovedSubmissionInEveryMode() throws RepositoryException
    {
        final Node approved = node("sub:Submission", "submitted", "approved");

        assertEquals(REFUSAL, this.veto.veto(approved, DeletionMode.ARCHIVE, this.requester));
        assertEquals(REFUSAL, this.veto.veto(approved, DeletionMode.PERMANENT, this.requester));
        assertEquals(REFUSAL, this.veto.veto(approved, DeletionMode.PURGE, this.requester));
    }

    @Test
    void allowsASubmissionNotApproved() throws RepositoryException
    {
        assertNull(this.veto.veto(node("sub:Submission", "rejected"), DeletionMode.ARCHIVE, this.requester));
        assertNull(this.veto.veto(node("sub:Submission"), DeletionMode.ARCHIVE, this.requester));
    }

    @Test
    void allowsASubmissionWithNoTags() throws RepositoryException
    {
        final Node untagged = mock(Node.class);
        when(untagged.isNodeType("sub:Submission")).thenReturn(true);

        assertNull(this.veto.veto(untagged, DeletionMode.ARCHIVE, this.requester));
    }

    @Test
    void allowsAnythingElseTaggedApproved() throws RepositoryException
    {
        // A review carries the same tag when its reviewer approves
        assertNull(this.veto.veto(node("sub:Review", "approved"), DeletionMode.ARCHIVE, this.requester));
    }

    private static Node node(final String type, final String... tags) throws RepositoryException
    {
        final Node node = mock(Node.class);
        when(node.isNodeType(type)).thenReturn(true);
        final Property property = mock(Property.class);
        final Value[] values = Arrays.stream(tags).map(ApprovedSubmissionVetoTest::value).toArray(Value[]::new);
        when(property.getValues()).thenReturn(values);
        when(node.hasProperty("tags")).thenReturn(true);
        when(node.getProperty("tags")).thenReturn(property);
        return node;
    }

    private static Value value(final String text)
    {
        final Value value = mock(Value.class);
        try {
            when(value.getString()).thenReturn(text);
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
        return value;
    }
}
