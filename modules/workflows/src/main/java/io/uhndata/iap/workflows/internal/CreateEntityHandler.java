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

import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The first built-in service task: create a new entity under the target homepage. The activity's
 * {@code entityType} property configures the JCR node type to create; the event's {@code title} names the new
 * entity, both as its stored {@code title} and — camel-cased into a valid name — as its node name. The created
 * path is reported in the {@link WorkflowResult#CREATED_PATH} variable, for the downstream nodes and for the
 * channel that fired the event.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateEntityHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "createEntity";

    /** The activity property configuring the JCR node type of the created entity. */
    private static final String ENTITY_TYPE = "entityType";

    /** The payload entry naming the created entity. */
    private static final String TITLE = "title";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object entityType = context.getActivity().get(ENTITY_TYPE);
        if (!(entityType instanceof String) || ((String) entityType).isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not configure which entityType to create");
        }
        final Object title = context.getEvent().get(TITLE);
        if (!(title instanceof String) || ((String) title).isBlank()) {
            throw new InvalidPayloadException("A title is required");
        }
        final Resource created = context.getResourceResolver().create(context.getTarget(),
            freeName(context.getTarget(), camelCase((String) title)),
            Map.of("jcr:primaryType", entityType, TITLE, title));
        context.setVariable(WorkflowResult.CREATED_PATH, created.getPath());
    }

    /**
     * Derives the node name from the title, translating an unusable title into a payload refusal.
     *
     * @param title the title to derive a name from
     * @return a camel-cased name
     * @throws InvalidPayloadException when nothing usable remains, e.g. the title holds only punctuation
     */
    private String camelCase(final String title) throws InvalidPayloadException
    {
        final String name = NodeNameUtils.camelCase(title);
        if (name.isEmpty()) {
            throw new InvalidPayloadException("The title must contain at least one letter or digit");
        }
        return name;
    }

    /**
     * Finds a free child name, translating an exhausted namespace into a payload refusal.
     *
     * @param parent the resource the entity will be created under
     * @param base the natural name derived from the title
     * @return a free name
     * @throws InvalidPayloadException when every tolerated variant is already taken
     */
    private String freeName(final Resource parent, final String base) throws InvalidPayloadException
    {
        final String name = NodeNameUtils.findFreeName(parent, base);
        if (name == null) {
            throw new InvalidPayloadException(
                "Too many entities are already named " + base + "; pick a different title");
        }
        return name;
    }
}
