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
package io.uhndata.iap.workflows.testing;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.workflows.internal.DeliveryRecorder;

/**
 * Stands in for the history's delivery recorder. It commits each delivery the engine makes and keeps the action
 * describing it, so that a test can read back what the engine delivered.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RecordedDeliveries implements DeliveryRecorder
{
    private final List<RecordedAction> described = new ArrayList<>();

    @Override
    public void commit(@NotNull final ResourceResolver resolver, @NotNull final RecordedAction action)
        throws PersistenceException
    {
        this.described.add(action);
        resolver.commit();
    }

    /**
     * Every delivery described so far, whether its commit succeeded or not.
     *
     * @return the actions, in the order the engine handed them over
     */
    @NotNull
    public List<RecordedAction> described()
    {
        return List.copyOf(this.described);
    }
}
