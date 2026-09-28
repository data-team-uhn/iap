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
package io.uhndata.iap.links.internal;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.utils.copy.CopyParticipant;

/**
 * Keeps a node's links out of its copies: they say how the original relates to other content, and the copy has
 * its own container, created with it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CopyParticipant.class)
public class LinksCopyParticipant implements CopyParticipant
{
    @Override
    public boolean skips(final Node child) throws RepositoryException
    {
        return LinkManager.CONTAINER_NAME.equals(child.getName());
    }
}
