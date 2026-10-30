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

import { WorkflowConsolePage } from '../../../pages/workflowConsole.page';
import { ADMIN, adminAuth, signInAs } from '../../../support/auth';

/**
 * THE STORY: an author edits a step whose configuration is a list of records.
 *
 * The `updateContent` step's `fields` parameter is a structure: each record is a field keyed by its property,
 * holding choices keyed by their value and an optional condition. The workflow is only edited, never run. A
 * record is edited in the properties panel, and the subtree the parser stores from it is checked on a live
 * instance.
 */
test.describe('stories: a step configured by a list of records', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  const TITLE = 'Field declarations';

  const TASK = 'update';

  const DIAGRAM = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:iap="https://iap.uhndata.io/bpmn"
                  id="Definitions_fields" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_fields" isExecutable="true">
    <bpmn:serviceTask id="${TASK}" name="Update the content">
      <bpmn:extensionElements>
        <iap:updateContent>
          <iap:fields name="workflow" label="Workflow">
            <iap:choices value="a" label="A" />
            <iap:appliesWhen property="dataType"><iap:values>long</iap:values></iap:appliesWhen>
          </iap:fields>
        </iap:updateContent>
      </bpmn:extensionElements>
    </bpmn:serviceTask>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Diagram_fields">
    <bpmndi:BPMNPlane id="Plane_fields" bpmnElement="Process_fields">
      <bpmndi:BPMNShape id="${TASK}_di" bpmnElement="${TASK}">
        <dc:Bounds x="200" y="100" width="100" height="80" />
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
`;

  let version = '';

  test.afterAll(async ({ playwright, baseURL }) => {
    // Leave the instance as it was found, permanently, so that no copy waits in the archive for the next run
    if (version !== '') {
      const request = await playwright.request.newContext({ baseURL });
      await request.delete(`${version.slice(0, version.lastIndexOf('/'))}?permanent=true`, { headers: adminAuth });
      await request.dispose();
    }
  });

  test('an author drafts a workflow whose step declares a field', async ({ page, request }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openFromConsole();
    await workflows.create(TITLE, 'A step configured by records');
    version = await workflows.edit('1.0');

    // The diagram the editor would save, given straight to the draft
    const saved = await request.post(`${version}.save.json`, {
      headers: adminAuth,
      multipart: {
        'bpmn.xml': { name: 'bpmn.xml', mimeType: 'application/xml', buffer: Buffer.from(DIAGRAM, 'utf-8') },
      },
    });
    expect(saved.ok(), 'the diagram was not saved').toBeTruthy();
    expect((await fields(request, version)).workflow?.label).toBe('Workflow');
  });

  test('she relabels a field and adds a second field in the panel', async ({ page, request }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openEditor(version);
    await page.locator(`.djs-shape[data-element-id="${TASK}"]`).click();

    const workflow = page.getByRole('group', { name: 'workflow', exact: true });
    const label = workflow.getByRole('textbox', { name: 'Label', exact: true }).first();
    await label.fill('Workflow version');
    await label.press('Enter');

    await page.getByRole('button', { name: 'Add Field', exact: true }).click();
    const added = page.getByRole('group', { name: 'New Field', exact: true });
    await expect(added).toBeVisible();
    // The record's title follows the property as it is typed, so the group cannot find its own field again:
    // Enter goes to the field the typing left focused. The first, since its condition has a property too.
    await added.getByRole('textbox', { name: 'Property', exact: true }).first().fill('status');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('group', { name: 'status', exact: true })).toBeVisible();
    await workflows.save();

    const stored = await fields(request, version);
    expect(Object.keys(stored).filter(key => !key.startsWith('jcr:'))).toEqual([ 'workflow', 'status' ]);
    expect(stored.workflow?.label).toBe('Workflow version');
    // The key names the node, and is not stored again as a property
    expect(stored.workflow?.name).toBeUndefined();
    expect(stored.workflow?.choices?.a?.label).toBe('A');
    expect(stored.workflow?.appliesWhen?.values).toEqual([ 'long' ]);
    expect(stored.status).toMatchObject({ 'jcr:primaryType': 'nt:unstructured' });
    expect(stored.status?.name).toBeUndefined();
  });
});

interface StoredField {
  'jcr:primaryType'?: string;
  name?: string;
  label?: string;
  choices?: Partial<Record<string, { label?: string }>>;
  appliesWhen?: { values?: string[] };
}

/** The `fields` subtree the parser stored on the step, as the repository serves it. */
async function fields(
  request: APIRequestContext, version: string,
): Promise<Record<string, StoredField | undefined>> {
  const response = await request.get(`${version}/update/fields.infinity.json`, { headers: adminAuth });
  expect(response.ok(), 'the step stored no fields').toBeTruthy();
  return (await response.json()) as Record<string, StoredField | undefined>;
}
