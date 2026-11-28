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
package io.uhndata.iap.workflows.internal.types;

import java.util.Set;
import java.util.function.Consumer;

import javax.jcr.PropertyType;

/**
 * A yes-or-no value, written the way XML Schema writes one: {@code true} or {@code 1}, {@code false} or {@code 0},
 * in lower case. BPMN types its own boolean attributes that way, and an IAP parameter follows the same rule. Anything
 * else is reported and dropped, so the node type's default stands.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class BooleanParameterType implements ParameterType
{
    private static final Set<String> TRUE_VALUES = Set.of("true", "1");

    private static final Set<String> FALSE_VALUES = Set.of("false", "0");

    @Override
    public int storageType()
    {
        return PropertyType.BOOLEAN;
    }

    @Override
    public Object convert(final String raw, final Consumer<String> report)
    {
        if (TRUE_VALUES.contains(raw)) {
            return Boolean.TRUE;
        }
        if (FALSE_VALUES.contains(raw)) {
            return Boolean.FALSE;
        }
        report.accept("a parameter has a value that is not a boolean");
        return null;
    }
}
