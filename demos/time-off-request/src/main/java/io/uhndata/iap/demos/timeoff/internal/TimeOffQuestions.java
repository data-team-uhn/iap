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
package io.uhndata.iap.demos.timeoff.internal;

/**
 * The questions of the time off request schema that the demo's rules read, and the answers they tell apart.
 *
 * <p>A question is named by its path within the schema version, which a new version or a copy of the schema
 * keeps.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TimeOffQuestions
{
    /** The path of the question saying how the absence is counted. */
    static final String DURATION_QUESTION = "details/duration";

    /** The value the duration question stores for half a day. */
    static final String HALF_DAY_OPTION = "half-day";

    /** The value the duration question stores for one whole day. */
    static final String FULL_DAY_OPTION = "full-day";

    /** The value the duration question stores for a span of days, which the two date questions then give. */
    static final String MULTIPLE_DAYS_OPTION = "multiple-days";

    /** The path of the question asking for the first day away. */
    static final String START_DATE_QUESTION = "details/startDate";

    /** The path of the question asking for the last day away, shown only for a span of days. */
    static final String END_DATE_QUESTION = "details/endDate";

    private TimeOffQuestions()
    {
        // Constants only
    }
}
