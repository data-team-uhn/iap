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
package io.uhndata.iap.protocols.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code pt:Protocol} node, a named institutional protocol required for approving
 * submissions. The protocol itself is only a container identifying the protocol; the actual requirements live in its
 * {@code pt:ProtocolVersion} children.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = "pt/Protocol",
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Protocol extends Entity
{
    @ValueMapValue
    private String title;

    @ValueMapValue
    private boolean active;

    /**
     * The human-readable name of the protocol.
     *
     * @return a title
     */
    public String getTitle()
    {
        return this.title;
    }

    /**
     * Whether new submissions may be created against this protocol.
     *
     * @return {@code true} if the protocol accepts new submissions
     */
    public boolean isActive()
    {
        return this.active;
    }
}
