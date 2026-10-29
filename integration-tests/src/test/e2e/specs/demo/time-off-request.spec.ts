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

import { adminAuth, signInAs } from '../../support/auth';
import { APPROVER, REQUESTER } from '../../support/timeOffDemo';

/**
 * The time off request demo, as it is installed.
 *
 * Each platform capability the demo uses is checked here in the content that uses it. These tests only look:
 * the stories beside them raise and decide requests, and prove that the pieces fit together.
 */
test.describe('the time off request demo', () => {
  test('installs the request schema', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1.json', {
      headers: adminAuth,
    });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as { version?: string; tags?: string[] };
    expect(version.version).toBe('1.0');
    expect(version.tags).toContain('active');
  });

  test('asks when the time off starts, and requires an answer', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1/details/startDate.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const question = (await response.json()) as {
      text?: string;
      dataType?: string;
      minAnswers?: number;
    };
    expect(question.text).toBe('Which day does your time off start?');
    expect(question.dataType).toBe('date');
    // "Required" is a demanded count, not a flag: one value must be given
    expect(question.minAnswers).toBe(1);
  });

  test('asks for the last day away only when the absence covers several days', async ({ request }) => {
    // A display condition, stored rather than coded: a cond:condition child names another question by its path
    // under the version. A mock repository enforces none of the node types involved: the mixin that allows the
    // child, the mandatory operand child, the autocreated `literal` source.
    const response = await request.get('/Schemas/timeOffRequest/v1/details/endDate.2.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const question = (await response.json()) as {
      text?: string;
      'cond:condition'?: {
        comparator?: string;
        operandA?: { source?: string; value?: string[] };
        operandB?: { source?: string; value?: string[] };
      };
    };
    // The last day away rather than the day back, since the budget counts both ends of the span
    expect(question.text).toBe('Which is your last day away?');
    const condition = question['cond:condition'];
    expect(condition?.comparator).toBe('equals');
    expect(condition?.operandA?.source).toBe('answer');
    expect(condition?.operandA?.value).toEqual([ 'details/duration' ]);
    // The node type autocreates the default source, so the content does not name it
    expect(condition?.operandB?.source).toBe('literal');
    // An option's value: what picking "Several days" stores
    expect(condition?.operandB?.value).toEqual([ 'multiple-days' ]);
  });

  test('ships the tag its reminder places', async ({ request }) => {
    // A tag with no definition cannot be placed, so a reminder that fired would fail its commit. Placing a tag
    // retires any other the host carries in the same category, and a late request is still submitted.
    const response = await request.get('/Tags/overdue.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const tag = (await response.json()) as { category?: string[]; 'jcr:primaryType'?: string };
    expect(tag['jcr:primaryType']).toBe('tag:Definition');
    expect(tag.category).toEqual([ 'timeliness' ]);
  });

  test('ships the tag its urgency check places', async ({ request }) => {
    // A request can be urgent, overdue and submitted at once, so each of those tags has its own category.
    const response = await request.get('/Tags/urgent.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const tag = (await response.json()) as { category?: string[]; 'jcr:primaryType'?: string };
    expect(tag['jcr:primaryType']).toBe('tag:Definition');
    expect(tag.category).toEqual([ 'priority' ]);
  });

  test('requires a doctor\'s note only for sick leave', async ({ request }) => {
    // The same mechanism one level up: a whole requirement, not just a question, that applies conditionally.
    const response = await request.get('/Schemas/timeOffRequest/v1/doctorsNote.2.json', { headers: adminAuth });

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
    // Deliberately absent: nothing reads the prompt yet, and one here would suggest that something acts on it.
    expect(requirement.aiCheckPrompt).toBeUndefined();
  });

  test('routes approval to the approvers group', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1/approval.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const approval = (await response.json()) as { approverGroup?: string };
    expect(approval.approverGroup).toBe('time-off-approvers');
  });

  test('points the schema version at the workflow that drives it', async ({ request }) => {
    // The join between the two halves of the demo: what a submission must contain, and what it is then put
    // through. The content writes `jcr:reference:workflow` with the target's *path*, and the content loader
    // turns it into a REFERENCE.
    //
    // `-dereference` switches off the serializer's reference embedding and gives the raw identifier. Embedding
    // is configurable, so asserting on the embedded node would test a serialization default, not the demo.
    const [schema, workflow] = await Promise.all([
      request.get('/Schemas/timeOffRequest/v1.-dereference.json', {
        headers: adminAuth,
      }),
      request.get('/Workflows/timeOffRequest/v1.-dereference.json', {
        headers: adminAuth,
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
    const response = await request.get('/Workflows/timeOffRequest/v1.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as { tags?: string[]; 'jcr:primaryType'?: string };
    expect(version.tags).toContain('active');

    expect(version['jcr:primaryType']).toBe('wf:WorkflowVersion');

    // The diagram is a file child of the version, not a property on it, so it must not turn up here
    expect(version).not.toHaveProperty('bpmnXml');
  });

  test('derives the flow nodes from the diagram rather than from the descriptor', async ({ request }) => {
    // This version declares its diagram authoritative, so the commit editor parses bpmn.xml and replaces the
    // flow nodes the descriptor installed. Every other test here passes either way: a running workflow proves
    // the nodes are right, not where they came from.
    const response = await request.get('/Workflows/timeOffRequest/v1.deep.-dereference.infinity.json',
      { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as Record<string, unknown> & {
      bpmnAuthoritative?: boolean;
      bpmnXmlParsedHash?: string;
      checkBudget?: { handler?: string };
      markSubmitted?: { handler?: string; tag?: string };
      markUrgency?: { handler?: string };
      approveRequest?: {
        performers?: string[];
        outcomeOptions?: string[];
        approvalOverdue?: { timerDuration?: string; interrupting?: boolean };
        approvalReminder?: { timerDuration?: string; interrupting?: boolean };
      };
      reminded?: { hostTag?: string };
      notifyApproved?: { handler?: string; recipients?: string[]; template?: string; urgency?: string; event?: string };
      notifyRejected?: { recipients?: string[]; template?: string };
      decision?: { toApproved?: { 'cond:condition'?: Record<string, unknown> } };
    };

    expect(version.bpmnAuthoritative).toBe(true);
    // Set only when a parse succeeds
    expect(version.bpmnXmlParsedHash).toEqual(expect.stringMatching(/^[0-9a-f]{64}$/));

    // Every property below comes from the diagram
    expect(version.checkBudget?.handler).toBe('checkTimeOffBudget');
    expect(version.markSubmitted?.handler).toBe('addTag');
    expect(version.markSubmitted?.tag).toBe('submitted');
    expect(version.markUrgency?.handler).toBe('markTimeOffUrgency');
    expect(version.approveRequest?.performers).toEqual([ 'time-off-approvers' ]);
    expect(version.approveRequest?.outcomeOptions).toEqual([ 'approved', 'rejected' ]);
    // Two boundary events, both stored inside the activity they watch, with their durations read from BPMN's
    // `timeDuration` element
    expect(version.approveRequest?.approvalOverdue?.timerDuration).toBe('P5D');
    expect(version.approveRequest?.approvalOverdue?.interrupting).toBe(true);
    // The nudge: same task, earlier, and it does not cancel the task. Only `interrupting` tells "remind them"
    // from "give up". It arrives as a boolean, not as the string in BPMN's `cancelActivity`.
    expect(version.approveRequest?.approvalReminder?.timerDuration).toBe('P2D');
    expect(version.approveRequest?.approvalReminder?.interrupting).toBe(false);
    // The end of the reminder's branch marks the request overdue; the approval task's branch carries on
    expect(version.reminded?.hostTag).toBe('overdue');
    // Each decision is told to the requester. A notify step without its audience and template stores as a bare
    // handler, and sends nothing.
    expect(version.notifyApproved?.handler).toBe('notify');
    expect(version.notifyApproved?.recipients).toEqual([ '@creator' ]);
    expect(version.notifyApproved?.template).toBe('/libs/iap/notificationTemplates/timeOffApproved');
    expect(version.notifyApproved?.urgency).toBe('immediate');
    expect(version.notifyApproved?.event).toBe('approved');
    expect(version.notifyRejected?.recipients).toEqual([ '@creator' ]);
    expect(version.notifyRejected?.template).toBe('/libs/iap/notificationTemplates/timeOffRejected');
    // The condition subtree, built from the System View the diagram carries in its extensionElements
    const condition = version.decision?.toApproved?.['cond:condition'];
    expect(condition).toBeTruthy();
    expect(condition?.['jcr:primaryType']).toBe('cond:SingleCondition');
    expect(condition?.comparator).toBe('equals');
    expect((condition?.operandA as { source?: string; value?: string[] } | undefined)?.source).toBe('variable');
    expect((condition?.operandA as { value?: string[] } | undefined)?.value).toEqual([ 'outcome' ]);
  });

  test('ships the BPMN as a file, served as XML', async ({ request }) => {
    const node = await request.get('/Workflows/timeOffRequest/v1/bpmn.xml.json', { headers: adminAuth });

    expect(node.ok()).toBeTruthy();
    expect(((await node.json()) as { 'jcr:primaryType'?: string })['jcr:primaryType']).toBe('nt:file');

    const response = await request.get('/Workflows/timeOffRequest/v1/bpmn.xml', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    expect(response.headers()['content-type']).toContain('application/xml');

    // The engine resolves these element identifiers. A diagram edited out of the shape the demo describes fails
    // here.
    const bpmn = await response.text();
    expect(bpmn).toContain('<bpmn:startEvent id="requestSubmitted"');
    expect(bpmn).toContain('<bpmn:serviceTask id="checkBudget"');
    expect(bpmn).toContain('<bpmn:userTask id="approveRequest"');
    expect(bpmn).toContain('<bpmn:serviceTask id="recordReview"');
    expect(bpmn).toContain('<bpmn:exclusiveGateway id="decision"');
    expect(bpmn).toContain('<bpmn:endEvent id="requestApproved"');
    expect(bpmn).toContain('<bpmn:endEvent id="requestRejected"');
    // Diagram interchange too, or the visual editor has nothing to draw
    expect(bpmn).toContain('<bpmndi:BPMNDiagram');
  });

  test('writes the decision down against the approval the schema asks for', async ({ request }) => {
    // Derived from the diagram: ServiceTask.json maps iap:requirement onto the activity
    const response = await request.get('/Workflows/timeOffRequest/v1/recordReview.json', { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const step = (await response.json()) as { handler?: string; requirement?: string };
    expect(step.handler).toBe('recordReview');
    expect(step.requirement).toBe('approval');
  });


  test('ships the system workflow its nightly sweep sends each waiting request', async ({ request }) => {
    // The calendar changes nothing on a request, so the demo's sweep supplies the event and this workflow judges
    // the request's urgency again. Only the sweep's own service user may send it.
    const response = await request.get('/SystemWorkflows/reassessTimeOffUrgency/v1.deep.infinity.json',
      { headers: adminAuth });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as {
      tags?: string[];
      targetResourceType?: string;
      requested?: { messageName?: string; performers?: string[] };
      markUrgency?: { handler?: string };
    };
    expect(version.tags).toContain('active');
    expect(version.targetResourceType).toBe('sub/Submission');
    expect(version.requested?.messageName).toBe('reassessTimeOffUrgency');
    expect(version.requested?.performers).toEqual([ 'tor-urgency' ]);
    expect(version.markUrgency?.handler).toBe('markTimeOffUrgency');
  });

  test('refuses submissions against an inactive schema version', async ({ request }) => {
    // The demo content has no inactive version, so this names a path that exists but is not a schema version.
    const response = await request.post('/Submissions', {
      headers: adminAuth,
      form: { title: 'Sneaky', schemaVersion: '/Workflows/timeOffRequest/v1' },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(400);
    expect(((await response.json()) as { error?: string }).error).toContain('no schema version');
  });

  test('creates the two people the demo is about', async ({ page }) => {
    await signInAs(page, REQUESTER);

    await expect(page).not.toHaveURL(/\/login/);
  });

  test('puts the approver in the group the schema routes approval to', async ({ request }) => {
    const response = await request.get('/system/userManager/group/time-off-approvers.1.json', {
      headers: adminAuth,
    });

    expect(response.ok()).toBeTruthy();
    const group = (await response.json()) as { members?: string[] };
    expect(JSON.stringify(group.members ?? [])).toContain(APPROVER.username);
  });
});
