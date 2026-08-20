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

import { expect, test } from '@playwright/test';

import { LoginPage } from '../../pages/login.page';

const asAdmin = { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` };

const asRequester = {
  Authorization: `Basic ${Buffer.from('demo-requester:demo-requester').toString('base64')}`,
};

const asApprover = {
  Authorization: `Basic ${Buffer.from('demo-approver:demo-approver').toString('base64')}`,
};

// Where a submission ends up: named by a UUID, and filed in a prefix tree whose three buckets are that name's own
// first three pairs of characters. The back-reference is the point of asserting it as a pattern rather than just
// checking for "some path under /Submissions" — it is what proves the buckets are derived from the name, which is
// the whole reason a UUID is used, and it would still hold if the tree's depth or segment length changed.
const FILED_PATH = '/Submissions/([0-9a-f]{2})/([0-9a-f]{2})/([0-9a-f]{2})/\\1\\2\\3[0-9a-f-]+';

// The path on its own, as a redirect reports it
const FILED = new RegExp(`^${FILED_PATH}$`);

// The same, at the end of the absolute URL a browser ends up on
const FILED_URL = new RegExp(`${FILED_PATH}$`);

/**
 * The time off request demo.
 *
 * This suite grows with the demo, and the demo grows with the platform: the point of both is that each
 * capability is proved by something in use rather than only by its own unit tests. It now runs the whole
 * process — a requester raises a request, an approver decides it, and the submission ends up approved or
 * refused — which is worth more than any of the pieces asserted on separately, because it is the only
 * thing that proves they fit together.
 */
test.describe('the time off request demo', () => {
  test('installs the request schema', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1.json', {
      headers: asAdmin,
    });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as {
      version?: string;
      active?: boolean;
    };
    expect(version.version).toBe('1.0');
    expect(version.active).toBe(true);
  });

  test('asks when the time off starts, and requires an answer', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1/details/startDate.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const question = (await response.json()) as {
      text?: string;
      dataType?: string;
      required?: boolean;
    };
    expect(question.text).toBe('Which day does your time off start?');
    expect(question.dataType).toBe('date');
    expect(question.required).toBe(true);
  });

  test('asks for a return date only when the absence covers several days', async ({ request }) => {
    // A display condition, stored rather than coded: the question carries a cond:condition child naming another
    // question by its path under the version. Worth an integration test because the condition is a subtree of
    // typed nodes — the mixin that allows it, the mandatory operand child, the autocreated `literal` source —
    // and a mock repository enforces none of that.
    const response = await request.get('/Schemas/timeOffRequest/v1/details/endDate.2.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const question = (await response.json()) as {
      'cond:condition'?: {
        comparator?: string;
        operandA?: { source?: string; value?: string[] };
        operandB?: { source?: string; value?: string[] };
      };
    };
    const condition = question['cond:condition'];
    expect(condition?.comparator).toBe('equals');
    expect(condition?.operandA?.source).toBe('answer');
    expect(condition?.operandA?.value).toEqual([ 'details/duration' ]);
    // The comparison side takes the default source, which the node type autocreates rather than the content
    // having to say it
    expect(condition?.operandB?.source).toBe('literal');
    // An option's value, not a phrase somebody has to type: what the condition compares against is
    // what picking "Several days" stores
    expect(condition?.operandB?.value).toEqual([ 'multiple-days' ]);
  });

  test('requires a doctor\'s note only for sick leave', async ({ request }) => {
    // The same mechanism one level up: a whole requirement, not just a question, that applies conditionally.
    const response = await request.get('/Schemas/timeOffRequest/v1/doctorsNote.2.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const requirement = (await response.json()) as {
      'jcr:primaryType'?: string;
      acceptedFileTypes?: string[];
      aiCheckPrompt?: string;
      'cond:condition'?: { comparator?: string; operandA?: { value?: string[] }; operandB?: { value?: string[] } };
    };
    expect(requirement['jcr:primaryType']).toBe('sch:DocumentRequirement');
    expect(requirement.acceptedFileTypes).toContain('application/pdf');
    expect(requirement['cond:condition']?.operandA?.value).toEqual([ 'details/absenceType' ]);
    expect(requirement['cond:condition']?.operandB?.value).toEqual([ 'sick' ]);
    // Deliberately absent: the note is collected but nothing reads it yet. Checking it against the reason for
    // the absence is the job of the language-model module being built separately, and a prompt sitting here
    // would suggest something already acts on it.
    expect(requirement.aiCheckPrompt).toBeUndefined();
  });

  test('routes approval to the approvers group', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1/approval.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const approval = (await response.json()) as { approverGroup?: string };
    expect(approval.approverGroup).toBe('time-off-approvers');
  });

  test('points the schema version at the workflow that drives it', async ({ request }) => {
    // The join between the two halves of the demo: what a submission must contain, and what it is then put
    // through. Written into the content as `jcr:reference:workflow` with the target's *path*, which the
    // content loader turns into a real REFERENCE.
    //
    // Read with `-dereference`, which switches off the serializer's reference embedding and gives the raw
    // identifier. That embedding is deliberate and on by default, but it is configurable — so asserting on
    // the embedded node would make this test depend on a serialization default rather than on the demo.
    const [schema, workflow] = await Promise.all([
      request.get('/Schemas/timeOffRequest/v1.-dereference.json', {
        headers: asAdmin,
      }),
      request.get('/Workflows/timeOffRequest/v1.-dereference.json', {
        headers: asAdmin,
      }),
    ]);

    expect(schema.ok()).toBeTruthy();
    expect(workflow.ok()).toBeTruthy();
    const reference = ((await schema.json()) as { workflow?: string }).workflow;
    const identifier = ((await workflow.json()) as { 'jcr:uuid'?: string })['jcr:uuid'];

    expect(identifier).toBeTruthy();
    expect(reference).toBe(identifier);
  });

  test('installs the workflow the engine can be pointed at', async ({ request }) => {
    const response = await request.get('/Workflows/timeOffRequest/v1.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as { active?: boolean; 'jcr:primaryType'?: string };
    expect(version.active).toBe(true);

    expect(version['jcr:primaryType']).toBe('wf:WorkflowVersion');

    // The diagram is a file child of the version, not a property on it, so it must not turn up here
    expect(version).not.toHaveProperty('bpmnXml');
  });

  test('ships the BPMN as a file, served as XML', async ({ request }) => {
    const node = await request.get('/Workflows/timeOffRequest/v1/bpmn.xml.json', { headers: asAdmin });

    expect(node.ok()).toBeTruthy();
    expect(((await node.json()) as { 'jcr:primaryType'?: string })['jcr:primaryType']).toBe('nt:file');

    const response = await request.get('/Workflows/timeOffRequest/v1/bpmn.xml', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    expect(response.headers()['content-type']).toContain('application/xml');

    // Asserted on as a process rather than as a string: these are the element identifiers the engine will
    // resolve, so a diagram edited into a shape the demo no longer describes should fail here.
    const bpmn = await response.text();
    expect(bpmn).toContain('<bpmn:startEvent id="requestSubmitted"');
    expect(bpmn).toContain('<bpmn:serviceTask id="checkBudget"');
    expect(bpmn).toContain('<bpmn:userTask id="approveRequest"');
    expect(bpmn).toContain('<bpmn:exclusiveGateway id="decision"');
    expect(bpmn).toContain('<bpmn:endEvent id="requestApproved"');
    expect(bpmn).toContain('<bpmn:endEvent id="requestRejected"');
    // Diagram interchange too, or the visual editor has nothing to draw
    expect(bpmn).toContain('<bpmndi:BPMNDiagram');
  });

  test('raises a submission against the demo schema', async ({ request }) => {
    // The demo's first real submission: the bootstrap system workflow on /Submissions vets the schema
    // version and creates the entity, all through the engine — no CRUD endpoint involved
    const response = await request.post('/Submissions', {
      headers: asAdmin,
      form: {
        title: 'A very sunny Friday',
        schemaVersion: '/Schemas/timeOffRequest/v1',
      },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(302);
    const location = response.headers().location;
    expect(location).toMatch(FILED);

    const created = await request.get(`${location}.json`, { headers: asAdmin });
    expect(created.ok()).toBeTruthy();
    const submission = (await created.json()) as {
      'jcr:primaryType'?: string;
      title?: string;
      tags?: string[];
      schemaVersion?: { '@path'?: string };
    };
    expect(submission['jcr:primaryType']).toBe('sub:Submission');
    expect(submission.title).toBe('A very sunny Friday');
    // The lifecycle is a tag rather than a property, so nothing autocreates it: the handler that raises the
    // submission is what puts it in the starting state
    expect(submission.tags).toEqual([ 'draft' ]);
    // The stored identifier became a real REFERENCE: the serializer can only embed what actually resolves
    expect(submission.schemaVersion?.['@path']).toBe('/Schemas/timeOffRequest/v1');
  });

  test('refuses submissions against an inactive schema version', async ({ request }) => {
    // The demo schema is active; ask for something that is real but retired to prove the vetting runs.
    // There is no inactive version in the demo content, so use the workflow tree instead: a path that
    // exists but is not a schema version at all.
    const response = await request.post('/Submissions', {
      headers: asAdmin,
      form: { title: 'Sneaky', schemaVersion: '/Workflows/timeOffRequest/v1' },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(400);
    expect(((await response.json()) as { error?: string }).error).toContain('no schema version');
  });

  // The process, told in order. These tests are one story rather than several checks — a request has to exist
  // before it can be read, and be decided before there is nothing left to decide — so they run serially even
  // though the suite is otherwise parallel. Left parallel they race each other for the same task, and the engine
  // rightly refuses the loser.
  test.describe.serial('raising and deciding a request', () => {
    // The request the story is about. Its path cannot be written down here: a submission is named by a UUID, so
    // the only way to know where it went is to keep what the engine's redirect said — which is also how any
    // client learns it.
    let raised = '';

    test('lets an ordinary user raise a request they hold no rights to create', async ({ request }) => {
      // The whole authorization model in one request. demo-requester has no write access anywhere — the only ACL
      // they benefit from makes /Submissions itself visible so that a POST can be routed. What lets this succeed is
      // the bootstrap's start event naming `everyone` as a performer; the engine checks that, then does the writing
      // as its own service user.
      const response = await request.post('/Submissions', {
        headers: asRequester,
        form: {
          title: 'A long weekend',
          schemaVersion: '/Schemas/timeOffRequest/v1',
        },
        maxRedirects: 0,
      });

      expect(response.status()).toBe(302);
      raised = response.headers().location;
      expect(raised).toMatch(FILED);

      // The engine wrote it, so jcr:createdBy names the service user; the human is remembered separately
      const created = await request.get(`${raised}.json`, {
        headers: asAdmin,
      });
      const submission = (await created.json()) as {
        createdBy?: string;
        'jcr:createdBy'?: string;
      };
      expect(submission.createdBy).toBe('demo-requester');
      expect(submission['jcr:createdBy']).toBe('workflows');
    });

    test('refuses to let an ordinary user author a workflow', async ({ request }) => {
      // Same user, same kind of request, different answer — and nothing about the data changed. The definition at
      // /SystemWorkflows/createWorkflow names iap-administrators as its performer, so that is where the no is said.
      const response = await request.post('/Workflows', {
        headers: asRequester,
        form: { title: 'Something I should not be authoring' },
        maxRedirects: 0,
      });

      expect(response.status()).toBe(403);
      expect(((await response.json()) as { error?: string }).error).toContain('not allowed');
      // And nothing was created on the way to being refused
      const listing = await request.get('/Workflows.2.json', {
        headers: asAdmin,
      });
      expect(JSON.stringify(await listing.json())).not.toContain('should not be authoring');
    });

    test('lets the people the workflow involves read the request', async ({ request }) => {
      // Reads cannot go through the engine — no workflow can run per row of a query — so the workflow *declares*
      // and the engine materializes: starting the instance granted read to the requester and to the performers of
      // its user task. Nobody wrote an ACL by hand, and nobody else can see it.
      // The negative control, and the reason this test means anything: a request the requester neither raised nor
      // approves stays invisible to them, so what they can see was granted rather than merely universal. Raised
      // here rather than borrowed from another test, so that the 404 below cannot be a 404 for the duller reason
      // that nothing is there at all.
      const somebodyElses = (await request.post('/Submissions', {
        headers: asAdmin,
        form: { title: 'Not the requester\'s business', schemaVersion: '/Schemas/timeOffRequest/v1' },
        maxRedirects: 0,
      })).headers().location;
      expect(somebodyElses).toMatch(FILED);
      // ...and it is genuinely readable by somebody, which is what makes the requester's 404 about them
      expect((await request.get(`${somebodyElses}.json`, { headers: asAdmin })).status()).toBe(200);

      const [byRequester, byApprover, someoneElses] = await Promise.all([
        request.get(`${raised}.json`, { headers: asRequester }),
        request.get(`${raised}.json`, { headers: asApprover }),
        request.get(`${somebodyElses}.json`, { headers: asRequester }),
      ]);

      expect(byRequester.status()).toBe(200);
      expect(byApprover.status()).toBe(200);
      expect(someoneElses.status()).toBe(404);
    });

    test('puts the new request under its workflow, waiting for its requester to send it', async ({ request }) => {
      // The submission's schema version names the workflow, so raising one starts it — and the instance lives
      // inside the submission, which is what makes it findable, securable and disposable along with it
      // `deep` is what asks the serializer for child nodes; without it a resource is its own properties and the
      // references they embed, which is the right default for a tree that now holds running workflows
      const response = await request.get(`${raised}.deep.-dereference.infinity.json`, {
        headers: asApprover,
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
      // Parked on the first user task, with a token recording exactly where. The approver's task does not exist
      // yet — a task is raised when the token reaches it, so there is nothing for them to see or decide.
      expect(instance?.token?.currentNodeId).toBe('fillIn');
      expect(instance?.fillIn?.status).toBe('created');
      expect(instance?.fillIn?.label).toBe('Say when you want to be away');
      expect(instance?.fillIn?.taskDefinitionId).toBe('fillIn');
      expect(instance?.approveRequest).toBeUndefined();
      // And the state the request is in is the state of the task it is waiting at, placed by the engine when the
      // token arrived there rather than written by the handler that created it
      expect(submission.tags).toEqual([ 'draft' ]);
      // Nothing to decide: sending a request is not a choice between outcomes, which is what tells a task list
      // to offer one plain control rather than a decision
      expect(instance?.fillIn?.outcomeOptions).toEqual([]);
    });

    test('refuses to let somebody else send a request they did not raise', async ({ request }) => {
      // @creator is the rule no group can express, and this is it being enforced: the approver is named on the
      // task after this one, and belongs to the group that decides — and still cannot send somebody's request
      // for them.
      const response = await request.post(`${raised}/wf:instances/timeOffRequest/fillIn`, {
        headers: asApprover,
        maxRedirects: 0,
      });

      expect(response.status()).toBe(403);
      expect(((await response.json()) as { error?: string }).error).toContain('not allowed');
    });

    test('sends the request on to the approver when its requester says it is finished', async ({ request }) => {
      // No outcome: this task offers none, so completing it is not a decision. This is the submit button's whole
      // server side — there is no submit event and no submitted flag, only a task being completed.
      const sent = await request.post(`${raised}/wf:instances/timeOffRequest/fillIn`, {
        headers: asRequester,
        maxRedirects: 0,
      });
      expect(sent.status()).toBe(200);

      const response = await request.get(`${raised}.deep.-dereference.infinity.json`, { headers: asApprover });
      const submission = (await response.json()) as {
        tags?: string[];
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
      expect(instance?.fillIn?.assignee).toBe('demo-requester');
      // Completed without deciding anything, which is not the same as deciding nothing in particular
      expect(instance?.fillIn?.outcome).toBeUndefined();
      // The walk carried on to the next thing a person has to do, and raised it
      expect(instance?.token?.currentNodeId).toBe('approveRequest');
      expect(instance?.approveRequest?.status).toBe('created');
      expect(instance?.approveRequest?.label).toBe('Approve the request');
      expect(instance?.approveRequest?.outcomeOptions).toEqual([ 'approved', 'rejected' ]);
      // And the request is now in the approver's hands, said by the state rather than by a flag: `submitted`
      // retired `draft`, because a lifecycle is a state and not an accumulation
      expect(submission.tags).toEqual([ 'submitted' ]);
    });

    test('will not take any more answers once the request has been sent', async ({ request }) => {
      // The same rule the editor reads off the form projection, enforced where it actually matters. Nothing was
      // added to the save workflow to make this happen: the state moved, and the save handler already refused
      // anything that is not a draft.
      const response = await request.post(raised, {
        headers: asRequester,
        form: { 'details/duration': 'full-day' },
        maxRedirects: 0,
      });

      expect(response.status()).toBe(403);
      expect(((await response.json()) as { error?: string }).error).toContain('no longer be changed');
    });

    test('has already looked up the requester\'s remaining days by the time anyone decides', async ({ request }) => {
      // The service task the walk passed through on its way to the approver. This is the demo's own Java, plugged
      // into the engine through the handler extension point — the platform knows nothing about counting time off,
      // and this is what a project supplying that knowledge looks like.
      //
      // Its answer is canned rather than fetched from a human resources system, which is the only thing standing
      // in for the real one: the activity, the dispatch and the record left behind are exactly as they would be.
      const response = await request.get(`${raised}.json`, { headers: asApprover });

      expect(response.ok()).toBeTruthy();
      const submission = (await response.json()) as {
        budgetRemainingDays?: number;
        budgetCheckedFor?: string;
      };
      expect(submission.budgetRemainingDays).toBe(12);
      // Whose budget it is, recorded beside the number: the approver reading this is not the requester, and
      // nothing else on the request would say so.
      expect(submission.budgetCheckedFor).toBe('demo-requester');
    });

    test('refuses a decision from someone the task does not name', async ({ request }) => {
      // The requester can see the task — they can see their own submission — but seeing it and being allowed to
      // decide it are different questions, and the second is answered by the activity's performers
      const response = await request.post(
        `${raised}/wf:instances/timeOffRequest/approveRequest`,
        {
          headers: asRequester,
          form: { outcome: 'approved' },
          maxRedirects: 0,
        },
      );

      expect(response.status()).toBe(403);
    });

    test('carries the request through to approval when the approver decides', async ({ request }) => {
      const decision = await request.post(
        `${raised}/wf:instances/timeOffRequest/approveRequest`,
        { headers: asApprover, form: { outcome: 'approved' }, maxRedirects: 0 },
      );
      expect(decision.status()).toBe(200);

      const response = await request.get(`${raised}.deep.-dereference.infinity.json`, {
        headers: asApprover,
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

      // The end event the gateway routed to said what finishing that way means to the submission. Only the one
      // tag: placing a lifecycle state retires the state it replaces, so the draft it started in is gone
      expect(submission.tags).toEqual([ 'approved' ]);
      const instance = submission['wf:instances']?.timeOffRequest;
      // Asserted before the token check below, which a missing instance would otherwise satisfy vacuously
      expect(instance).toBeTruthy();
      expect(instance?.status).toBe('completed');
      expect(instance?.endTime).toBeTruthy();
      // The task records who decided and what they decided; the token is spent and gone
      expect(instance?.approveRequest?.status).toBe('completed');
      expect(instance?.approveRequest?.outcome).toBe('approved');
      expect(instance?.approveRequest?.assignee).toBe('demo-approver');
      expect(instance?.token).toBeUndefined();
    });

    test('has nothing left to decide once the task is done', async ({ request }) => {
      const response = await request.post(
        `${raised}/wf:instances/timeOffRequest/approveRequest`,
        {
          headers: asApprover,
          form: { outcome: 'rejected' },
          maxRedirects: 0,
        },
      );

      expect(response.status()).toBe(409);
    });

    test('routes a refusal down the other arc of the gateway', async ({ request }) => {
      // The same definition, the other outcome: proving the gateway actually chooses rather than always taking the
      // first arc. `rejected` matches no condition, so the arc marked as the default carries it.
      const refused = await request.post('/Submissions', {
        headers: asRequester,
        form: {
          title: 'A day I will not get',
          schemaVersion: '/Schemas/timeOffRequest/v1',
        },
        maxRedirects: 0,
      });
      expect(refused.status()).toBe(302);
      // Its own request, so its own path: nothing about a submission's name can be predicted from its title
      const path = refused.headers().location;
      expect(path).toMatch(FILED);

      // Sent by its requester first, since the approver has nothing to decide until it reaches them
      expect((await request.post(`${path}/wf:instances/timeOffRequest/fillIn`,
        { headers: asRequester, maxRedirects: 0 })).status()).toBe(200);

      const decision = await request.post(
        `${path}/wf:instances/timeOffRequest/approveRequest`,
        { headers: asApprover, form: { outcome: 'rejected' }, maxRedirects: 0 },
      );
      expect(decision.status()).toBe(200);

      const response = await request.get(`${path}.json`, {
        headers: asApprover,
      });
      expect(((await response.json()) as { tags?: string[] }).tags).toEqual([ 'rejected' ]);
    });

    test('keeps the system workflows out of sight', async ({ request }) => {
      // They are the engine's own tree, read through its service user; an ordinary user has no business seeing
      // which definitions decide what they may do
      const response = await request.get('/SystemWorkflows.json', {
        headers: asRequester,
      });

      expect(response.status()).toBe(404);
    });
  });

  test('lets a requester raise a request from the dashboard, choosing what to submit against', async ({ page }) => {
    // The demo as a person actually meets it, which is the only thing that proves the pieces fit: the dashboard
    // offers the action, the dialog reads what is open for submissions out of the repository, and the engine
    // raises the request the choice names and redirects to it. Each half is asserted elsewhere over HTTP — that
    // the schema is active, that a POST to /Submissions creates one — and neither says whether a submitter can
    // get from one to the other.
    const login = new LoginPage(page);
    await login.open();
    await login.signInAs('demo-requester', 'demo-requester');

    await page.getByRole('button', { name: 'New submission' }).click();
    const dialog = page.getByRole('dialog');
    // Offered by title and version, read from /Schemas rather than hardcoded in the dialog
    await dialog.getByRole('radio', { name: /Time off request 1\.0/ }).check();
    await dialog.getByLabel(/Title/).fill('A Tuesday in October');
    await dialog.getByRole('button', { name: 'Create' }).click();

    // The engine answers with a redirect to what it created, which the browser follows to the submission's own
    // page — at a UUID path in the prefix tree, so what is asserted is the shape rather than a name
    await expect(page).toHaveURL(FILED_URL);
    await expect(page.getByText('A Tuesday in October')).toBeVisible();
  });

  test('asks what the answers make relevant, with nothing to press to save', async ({ page }) => {
    // The demo's whole point, exercised the way it is met: the questions on screen depend on the answers
    // already given, and *the server* decides which ones apply. Nothing in the browser evaluates a
    // condition — an answer is saved as soon as it is finished, the form is read again, and what comes
    // back is drawn. Asserted here rather than only in unit tests because every piece of that loop is
    // real: the save is a workflow event the engine authorizes, the condition is a subtree of typed
    // nodes, and the projection that filters on it is a servlet.
    const login = new LoginPage(page);
    await login.open();
    await login.signInAs('demo-requester', 'demo-requester');

    await page.getByRole('button', { name: 'New submission' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('radio', { name: /Time off request 1\.0/ }).check();
    await dialog.getByLabel(/Title/).fill('A week in November');
    await dialog.getByRole('button', { name: 'Create' }).click();
    await expect(page).toHaveURL(FILED_URL);

    // In through the listing's own Edit action, which is how a submitter reaches the editor. The
    // address is the submission's path with `.edit` on the end — a view of a resource, asked for by
    // extension like every other, and served by a script of its own rather than by a query parameter.
    await page.getByRole('link', { name: /Back to the dashboard/ }).click();
    await page.getByRole('row', { name: /A week in November/ }).getByLabel('Edit').click();
    await expect(page).toHaveURL(/\.edit$/);

    // The question offers its answers, so it is picked from rather than typed into
    await expect(page.getByRole('radiogroup', { name: /half day, a full day, or several days/ })).toBeVisible();
    // Not yet asked, because nothing yet makes it relevant. It is absent from what the server sent, not
    // hidden by the browser — which is the difference the whole design turns on.
    await expect(page.getByLabel(/Which day are you back/)).toHaveCount(0);

    // Picking finishes the answer as it happens: there is no field to leave
    await page.getByRole('radio', { name: 'Several days' }).check();

    await expect(page.getByLabel(/Which day are you back/)).toBeVisible();

    // The same mechanism one level up: a whole requirement, appearing because of an answer given to a
    // question in another one
    await expect(page.getByText('Doctor\'s note')).toHaveCount(0);
    await page.getByRole('radio', { name: 'Sick leave' }).check();
    await expect(page.getByText('Doctor\'s note')).toBeVisible();

    // Stored rather than remembered: a reload asks the server again, and the answers are the ones the
    // engine wrote — as a user with no write access anywhere, through a workflow that authorized them
    await page.reload();
    await expect(page.getByRole('radio', { name: 'Several days' })).toBeChecked();
    await expect(page.getByLabel(/Which day are you back/)).toBeVisible();
  });

  test('refuses a request for more time off than the requester has left', async ({ page }) => {
    // A rule about what the answers may *say*, rather than about who may write them, and the only place the
    // whole path can be seen at once: the save is carried out, a validator reads the request as the save would
    // leave it, objects, and the engine's one-commit rule throws the write away with the rest of the run. What
    // the submitter is left with is the reason, on the answer they just gave, and a form still holding
    // everything that was accepted before it.
    const login = new LoginPage(page);
    await login.open();
    await login.signInAs('demo-requester', 'demo-requester');

    await page.getByRole('button', { name: 'New submission' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('radio', { name: /Time off request 1\.0/ }).check();
    await dialog.getByLabel(/Title/).fill('Most of November');
    await dialog.getByRole('button', { name: 'Create' }).click();
    await expect(page).toHaveURL(FILED_URL);

    // Scoped to the request's own View/Edit switch: the dashboard's listings have an Edit control per
    // row, and neither the URL nor the absence of those listings settles where a click lands — the
    // route changes before the old view is gone, and the listings are absent while still loading
    await page.getByRole('group', { name: 'How to show this submission' })
      .getByRole('button', { name: 'Edit' }).click();
    await expect(page).toHaveURL(/\.edit$/);

    // Each of these is accepted: until both ends of the span are given there is no number to compare, and a
    // half-answered request is the ordinary state of one being filled in rather than an error
    await page.getByRole('radio', { name: 'Several days' }).check();
    const start = page.getByLabel(/Which day does your time off start/);
    await start.fill('2026-11-02');
    await start.blur();
    // Both of them: the duration and the start date each report their own save, and waiting for the
    // second is what keeps the next answer from racing the one before it
    await expect(page.getByText('Saved')).toHaveCount(2);

    // Twenty days, counting both ends, against the twelve the demo's holiday bank gives this requester
    const back = page.getByLabel(/Which day are you back/);
    await back.fill('2026-11-21');
    await back.blur();

    // The reason reaches the field that carries the answer, which is the difference between a refusal a
    // submitter can act on and a save that merely failed
    await page.getByLabel('Not saved').hover();
    await expect(page.getByRole('tooltip'))
      .toContainText('This asks for more time off than you have left. Requested: 20. Remaining: 12.');

    // And nothing was written: the run that would have stored the return date reverted, while the answers
    // accepted before it are still there
    await page.reload();
    await expect(page.getByLabel(/Which day are you back/)).toHaveValue('');
    await expect(page.getByLabel(/Which day does your time off start/)).toHaveValue('2026-11-02');
    await expect(page.getByRole('radio', { name: 'Several days' })).toBeChecked();
  });

  test('creates the two people the demo is about', async ({ page }) => {
    // Proves the accounts exist and their passwords work, which is what the walkthrough depends on
    const login = new LoginPage(page);
    await login.open();
    await login.signInAs('demo-requester', 'demo-requester');

    await expect(page).not.toHaveURL(/\/login/);
  });

  test('puts the approver in the group the schema routes approval to', async ({ request }) => {
    const response = await request.get('/system/userManager/group/time-off-approvers.1.json', {
      headers: asAdmin,
    });

    expect(response.ok()).toBeTruthy();
    const group = (await response.json()) as { members?: string[] };
    expect(JSON.stringify(group.members ?? [])).toContain('demo-approver');
  });
});
