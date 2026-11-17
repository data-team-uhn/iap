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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.spi.ValidityCheck;

/**
 * A request cannot be sent while one of its documents is being read: the reading would then write its verdicts and
 * suggestions onto a request that is no longer the submitter's to change. A workflow places a {@code checkValid}
 * step right after the task the submitter completes to send the request, so sending is refused until the reading
 * ends or is stopped.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ValidityCheck.class)
public class ReadingFinishedCheck implements ValidityCheck
{
    static final String STILL_READING_MESSAGE = "a document is still being read";

    @Override
    public boolean appliesTo(final Resource target)
    {
        return target.isResourceType(Submission.RESOURCE_TYPE);
    }

    @Override
    public List<String> check(final Resource target)
    {
        return ExtractionStatus.isRunning(target) ? List.of(STILL_READING_MESSAGE) : List.of();
    }
}
