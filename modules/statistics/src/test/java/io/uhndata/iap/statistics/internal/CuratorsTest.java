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
package io.uhndata.iap.statistics.internal;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Curators}. Who counts as one is exercised through the two servlets that ask;
 * this covers the one thing neither of them touches.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CuratorsTest
{
    @Test
    void isAUtilityClass() throws ReflectiveOperationException
    {
        final var constructor = Curators.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
