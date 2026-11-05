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

import java.util.List;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

/**
 * Adds {@code @delegable} to each serialized workflow task: whether the reader may decide it on behalf of someone
 * else. For now every task may be delegated, so the answer is whether the reader may act for others at all, worked
 * out once per serialization.
 *
 * <p>Presentation, never authorization: the engine still refuses whoever may not complete a task, so nothing may rest
 * on the field being true.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class DelegableProcessor implements ResourceJsonProcessor
{
    /** Whether the reader may decide the task on behalf of someone else. */
    static final String DELEGABLE_KEY = "@delegable";

    private static final Logger LOGGER = LoggerFactory.getLogger(DelegableProcessor.class);

    private static final String TASK_NODE_TYPE = "wf:TaskInstance";

    /** Where this applies: a submission's workflow container, one instance in it, or a task on its own. */
    private static final List<String> SERIALIZED_TREES = List.of(WorkflowInstances.RESOURCE_TYPE,
        WorkflowInstance.RESOURCE_TYPE, TaskInstance.RESOURCE_TYPE);

    private final Delegation delegation;

    /** The reader's answer, for the serialization in progress; unset when it could not be worked out. */
    private final ThreadLocal<Boolean> readerIsDelegate = new ThreadLocal<>();

    /**
     * Constructor.
     *
     * @param delegation says whether the reader may act for others
     */
    @Activate
    public DelegableProcessor(@Reference final Delegation delegation)
    {
        this.delegation = delegation;
    }

    @Override
    public String getName()
    {
        return "delegable";
    }

    @Override
    public int getPriority()
    {
        return 20;
    }

    @Override
    public boolean canProcess(@NotNull final Resource resource)
    {
        return SERIALIZED_TREES.stream().anyMatch(resource::isResourceType);
    }

    @Override
    public boolean isEnabledByDefault(@NotNull final Resource resource)
    {
        return true;
    }

    @Override
    public void start(@NotNull final Resource resource)
    {
        final ResourceResolver reader = resource.getResourceResolver();
        try {
            this.readerIsDelegate.set(this.delegation.isDelegate(reader, UserIds.canonical(reader)));
        } catch (final WorkflowFailedException e) {
            // Answering nothing offers no control, which is the safe direction
            LOGGER.error("Could not tell whether {} may act for others: {}", reader.getUserID(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(getClass(), "start").about(resource));
        }
    }

    @Override
    public void leave(@NotNull final Node node, @NotNull final JsonObjectBuilder json,
        @NotNull final Function<Node, JsonValue> serializeNode)
    {
        final Boolean delegable = this.readerIsDelegate.get();
        try {
            if (delegable != null && node.isNodeType(TASK_NODE_TYPE)) {
                json.add(DELEGABLE_KEY, delegable);
            }
        } catch (final RepositoryException e) {
            LOGGER.error("Could not tell whether {} is a task: {}", node, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(getClass(), "leave"));
        }
    }

    @Override
    public void end(@NotNull final Resource resource)
    {
        this.readerIsDelegate.remove();
    }
}
