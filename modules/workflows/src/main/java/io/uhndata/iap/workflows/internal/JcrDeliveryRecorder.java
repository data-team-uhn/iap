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

import java.util.Objects;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;

/**
 * Commits a delivery through the history's recorder, on the JCR session behind the engine's resolver, so that the
 * commit records it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DeliveryRecorder.class)
public class JcrDeliveryRecorder implements DeliveryRecorder
{
    private final HistoryRecorder recorder;

    /**
     * Constructor.
     *
     * @param recorder the history's recorder
     */
    @Activate
    public JcrDeliveryRecorder(@Reference final HistoryRecorder recorder)
    {
        this.recorder = recorder;
    }

    @Override
    public void commit(final ResourceResolver resolver, final RecordedAction action) throws PersistenceException
    {
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class),
            "The engine's session is a JCR session");
        try {
            this.recorder.commit(session, action);
        } catch (final RepositoryException e) {
            throw new PersistenceException("The delivery could not be committed", e);
        }
    }
}
