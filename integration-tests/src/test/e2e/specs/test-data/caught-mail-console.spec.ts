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

import { ADMIN, adminAuth, signInAs } from '../../support/auth';

/**
 * Reading the caught mail through the administration console, on an instance that is catching.
 *
 * What this proves and the unit tests cannot: that the module's `ext:Extension` nodes really load —
 * initial content naming a node type another bundle declares is a runtime question, not a compile
 * one — that `/admin/mail` and `/admin/mail/<name>` both resolve to a page, and that
 * `.paginate.json` lists `mail:CaughtMessage` children, which rests on one `childNodeType` line in
 * the CND and on nothing else.
 *
 * The mailbox is one shared thing and this suite runs fully parallel, so nothing here reads a row by
 * position or compares totals. A test that needs *its own* message addresses it uniquely and looks
 * the node up through `.messages.json`; a test that only needs *a* message can use any of them,
 * since everything in this mailbox came from the same test endpoint.
 */
test.describe('the caught mail console', () => {
  /** Sends the fixed test message to an address only the calling test uses. */
  const sendTo = async (request: APIRequestContext, address: string) => {
    const response = await request.get('/content.emailtest.html'
      + `?fromEmail=platform@example.com&fromName=The%20Platform&toEmail=${address}&toName=Recipient`
      + '&isHtml=true', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
  };

  /**
   * The node name of the message addressed to `address`, read back through the module's own
   * listing. Addressing the message directly is what keeps these tests out of the way of the others
   * filling the same mailbox in parallel — the grid's own search cannot do it, because the quick
   * filter is a full-text query and an email address is not what full text matches on.
   */
  const nameOf = async (request: APIRequestContext, address: string): Promise<string> => {
    const response = await request.get('/CaughtMail.messages.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
    const { messages } = (await response.json()) as { messages: { path: string; to?: string[] }[] };
    const mine = messages.filter(message => (message.to ?? []).some(to => to.includes(address)));
    expect(mine).toHaveLength(1);
    return mine[0].path.replace(/^.*\//, '');
  };

  test('reports that mail is being caught', async ({ request }) => {
    const response = await request.get('/CaughtMail.status.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const status = (await response.json()) as { enabled: boolean; total: number };
    // This aggregate ships dev/email-catcher-enabled.json, so the switch is on. The count is only
    // ever a lower bound here, because the rest of the suite is filling the same mailbox.
    expect(status.enabled).toBe(true);
    expect(status.total).toBeGreaterThanOrEqual(0);
  });

  test('offers caught mail as one of the console tools', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');

    await expect(page.getByRole('heading', { name: 'Caught mail' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Read caught mail' })).toBeVisible();
    // The summary really loaded, rather than the frame rendering around nothing
    await expect(page.getByText('Caught so far')).toBeVisible();
    // And it is not claiming the opposite of what this instance is doing
    await expect(page.getByText(/Mail is being delivered normally/)).toHaveCount(0);
  });

  test('lists what has been caught, and opens a message from the list', async ({ page, request }) => {
    // At least one message exists from here on, whatever the other tests are doing
    await sendTo(request, 'listed@example.com');

    await signInAs(page, ADMIN);
    await page.goto('/admin');
    await page.getByRole('link', { name: 'Read caught mail' }).click();
    await expect(page).toHaveURL(/\/admin\/mail$/);

    // The listing is the shared entity grid over `.paginate.json`, which only lists anything at all
    // because mail:CaughtMailHomepage declares childNodeType
    await expect(page.getByRole('columnheader', { name: 'Subject' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Caught' })).toBeVisible();
    const anyMessage = page.getByRole('row').filter({ hasText: 'IAP test message' }).first();
    await expect(anyMessage).toBeVisible();

    // Any row will do: every message in this mailbox came from the same test endpoint, so none of
    // the assertions below depend on which one the click lands on
    await anyMessage.click();

    await expect(page).toHaveURL(/\/admin\/mail\/[^/]+$/);
    await expect(page.getByRole('heading', { name: 'IAP test message' })).toBeVisible();
    await expect(page.getByText('The Platform <platform@example.com>')).toBeVisible();
  });

  test('shows a message whole, at its own address', async ({ page, request }) => {
    const address = 'console@example.com';
    await sendTo(request, address);
    const name = await nameOf(request, address);

    await signInAs(page, ADMIN);
    await page.goto(`/admin/mail/${name}`);

    await expect(page.getByRole('heading', { name: 'IAP test message' })).toBeVisible();
    await expect(page.getByText(`Recipient <${address}>`)).toBeVisible();
    await expect(page.getByText('The Platform <platform@example.com>')).toBeVisible();
    await expect(page.getByText('Headers')).toBeVisible();
  });

  test('draws the HTML body in a sandbox, with the source beside it', async ({ page, request }) => {
    const address = 'rendered@example.com';
    await sendTo(request, address);
    const name = await nameOf(request, address);

    await signInAs(page, ADMIN);
    await page.goto(`/admin/mail/${name}`);

    // An empty sandbox attribute is every restriction at once, which is what makes rendering
    // whatever was handed to the mail service safe inside an administrator's session
    const frame = page.getByTitle('The message as a recipient would see it');
    await expect(frame).toBeVisible();
    await expect(frame).toHaveAttribute('sandbox', '');
    await expect(frame.contentFrame().getByText(
      'Here is a test message from the Institutional Authorization Platform.')).toBeVisible();

    await page.getByRole('tab', { name: 'HTML source' }).click();
    await expect(page.getByText('<title>Rich Text</title>', { exact: false })).toBeVisible();
  });
});
