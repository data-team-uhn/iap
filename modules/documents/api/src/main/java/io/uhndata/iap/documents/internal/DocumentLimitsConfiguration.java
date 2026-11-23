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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * The largest document the platform takes. Read by {@link ConfiguredDocumentLimits}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Document limits",
    description = "The largest document a person may upload. The browser checks against the same numbers. Keep them "
        + "at or under the parser's own IAP_MAX_INPUT_BYTES, IAP_MAX_INPUT_PAGES and IAP_MAX_EXPANDED_BYTES.")
public @interface DocumentLimitsConfiguration
{
    /**
     * The largest file taken.
     *
     * @return a size in bytes
     */
    @AttributeDefinition(name = "Largest file",
        description = "In bytes. Zero or less uses the default of 52428800 (50 MB).")
    long maxFileSize() default ConfiguredDocumentLimits.DEFAULT_MAX_FILE_SIZE;

    /**
     * The most pages a PDF may have.
     *
     * @return a number of pages
     */
    @AttributeDefinition(name = "Most PDF pages",
        description = "Checked by the browser before an upload. Zero or less uses the default of 500.")
    int maxPdfPages() default ConfiguredDocumentLimits.DEFAULT_MAX_PDF_PAGES;

    /**
     * The most a {@code .docx} may unzip to.
     *
     * @return a size in bytes
     */
    @AttributeDefinition(name = "Largest unzipped Word file",
        description = "In bytes, what a .docx may unzip to. Checked by the browser before an upload. Zero or less "
            + "uses the default of 536870912 (512 MB).")
    long maxUnzippedSize() default ConfiguredDocumentLimits.DEFAULT_MAX_UNZIPPED_SIZE;
}
