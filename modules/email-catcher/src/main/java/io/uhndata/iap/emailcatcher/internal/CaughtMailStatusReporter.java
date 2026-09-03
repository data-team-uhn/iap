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
package io.uhndata.iap.emailcatcher.internal;

import java.util.Set;

import org.apache.sling.commons.messaging.mail.MailService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Reports whether outgoing email is being caught rather than delivered.
 *
 * <p>
 * <strong>A warning while it is on, and deliberately so.</strong> The catcher is switched on on purpose, so this
 * is not reporting a fault — but everything the platform would have emailed is going nowhere, and that is a fact
 * an administrator reading a status report needs put in front of them rather than left to be inferred. It is the
 * kind of setting that is meant to be temporary and is easy to leave on: a report that stayed quiet about it
 * would be the reason nobody noticed the password reset messages had stopped arriving.
 * </p>
 *
 * <p>
 * <strong>Debug while it is off</strong>, rather than nothing at all. Debug reports are left out unless somebody
 * asks for them, so the ordinary report is not padded with a line saying that a development facility is off in
 * production, where it always is; but somebody diagnosing where the mail went can ask, and get an answer instead
 * of the silence a {@code null} report would leave, which reads the same as the bundle not being installed.
 * </p>
 *
 * <p>
 * <strong>Whether it is on is a question only the service registry can answer</strong>, exactly as for
 * {@link CaughtMailStatusServlet}: the configuration says what somebody asked for, while the presence of a mail
 * service carrying {@link CaughtMailService#CATCHER_PROPERTY} says what is in force.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class CaughtMailStatusReporter implements StatusReporter
{
    private static final String TITLE = "Email Catcher";

    /**
     * The catcher's own mail service, present only while it is switched on. Dynamic and greedy so that toggling
     * the setting is reflected without this component being restarted; optional because its absence is the
     * answer rather than a reason not to run.
     */
    @Reference(target = "(" + CaughtMailService.CATCHER_PROPERTY + "=true)",
        cardinality = ReferenceCardinality.OPTIONAL,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile MailService catcher;

    @Override
    public String getName()
    {
        return TITLE;
    }

    @Override
    public StatusReport report(final boolean unprivileged)
    {
        if (this.catcher == null) {
            return new StatusReport(TITLE, StatusReport.Status.DEBUG,
                "The email catcher is off, so email is being delivered normally.");
        }
        // Where the messages went is an internal path, and a report that may be posted somewhere unprivileged has
        // no business naming it. That mail is not being delivered is the point, and is said either way.
        final String text = "Outgoing email is being caught, not delivered: nothing the platform sends is"
            + " reaching anybody."
            + (unprivileged ? "" : " Messages are filed under " + CaughtMailService.CAUGHT_MAIL_PATH + ".");
        return new StatusReport(TITLE, StatusReport.Status.WARNING, text);
    }

    @Override
    public Set<String> getTags()
    {
        return Set.of("problems", "email");
    }
}
