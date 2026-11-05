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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.history.api.RecordedAction;

/**
 * Commits a delivery so that the commit records it in the history.
 *
 * @version $Id$
 * @since 0.1.0
 */
@FunctionalInterface
public interface DeliveryRecorder
{
    /**
     * Commits the changes a session holds, recorded as the given action. A commit that fails records nothing.
     *
     * @param resolver the engine's session, holding the delivery's changes uncommitted
     * @param action the delivery, as its history records it
     * @throws PersistenceException if the commit fails
     */
    void commit(ResourceResolver resolver, RecordedAction action) throws PersistenceException;
}
