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

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.utils.copy.ContentCopier;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The built-in service task copying content: what the event's {@code source} holds is copied, with the
 * {@link ContentCopier}, into what the task acts on, the resource the execution created or else the target. An
 * event without a source copies nothing, so a workflow can offer copying as an option. The activity may accept only
 * sources of one resource type ({@code sourceType}), and leave out properties of the source itself
 * ({@code skipProperties}) or its tags in some categories ({@code dropTagCategories}), such as a label the copy has
 * its own of, or where the source stands.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CopyContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "copyContent";

    /** The payload entry naming what to copy. */
    static final String SOURCE_PARAMETER = "source";

    @Reference
    private ContentCopier copier;

    @Reference
    private TagManager tags;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object path = context.getEvent().get(SOURCE_PARAMETER);
        if (path == null) {
            return;
        }
        final Resource source = path instanceof String ? context.getResourceResolver().getResource((String) path)
            : null;
        final Object type = context.getActivity().get("sourceType");
        if (source == null || type instanceof String && !source.isResourceType((String) type)) {
            throw new InvalidPayloadException("There is nothing at " + path + " that can be copied here");
        }
        final Resource host = ExecutionHost.of(context);
        final Set<String> dropped = strings(context.getActivity().get("dropTagCategories")).stream()
            .flatMap(category -> this.tags.findDefinitions(category, null).stream())
            .map(TagDefinition::getName)
            .collect(Collectors.toSet());
        try {
            final Node target = Nodes.of(host);
            VersioningUtils.checkOut(target);
            this.copier.copy(Nodes.of(source), target, strings(context.getActivity().get("skipProperties")),
                Map.of(TagManager.TAGS_PROPERTY, dropped));
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot copy " + source.getPath() + " into " + host.getPath(), e);
        }
    }

    /**
     * An activity setting that lists names, given as one or several.
     *
     * @param setting the setting's value
     * @return the names, none when the setting is absent
     */
    private static Set<String> strings(final Object setting)
    {
        if (setting instanceof String) {
            return Set.of((String) setting);
        }
        return setting instanceof String[] ? Set.copyOf(Arrays.asList((String[]) setting)) : Set.of();
    }
}
