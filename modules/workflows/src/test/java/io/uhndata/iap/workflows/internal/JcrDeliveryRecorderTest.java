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
package io.uhndata.iap.workflows.internal;

import javax.jcr.InvalidItemStateException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link JcrDeliveryRecorder}: the delivery is committed through the history's recorder, on the
 * engine's session.
 *
 * @version $Id$
 * @since 0.1.0
 */
class JcrDeliveryRecorderTest
{
    private static final RecordedAction ACTION = RecordedAction.by("jdoe", "save").build();

    private final HistoryRecorder history = Mockito.mock(HistoryRecorder.class);

    private final Session session = Mockito.mock(Session.class);

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final JcrDeliveryRecorder recorder = new JcrDeliveryRecorder(this.history);

    @Test
    void commitsTheDeliveryThroughTheHistory() throws Exception
    {
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);

        this.recorder.commit(this.resolver, ACTION);

        Mockito.verify(this.history).commit(this.session, ACTION);
    }

    @Test
    void keepsTheRepositorysReasonForAFailedCommit() throws Exception
    {
        final InvalidItemStateException conflict = new InvalidItemStateException("somebody else won");
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        Mockito.when(this.history.commit(this.session, ACTION)).thenThrow(conflict);

        assertSame(conflict, assertThrows(PersistenceException.class,
            () -> this.recorder.commit(this.resolver, ACTION)).getCause(),
            "The engine reads the cause to tell a lost race from a failure");
    }
}
