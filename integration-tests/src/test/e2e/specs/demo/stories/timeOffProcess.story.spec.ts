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


import { expect, test, type APIRequestContext } from '@playwright/test';

import { ADMIN, adminAuth, basicAuth } from '../../../support/auth';
import { tagsOf } from '../../../support/nodes';
import {
  APPROVER, localDay, raiseRequest, REQUESTER, taskUrl, TIME_OFF_SCHEMA_VERSION,
} from '../../../support/timeOffDemo';

const requesterAuth = basicAuth(REQUESTER);

const approverAuth = basicAuth(APPROVER);

/** The tags a request inherits from its parts, `incomplete` among them while something it asks is missing. */
async function aggregatedTagsOf(request: APIRequestContext, path: string): Promise<string[]> {
  const response = await request.get(`${path}.json`, { headers: adminAuth });
  expect(response.ok(), `${path} could not be read`).toBeTruthy();
  return ((await response.json()) as { aggregatedTags?: string[] }).aggregatedTags ?? [];
}

/** Saves answers to a request, as the editor does when the requester finishes one. */
async function answer(request: APIRequestContext, path: string, answers: Record<string, string>) {
  return request.post(path, { headers: requesterAuth, form: answers, maxRedirects: 0 });
}

/**
 * A story: the time off process, over HTTP.
 *
 * A requester raises requests, an approver decides them, and each ends up approved or refused. Every step changes
 * the repository, so these are stories, run after the specs that only look.
 */
test.describe('a story: the time off process, over HTTP', () => {
  test('raises a submission against the demo schema', async ({ request }) => {
    // The bootstrap system workflow on /Submissions vets the schema version and creates the entity through the
    // engine. No CRUD endpoint is involved.
    const location = await raiseRequest(request, ADMIN, 'A very sunny Friday');

    const created = await request.get(`${location}.json`, { headers: adminAuth });
    expect(created.ok()).toBeTruthy();
    const submission = (await created.json()) as {
      'jcr:primaryType'?: string;
      title?: string;
      tags?: string[];
      aggregatedTags?: string[];
      schemaVersion?: { '@path'?: string };
    };
    expect(submission['jcr:primaryType']).toBe('sub:Submission');
    expect(submission.title).toBe('A very sunny Friday');
    // The lifecycle is a tag, so nothing autocreates it: the handler that raises the submission places `draft`.
    // `incomplete` sits on the parts nobody has answered yet and reaches the request by aggregation.
    expect(submission.tags).toEqual([ 'draft' ]);
    expect(submission.aggregatedTags).toContain('incomplete');
    // The stored identifier became a real REFERENCE: the serializer can only embed what actually resolves
    expect(submission.schemaVersion?.['@path']).toBe(TIME_OFF_SCHEMA_VERSION);
  });

  // The process, told in order. Each test needs the one before it: a request must exist before it is read,
  // and be decided before nothing is left to decide. Run in parallel, they race for the same task and the
  // engine refuses the loser.
  test.describe.serial('raising and deciding a request', () => {
    // The request the story is about. A submission is named by a UUID, so its path is whatever the engine's
    // redirect said.
    let raised = '';

    test('lets an ordinary user raise a request they hold no rights to create', async ({ request }) => {
      // The whole authorization model in one request. demo-requester has no write access anywhere. The only ACL
      // that helps them makes /Submissions visible, so that a POST can be routed. The bootstrap's start event
      // names `everyone` as a performer, and the engine checks that, then writes as its own service user.
      raised = await raiseRequest(request, REQUESTER, 'A long weekend');

      // The engine wrote it, so jcr:createdBy names the service user; the human is remembered separately
      const created = await request.get(`${raised}.json`, {
        headers: adminAuth,
      });
      const submission = (await created.json()) as {
        createdBy?: string;
        'jcr:createdBy'?: string;
      };
      expect(submission.createdBy).toBe(REQUESTER.username);
      expect(submission['jcr:createdBy']).toBe('workflows');
    });

    test('refuses to let an ordinary user author a workflow', async ({ request }) => {
      // Same user, same kind of request, different answer. The definition at /SystemWorkflows/createWorkflow
      // names iap-administrators as its performer.
      const response = await request.post('/Workflows', {
        headers: requesterAuth,
        form: { title: 'Something I should not be authoring' },
        maxRedirects: 0,
      });

      expect(response.status()).toBe(403);
      expect(((await response.json()) as { error?: string }).error).toContain('not allowed');
      // Nothing was created before the refusal
      const listing = await request.get('/Workflows.2.json', {
        headers: adminAuth,
      });
      expect(JSON.stringify(await listing.json())).not.toContain('should not be authoring');
    });

    test('lets the people the workflow involves read the request', async ({ request }) => {
      // No workflow can run per row of a query, so reads do not go through the engine. The workflow declares who
      // may read, and the engine grants it: starting the instance gave read to the requester and to the
      // performers of its user task. Nobody wrote an ACL by hand.
      //
      // The negative control: a request the requester neither raised nor approves stays invisible to them. It is
      // raised here rather than borrowed, so the 404 below cannot mean that nothing is there.
      const somebodyElses = await raiseRequest(request, ADMIN, 'Not the requester\'s business');
      // Somebody can read it, so the requester's 404 is about them
      expect((await request.get(`${somebodyElses}.json`, { headers: adminAuth })).status()).toBe(200);

      const [byRequester, byApprover, someoneElses] = await Promise.all([
        request.get(`${raised}.json`, { headers: requesterAuth }),
        request.get(`${raised}.json`, { headers: approverAuth }),
        request.get(`${somebodyElses}.json`, { headers: requesterAuth }),
      ]);

      expect(byRequester.status()).toBe(200);
      expect(byApprover.status()).toBe(200);
      expect(someoneElses.status()).toBe(404);
    });

    test('puts the new request under its workflow, waiting for its requester to send it', async ({ request }) => {
      // The submission's schema version names the workflow, so raising one starts it. The instance lives inside
      // the submission, so it is found, secured and deleted along with it.
      // `deep` asks the serializer for child nodes. Without it a resource is its own properties and the
      // references they embed.
      const response = await request.get(`${raised}.deep.-dereference.infinity.json`, {
        headers: approverAuth,
      });
      expect(response.ok()).toBeTruthy();
      const submission = (await response.json()) as {
        tags?: string[];
        'wf:instances'?: {
          timeOffRequest?: {
            status?: string;
            token?: { currentNodeId?: string };
            fillIn?: { status?: string; label?: string; taskDefinitionId?: string; outcomeOptions?: string[] };
            approveRequest?: unknown;
          };
        };
      };

      const instance = submission['wf:instances']?.timeOffRequest;
      expect(instance).toBeTruthy();
      expect(instance?.status).toBe('active');
      // Parked on the first user task, with a token recording where. The approver's task does not exist yet: a
      // task is raised when the token reaches it.
      expect(instance?.token?.currentNodeId).toBe('fillIn');
      expect(instance?.fillIn?.status).toBe('created');
      expect(instance?.fillIn?.label).toBe('Say when you want to be away');
      expect(instance?.fillIn?.taskDefinitionId).toBe('fillIn');
      expect(instance?.approveRequest).toBeUndefined();
      // Filling it in is the draft
      expect(submission.tags).toEqual([ 'draft' ]);
      // Sending is not a choice between outcomes. An empty list tells a task list to offer one plain control
      // rather than a decision.
      expect(instance?.fillIn?.outcomeOptions).toEqual([]);
    });

    test('refuses to let somebody else send a request they did not raise', async ({ request }) => {
      // `@creator` is a rule no group can express. The approver is named on the next task and belongs to the
      // group that decides, and still cannot send this request.
      const response = await request.post(taskUrl(raised, 'fillIn'), {
        headers: approverAuth,
        maxRedirects: 0,
      });

      expect(response.status()).toBe(403);
      expect(((await response.json()) as { error?: string }).error).toContain('not allowed');
    });

    test('refuses more values than a question takes', async ({ request }) => {
      // The duration takes one value, the default answer count, and the editor cannot send two. This proves end
      // to end that the constraint validators run in the save workflow's validation step.
      const response = await request.post(raised, {
        headers: { ...requesterAuth, 'Content-Type': 'application/x-www-form-urlencoded' },
        data: 'details%2Fduration=full-day&details%2Fduration=half-day',
        maxRedirects: 0,
      });

      expect(response.status()).toBe(400);
      expect(((await response.json()) as { error?: string }).error)
        .toContain('Too many values for "Is this a half day, a full day, or several days?". Given: 2. Allowed: 1.');
    });

    test('sends the request on to the approver when its requester says it is finished', async ({ request }) => {
      // No outcome: this task offers none, so completing it is not a decision. There is no submit event. Sending
      // a request is completing this task.
      const sent = await request.post(taskUrl(raised, 'fillIn'), {
        headers: requesterAuth,
        maxRedirects: 0,
      });
      expect(sent.status()).toBe(200);

      const response = await request.get(`${raised}.deep.-dereference.infinity.json`, { headers: approverAuth });
      const submission = (await response.json()) as {
        tags?: string[];
        aggregatedTags?: string[];
        'wf:instances'?: {
          timeOffRequest?: {
            token?: { currentNodeId?: string };
            fillIn?: { status?: string; assignee?: string; outcome?: string };
            approveRequest?: { status?: string; label?: string; outcomeOptions?: string[] };
          };
        };
      };
      const instance = submission['wf:instances']?.timeOffRequest;
      expect(instance).toBeTruthy();
      expect(instance?.fillIn?.status).toBe('completed');
      expect(instance?.fillIn?.assignee).toBe(REQUESTER.username);
      expect(instance?.fillIn?.outcome).toBeUndefined();
      // The walk carried on to the next user task and raised it
      expect(instance?.token?.currentNodeId).toBe('approveRequest');
      expect(instance?.approveRequest?.status).toBe('created');
      expect(instance?.approveRequest?.label).toBe('Approve the request');
      expect(instance?.approveRequest?.outcomeOptions).toEqual([ 'approved', 'rejected' ]);
      // The addTag step after the task placed `submitted` in place of `draft`: a lifecycle is a state, not an
      // accumulation. `incomplete` survives. The editor would not offer to send an unanswered request, but only
      // the UI enforces completeness, and this test talks HTTP.
      expect(submission.tags).toEqual([ 'submitted' ]);
      expect(submission.aggregatedTags).toContain('incomplete');
    });

    test('will not take any more answers once the request has been sent', async ({ request }) => {
      // The save handler refuses anything that is not a draft, and the request no longer is one
      const response = await request.post(raised, {
        headers: requesterAuth,
        form: { 'details/duration': 'full-day' },
        maxRedirects: 0,
      });

      expect(response.status()).toBe(409);
      expect(((await response.json()) as { error?: string }).error).toContain('no longer be changed');
    });

    test('has already looked up the requester\'s remaining days by the time anyone decides', async ({ request }) => {
      // The demo's own Java, plugged into the engine through the handler extension point. It runs as soon as the
      // request is raised, before the requester's task. The platform knows nothing about counting time off.
      //
      // Its answer is canned rather than fetched from a human resources system. The activity, the dispatch and
      // the record it leaves are as they would be with a real one.
      const response = await request.get(`${raised}.json`, { headers: approverAuth });

      expect(response.ok()).toBeTruthy();
      expect(((await response.json()) as { budgetRemainingDays?: number }).budgetRemainingDays).toBe(12);
    });

    test('refuses a decision from someone the task does not name', async ({ request }) => {
      // The requester can see the task, since they can see their own submission. Seeing it and being allowed to
      // decide it are different questions, and the activity's performers answer the second.
      const response = await request.post(
        taskUrl(raised, 'approveRequest'),
        {
          headers: requesterAuth,
          form: { outcome: 'approved' },
          maxRedirects: 0,
        },
      );

      expect(response.status()).toBe(403);
    });

    test('carries the request through to approval when the approver decides', async ({ request }) => {
      const decision = await request.post(
        taskUrl(raised, 'approveRequest'),
        { headers: approverAuth, form: { outcome: 'approved' }, maxRedirects: 0 },
      );
      expect(decision.status()).toBe(200);

      const response = await request.get(`${raised}.deep.-dereference.infinity.json`, {
        headers: approverAuth,
      });
      const submission = (await response.json()) as {
        tags?: string[];
        'wf:instances'?: {
          timeOffRequest?: {
            status?: string;
            endTime?: string;
            token?: unknown;
            approveRequest?: { status?: string; outcome?: string; assignee?: string };
          };
        };
      };

      // The end event on the approved branch places `approved` and retires `submitted`
      expect(submission.tags).toEqual([ 'approved' ]);
      const instance = submission['wf:instances']?.timeOffRequest;
      // Asserted before the token check below, which a missing instance would otherwise satisfy vacuously
      expect(instance).toBeTruthy();
      expect(instance?.status).toBe('completed');
      expect(instance?.endTime).toBeTruthy();
      expect(instance?.approveRequest?.status).toBe('completed');
      expect(instance?.approveRequest?.outcome).toBe('approved');
      expect(instance?.approveRequest?.assignee).toBe(APPROVER.username);
      expect(instance?.token).toBeUndefined();
    });

    test('has nothing left to decide once the task is done', async ({ request }) => {
      const response = await request.post(
        taskUrl(raised, 'approveRequest'),
        {
          headers: approverAuth,
          form: { outcome: 'rejected' },
          maxRedirects: 0,
        },
      );

      expect(response.status()).toBe(409);
    });

    test('routes a refusal down the other arc of the gateway', async ({ request }) => {
      // The same definition, the other outcome: the gateway chooses rather than always taking the first arc.
      // `rejected` matches no condition, so the default arc carries it.
      const path = await raiseRequest(request, REQUESTER, 'A day I will not get');

      // The requester sends it first: the approver's task does not exist until then
      expect((await request.post(taskUrl(path, 'fillIn'),
        { headers: requesterAuth, maxRedirects: 0 })).status()).toBe(200);

      const decision = await request.post(
        taskUrl(path, 'approveRequest'),
        { headers: approverAuth, form: { outcome: 'rejected' }, maxRedirects: 0 },
      );
      expect(decision.status()).toBe(200);

      expect(await tagsOf(request, path)).toEqual([ 'rejected' ]);
    });
  });

  test('flags a request whose time off starts tomorrow, once it is sent', async ({ request }) => {
    const path = await raiseRequest(request, REQUESTER, 'Tomorrow, as it turns out');
    expect((await answer(request, path,
      { 'details/duration': 'full-day', 'details/startDate': localDay(1) })).status()).toBe(200);

    // Nothing judges the date until the request is sent
    expect(await tagsOf(request, path)).not.toContain('urgent');

    expect((await request.post(taskUrl(path, 'fillIn'), { headers: requesterAuth, maxRedirects: 0 })).status())
      .toBe(200);

    // Recorded on the request itself, not worked out by whoever looks at it
    expect(await tagsOf(request, path)).toEqual(expect.arrayContaining([ 'submitted', 'urgent' ]));
  });

  test('lets only the nightly sweep ask for a request to be judged again', async ({ request }) => {
    // The event the sweep sends, named by selector. The extension is what makes it a selector: without one, Sling
    // reads the last dot-segment as the extension and the POST becomes an ordinary save. Its start event admits the
    // sweep's service user alone, and an administrator passes every such check.
    const path = await raiseRequest(request, REQUESTER, 'Judged at night');

    const byRequester = await request.post(`${path}.reassessTimeOffUrgency.json`, {
      headers: requesterAuth,
      maxRedirects: 0,
    });
    expect(byRequester.status()).toBe(403);

    const byAdministrator = await request.post(`${path}.reassessTimeOffUrgency.json`, {
      headers: adminAuth,
      maxRedirects: 0,
    });
    expect(byAdministrator.status()).toBe(200);
  });

  test('refuses a request for more time off than the requester has left', async ({ request }) => {
    // A rule about what the answers may say, not about who may write them. The save runs, a validator reads the
    // request as the save would leave it and objects, and the engine's one-commit rule throws the write away.
    const path = await raiseRequest(request, REQUESTER, 'Most of November');
    // Each accepted: until both ends of the span are given, there is no number to compare
    expect((await answer(request, path,
      { 'details/duration': 'multiple-days', 'details/startDate': '2026-11-02' })).status()).toBe(200);

    // Twenty days, counting both ends, against the twelve the demo's holiday bank gives this requester
    const refused = await answer(request, path, { 'details/endDate': '2026-11-21' });

    expect(refused.status()).toBe(400);
    expect(((await refused.json()) as { error?: string }).error)
      .toContain('This asks for more time off than you have left. Requested: 20. Remaining: 12.');
  });

  test('holds back a request whose last day away comes before its first', async ({ request }) => {
    // Accepted, so that a span can be moved one day at a time, but incomplete, so that it cannot be sent
    const path = await raiseRequest(request, REQUESTER, 'Back to front');
    expect((await answer(request, path, {
      'details/duration': 'multiple-days',
      'details/startDate': '2026-11-06',
      'details/endDate': '2026-11-02',
      'details/absenceType': 'vacation',
    })).status()).toBe(200);

    expect(await aggregatedTagsOf(request, path)).toContain('incomplete');

    // The only thing missing was the order, so putting the days right completes the request
    expect((await answer(request, path, { 'details/endDate': '2026-11-09' })).status()).toBe(200);

    expect(await aggregatedTagsOf(request, path)).not.toContain('incomplete');
  });
});
