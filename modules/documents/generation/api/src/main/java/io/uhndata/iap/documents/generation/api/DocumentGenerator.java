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
package io.uhndata.iap.documents.generation.api;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Fills a {@code .docx} template with values about a resource, and converts the result to PDF. The values come from
 * every registered {@link io.uhndata.iap.documents.generation.spi.TemplateContextProvider}, each under its own name,
 * so a template refers to {@code {{ submission.title }}} without this service knowing what a submission is.
 *
 * <p>The call waits for both documents: a one-page letter takes about a second.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DocumentGenerator
{
    /**
     * The maximum size a template can be.
     */
    int MAX_TEMPLATE_BYTES = 10 * 1024 * 1024;

    /**
     * Fill {@code template} with the values every provider has for {@code target}, and convert it to PDF.
     *
     * @param template the template, an {@code nt:file} holding a {@code .docx}
     * @param target what the document is about, e.g. a submission
     * @return how it went, with both documents when it worked
     */
    @NotNull
    GenerationResult generate(@NotNull Resource template, @NotNull Resource target);
}
