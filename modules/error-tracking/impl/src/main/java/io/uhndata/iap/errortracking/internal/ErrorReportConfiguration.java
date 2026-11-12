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
        description = "How long, in minutes, after a failure was last seen it still counts as happening now. An "
            + "unacknowledged failure seen inside this window makes the report an ERROR; one last seen before it is "
            + "reported as a WARNING, since it is a fault somebody should look at rather than a sign that this "
            + "instance is unwell right now. Nothing recorded is ever deleted, so a deployment that wants every "
            + "unacknowledged failure to be an ERROR should set a window longer than an instance ever runs, rather "
            + "than zero — a window of zero or less cannot say what is happening now, and is ignored.")
    int recentFailureWindow() default DEFAULT_RECENT_WINDOW;

    /**
     * Whether a recorded problem, which nothing was thrown for, can make the report an {@code ERROR}.
     *
     * @return {@code true} if a recent problem makes the report an {@code ERROR}, as a recent failure does
     */
    @AttributeDefinition(name = "Problems are urgent",
        description = "Whether something this instance found wrong without anything being thrown — a mis-authored "
            + "definition, most often — can make the report an ERROR the way a failure does. Off by default: an "
            + "authoring mistake is somebody's to correct, and however often it is hit, it does not mean this "
            + "instance is unwell. Turn it on where a definition being wrong is itself an emergency.")
    boolean problemsAreUrgent() default false;
}
