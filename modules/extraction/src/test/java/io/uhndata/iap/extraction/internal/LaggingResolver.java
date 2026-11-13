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
package io.uhndata.iap.extraction.internal;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.api.wrappers.ValueMapDecorator;

/**
 * A second session over the test's repository. jcr-mock has one session for everybody, so this one keeps the
 * properties it read until {@link #refresh()}, as an Oak session keeps its view. A write made through the test's
 * own resolver, standing in for another session, is then seen only after a refresh.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class LaggingResolver extends ResourceResolverWrapper
{
    private final Map<String, ValueMap> seen = new HashMap<>();

    LaggingResolver(final ResourceResolver resolver)
    {
        super(resolver);
    }

    @Override
    public Resource getResource(final String path)
    {
        final Resource found = super.getResource(path);
        if (found == null) {
            return null;
        }
        final ValueMap properties = this.seen.computeIfAbsent(found.getPath(),
            key -> new ValueMapDecorator(new HashMap<>(found.getValueMap())));
        return new ResourceWrapper(found)
        {
            @Override
            public ValueMap getValueMap()
            {
                return properties;
            }

            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return ValueMap.class.equals(type) ? type.cast(properties) : super.adaptTo(type);
            }
        };
    }

    @Override
    public void refresh()
    {
        this.seen.clear();
        super.refresh();
    }
}
