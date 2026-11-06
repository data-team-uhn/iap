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
package io.uhndata.iap.serialization.internal;

import java.util.OptionalInt;
import java.util.function.Function;

import javax.jcr.Node;

import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.utils.SelectorUtils;

/**
 * Serializes the children of each node. The name of this processor is {@code deep}.
 *
 * <p>
 * A depth selector other than {@code 0} enables it too, so {@code .1.json} and {@code .infinity.json} need no
 * {@code deep}. {@code ResourceToJsonAdapterFactory} stops it at the requested depth.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class DeepProcessor implements ResourceJsonProcessor
{
    @Override
    public String getName()
    {
        return "deep";
    }

    @Override
    public int getPriority()
    {
        return 10;
    }

    @Override
    public boolean isEnabledByDefault(final Resource resource)
    {
        final OptionalInt depth = SelectorUtils.parseDepth(resource.getResourceMetadata().getResolutionPathInfo());
        return depth.isPresent() && depth.getAsInt() != 0;
    }

    @Override
    public JsonValue processChild(final Node node, final Node child, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
    {
        // A processor that ran earlier may have serialized this child its own way
        if (input != null) {
            return input;
        }
        return serializeNode.apply(child);
    }
}
