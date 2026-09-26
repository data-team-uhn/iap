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

import java.util.Collections;
import java.util.List;

import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code addTag} service task: places the activity's {@code tag} on the host. With {@code replaceExisting}
 * set, it first removes the host's other own tags that share a category with it, which is how a workflow moves
 * its host from one state to the next.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class AddTagHandler extends AbstractTagHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "addTag";

    /** The activity property asking for the tags sharing a category with the new one to be removed. */
    private static final String REPLACE_PARAMETER = "replaceExisting";

    @Reference
    private TagManager tagManager;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    protected void change(final WorkflowTaskContext context, final Taggable host, final String tag)
        throws PersistenceException
    {
        if (Boolean.parseBoolean(String.valueOf(context.getActivity().get(REPLACE_PARAMETER)))) {
            final List<String> categories = categories(tag);
            for (final String existing : host.getTags()) {
                if (!existing.equals(tag) && !Collections.disjoint(categories, categories(existing))) {
                    host.untag(existing, true);
                }
            }
        }
        host.tag(tag, true);
    }

    /**
     * The categories a tag belongs to.
     *
     * @param tag a tag name
     * @return its categories, none for an undefined tag
     */
    private List<String> categories(final String tag)
    {
        final TagDefinition definition = this.tagManager.getDefinition(tag);
        return definition == null ? List.of() : definition.getCategories();
    }
}
