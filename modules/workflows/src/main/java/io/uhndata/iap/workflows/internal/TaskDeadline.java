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
package io.uhndata.iap.workflows.internal;

import java.util.Calendar;
import java.util.List;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.TaskInstance;

/**
 * The deadline a task is running against, and the boundary timer it belongs to: what a {@code timeout} aimed at the
 * task asks about.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TaskDeadline
{
    /** The service user that delivers passed deadlines, and who a boundary timer admits when it names nobody. */
    static final String TIMER_USER = "iap-timer";

    private final TaskInstance task;

    private final IntermediateCatchingEvent timer;

    /**
     * Finds the boundary timer counting down to a task.
     *
     * @param task the task a {@code timeout} is aimed at
     * @param definition the activity the task was raised from
     * @throws NoApplicableWorkflowException when nothing is counting down to the task
     */
    TaskDeadline(final TaskInstance task, final Activity definition) throws NoApplicableWorkflowException
    {
        this.task = task;
        this.timer = definition.getBoundaryEvents().stream()
            .filter(event -> event.getElementId().equals(task.getDueEventId()))
            .findFirst()
            .orElseThrow(() -> new NoApplicableWorkflowException("The task " + task.getPath()
                + " has no deadline to run out: nothing is counting down to it"));
    }

    /**
     * The boundary timer the deadline belongs to.
     *
     * @return the timer
     */
    IntermediateCatchingEvent getTimer()
    {
        return this.timer;
    }

    /**
     * Who may fire the timer. The task's own performers do not count: they may decide the work, which is not the
     * same as declaring it late. A timer that names nobody admits the {@value #TIMER_USER} service user, which is who
     * the deadline sweep delivers as.
     *
     * @return the principals it admits
     */
    List<String> listWhoMayFire()
    {
        final List<String> performers = this.timer.getPerformers();
        return performers.isEmpty() ? List.of(TIMER_USER) : performers;
    }

    /**
     * Refuses to time the task out early, whoever asks.
     *
     * @throws InvalidStateException when the deadline has not passed yet
     */
    void checkPassed() throws InvalidStateException
    {
        final Calendar due = this.task.getDueDate();
        if (due == null || due.after(Calendar.getInstance())) {
            throw new InvalidStateException("The task " + this.task.getPath() + " has not run out of time yet");
        }
    }
}
