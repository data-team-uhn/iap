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

import java.io.IOException;

import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.Requirement;

/**
 * Reads the reference text a requirement shows the model, such as a template of what a research proposal holds,
 * or where a protocol usually states its sample size.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ReferenceTemplates
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReferenceTemplates.class);

    private ReferenceTemplates()
    {
        // Utility
    }

    /**
     * The requirement's reference text, under a heading naming what it is for. The model is shown it after the
     * document and before the questions.
     *
     * @param requirement the decision or form the template belongs to
     * @param template the requirement's template, or {@code null} when it has none
     * @param reporter the class to name when the template cannot be read
     * @return the text, or {@code null} when there is no template or it cannot be read
     */
    static String readTemplate(final Requirement requirement, final Resource template, final Class<?> reporter)
    {
        if (template == null) {
            return null;
        }
        try {
            return "# Reference for \"" + requirement.getLabel() + "\"\n\n" + DocumentText.readText(template).strip();
        } catch (final IOException e) {
            // The model is still worth asking without the reference, which only sharpens its answers
            LOGGER.warn("The template of {} could not be read: {}", requirement.getPath(), e.getMessage());
            // Broken schema content: the reading goes on without it, so nobody else would notice
            ErrorLogger.logError(e, ErrorContext.of(reporter, "readTemplate").about(requirement.getPath()));
            return null;
        }
    }
}
