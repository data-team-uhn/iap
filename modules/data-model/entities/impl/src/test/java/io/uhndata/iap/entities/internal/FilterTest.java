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
package io.uhndata.iap.entities.internal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Filter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class FilterTest
{
    @Test
    public void supportedComparatorIsKept()
    {
        final Filter filter = new Filter("status", "<>", "draft");
        Assertions.assertEquals("status", filter.getName());
        Assertions.assertEquals("<>", filter.getComparator());
        Assertions.assertEquals("draft", filter.getValue());
        Assertions.assertFalse(filter.isValueless());
    }

    @Test
    public void unsupportedComparatorFallsBackToEquals()
    {
        final Filter filter = new Filter("status", "; drop everything", "draft");
        Assertions.assertEquals("=", filter.getComparator());
    }

    @Test
    public void nullComparatorFallsBackToEquals()
    {
        final Filter filter = new Filter("status", null, "draft");
        Assertions.assertEquals("=", filter.getComparator());
    }

    @Test
    public void valuelessComparatorsAreDetected()
    {
        Assertions.assertTrue(new Filter("status", "IS NULL", null).isValueless());
        Assertions.assertTrue(new Filter("status", "IS NOT NULL", null).isValueless());
    }
}
