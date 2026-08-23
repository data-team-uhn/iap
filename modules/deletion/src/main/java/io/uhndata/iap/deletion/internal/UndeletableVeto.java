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
package io.uhndata.iap.deletion.internal;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.deletion.spi.DeletionVeto;

/**
 * The built-in deletion guard: resources bearing the {@code del:Undeletable} mixin cannot be deleted in any way —
 * not archived, not permanently removed, and not purged. The protection is lifted only by removing the mixin.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class UndeletableVeto implements DeletionVeto
{
    @Override
    public String getName()
    {
        return "undeletable";
    }

    @Override
    public String veto(final Node node, final DeletionMode mode, final Session requester)
        throws RepositoryException
    {
        if (node.isNodeType(DeletionService.UNDELETABLE_MIXIN)) {
            return "This resource is protected from deletion";
        }
        return null;
    }
}
