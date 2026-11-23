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

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

import io.uhndata.iap.documents.api.DocumentLimits;

/**
 * The document limits, as an administrator configured them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DocumentLimits.class)
@Designate(ocd = DocumentLimitsConfiguration.class)
public class ConfiguredDocumentLimits implements DocumentLimits
{
    /** 50 MB. Above this an upload is slow enough to look broken. */
    static final long DEFAULT_MAX_FILE_SIZE = 50L * 1024 * 1024;

    /** Past this a parse takes long enough that a person would think it had failed. */
    static final int DEFAULT_MAX_PDF_PAGES = 500;

    /** 512 MB, the same as the parser's own default. */
    static final long DEFAULT_MAX_UNZIPPED_SIZE = 512L * 1024 * 1024;

    // One reference, written whole, so a request never reads half of a reconfiguration
    private volatile DocumentLimitsConfiguration configuration;

    /**
     * Read the configured limits.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final DocumentLimitsConfiguration configuration)
    {
        this.configuration = configuration;
    }

    @Override
    public long getMaxFileSize()
    {
        final long configured = this.configuration.maxFileSize();
        return configured > 0 ? configured : DEFAULT_MAX_FILE_SIZE;
    }

    @Override
    public int getMaxPdfPages()
    {
        final int configured = this.configuration.maxPdfPages();
        return configured > 0 ? configured : DEFAULT_MAX_PDF_PAGES;
    }

    @Override
    public long getMaxUnzippedSize()
    {
        final long configured = this.configuration.maxUnzippedSize();
        return configured > 0 ? configured : DEFAULT_MAX_UNZIPPED_SIZE;
    }
}
