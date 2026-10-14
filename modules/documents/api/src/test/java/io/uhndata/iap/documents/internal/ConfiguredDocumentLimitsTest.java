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
package io.uhndata.iap.documents.internal;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ConfiguredDocumentLimits}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ConfiguredDocumentLimitsTest
{
    private final ConfiguredDocumentLimits limits = new ConfiguredDocumentLimits();

    @Test
    void reportsTheConfiguredLimits()
    {
        this.limits.activate(mockConfiguration(1000, 20, 3000));

        assertEquals(1000, this.limits.getMaxFileSize());
        assertEquals(20, this.limits.getMaxPdfPages());
        assertEquals(3000, this.limits.getMaxUnzippedSize());
    }

    @Test
    void usesTheDefaultsForZero()
    {
        this.limits.activate(mockConfiguration(0, 0, 0));

        assertEquals(ConfiguredDocumentLimits.DEFAULT_MAX_FILE_SIZE, this.limits.getMaxFileSize());
        assertEquals(ConfiguredDocumentLimits.DEFAULT_MAX_PDF_PAGES, this.limits.getMaxPdfPages());
        assertEquals(ConfiguredDocumentLimits.DEFAULT_MAX_UNZIPPED_SIZE, this.limits.getMaxUnzippedSize());
    }

    @Test
    void usesTheDefaultsForANegativeValue()
    {
        this.limits.activate(mockConfiguration(-1, -1, -1));

        assertEquals(50L * 1024 * 1024, this.limits.getMaxFileSize());
        assertEquals(500, this.limits.getMaxPdfPages());
        assertEquals(512L * 1024 * 1024, this.limits.getMaxUnzippedSize());
    }

    static DocumentLimitsConfiguration mockConfiguration(final long maxFileSize, final int maxPdfPages,
        final long maxUnzippedSize)
    {
        final DocumentLimitsConfiguration configuration = Mockito.mock(DocumentLimitsConfiguration.class);
        Mockito.when(configuration.maxFileSize()).thenReturn(maxFileSize);
        Mockito.when(configuration.maxPdfPages()).thenReturn(maxPdfPages);
        Mockito.when(configuration.maxUnzippedSize()).thenReturn(maxUnzippedSize);
        return configuration;
    }
}
