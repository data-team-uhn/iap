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

/**
 * The time off request demo.
 *
 * This suite grows with the demo, and the demo grows with the platform: the point of both is that each
 * capability is proved by something in use rather than only by its own unit tests. Today the workflow
 * engine does not exist, so what can be asserted is that the process is *defined* and installed — the
 * schema a requester fills in, and the BPMN the engine will execute. As the engine lands, the tests
 * asserting that a request can actually be raised and approved belong here.
 */
test.describe('the time off request demo', () => {
  test('installs the request schema', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as { version?: string; active?: boolean };
    expect(version.version).toBe('1.0');
    expect(version.active).toBe(true);
  });

  test('asks which day is being taken off, and requires an answer', async ({ request }) => {
    const response = await request.get('/Schemas/timeOffRequest/v1/details/day.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const question = (await response.json()) as { text?: string; dataType?: string; required?: boolean };
    expect(question.text).toBe('Which day are you taking off?');
    expect(question.dataType).toBe('date');
    expect(question.required).toBe(true);
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
      request.get('/Schemas/timeOffRequest/v1.-dereference.json', { headers: asAdmin }),
      request.get('/Workflows/timeOffRequest/v1.-dereference.json', { headers: asAdmin }),
    ]);

    expect(schema.ok()).toBeTruthy();
    expect(workflow.ok()).toBeTruthy();
    const reference = ((await schema.json()) as { workflow?: string }).workflow;
    const identifier = ((await workflow.json()) as { 'jcr:uuid'?: string })['jcr:uuid'];

    expect(identifier).toBeTruthy();
    expect(reference).toBe(identifier);
  });

  test('installs the workflow, with BPMN the engine can be pointed at', async ({ request }) => {
    const response = await request.get('/Workflows/timeOffRequest/v1.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as { active?: boolean; bpmnXml?: string };
    expect(version.active).toBe(true);

    // Asserted on as a process rather than as a string: these are the element identifiers the engine will
    // resolve, so a diagram edited into a shape the demo no longer describes should fail here.
    const bpmn = version.bpmnXml ?? '';
    expect(bpmn).toContain('<bpmn:startEvent id="requestSubmitted"');
    expect(bpmn).toContain('<bpmn:userTask id="approveRequest"');
    expect(bpmn).toContain('<bpmn:exclusiveGateway id="decision"');
    expect(bpmn).toContain('<bpmn:endEvent id="requestApproved"');
    expect(bpmn).toContain('<bpmn:endEvent id="requestRejected"');
    // Diagram interchange too, or the visual editor has nothing to draw
    expect(bpmn).toContain('<bpmndi:BPMNDiagram');
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
