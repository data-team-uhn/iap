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

import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.jcr.PropertyType;

/**
 * A whole number, written in ASCII digits with an optional sign, as XML Schema writes one.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class LongParameterType implements ParameterType
{
    /** {@link Long#valueOf(String)} also reads digits of other scripts, which XML Schema does not allow. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[+-]?[0-9]+");

    @Override
    public int storageType()
    {
        return PropertyType.LONG;
    }

    @Override
    public Object convert(final String raw, final Consumer<String> report)
    {
        try {
            if (WHOLE_NUMBER.matcher(raw).matches()) {
                return Long.valueOf(raw);
            }
        } catch (final NumberFormatException e) {
            // Too large for a long: reported below like any other value that is not one
        }
        report.accept("a parameter has a value that is not a whole number");
        return null;
    }
}
