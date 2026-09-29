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
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code removeTag} service task: removes the activity's {@code tag} from the host.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RemoveTagHandler extends AbstractTagHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "removeTag";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    protected void change(final WorkflowTaskContext context, final Taggable host, final String tag)
        throws PersistenceException
    {
        host.untag(tag, true);
    }
}
