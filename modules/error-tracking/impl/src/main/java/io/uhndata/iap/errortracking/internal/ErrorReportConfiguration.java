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
package io.uhndata.iap.errortracking.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * What makes the status report about recorded errors an {@code ERROR}. A monitoring tool polls
 * {@code /system/status}, and an {@code ERROR} there should mean that something is going wrong now.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Error Report",
    description = "How loudly the status report speaks about the errors this instance recorded.")
public @interface ErrorReportConfiguration
{
    /** The default for {@link #recentFailureWindow()}, in minutes. */
    int DEFAULT_RECENT_WINDOW = 60;

    /**
     * How long after a failure was last seen it still counts as happening now.
     *
     * @return a number of minutes; a value of zero or less is ignored, and the default is used instead
     */
    @AttributeDefinition(name = "Recent failure window",
        description = "For how many minutes after it was last seen an unacknowledged failure makes the report an "
            + "ERROR rather than a WARNING. Zero or less means the default, 60.")
    int recentFailureWindow() default DEFAULT_RECENT_WINDOW;

    /**
     * Whether a recorded problem, which nothing was thrown for, can make the report an {@code ERROR}.
     *
     * @return {@code true} if a recent problem makes the report an {@code ERROR}, as a recent failure does
     */
    @AttributeDefinition(name = "Problems are urgent",
        description = "Whether a recent problem, such as a mis-authored definition, makes the report an ERROR as a "
            + "recent failure does.")
    boolean problemsAreUrgent() default false;
}
