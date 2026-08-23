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

const asAdmin = { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` };

interface CaughtMessage {
  subject?: string;
  from?: string[];
  to?: string[];
  cc?: string[];
  textBody?: string;
  htmlBody?: string;
  caughtAt?: string;
}

/**
 * The email catcher, proved at the far end.
 *
 * A test that only checked the send endpoint's status would prove nothing: it answers 200 for a message that was
 * handed over, and whether anything came of that is exactly the question. So every assertion here is about what
 * came back out of `/CaughtMail`, not about what went in.
 *
 * The mailbox is one shared thing and this suite runs fully parallel, so nothing here reads by position or
 * counts totals — each test addresses its own message and finds that one. Anything else is a race against the
 * other tests in this file, which is a flake rather than a finding.
 */
test.describe('the email catcher', () => {
  const messages = async (request: APIRequestContext): Promise<CaughtMessage[]> => {
    const response = await request.get('/CaughtMail.messages.json', { headers: asAdmin });
    expect(response.ok()).toBeTruthy();
    return ((await response.json()) as { messages: CaughtMessage[] }).messages;
  };

  /** Sends the fixed test message to an address only this test uses. */
  const sendTo = async (request: APIRequestContext, address: string, isHtml = false) => {
    const response = await request.get('/content.emailtest.html'
      + `?fromEmail=platform@example.com&fromName=The%20Platform&toEmail=${address}&toName=Recipient`
      + (isHtml ? '&isHtml=true' : ''), { headers: asAdmin });
    expect(response.ok()).toBeTruthy();
  };

  const addressedTo = (caught: CaughtMessage[], address: string) =>
    caught.filter(message => (message.to ?? []).some(to => to.includes(address)));

  test('files a message that would have been sent, instead of sending it', async ({ request }) => {
    await sendTo(request, 'plain@example.com');

    // The far end: the endpoint said it prepared a message, and this is whether one exists
    const mine = addressedTo(await messages(request), 'plain@example.com');
    expect(mine).toHaveLength(1);
    expect(mine[0].subject).toBe('IAP test message');
    expect(mine[0].from).toEqual([ 'The Platform <platform@example.com>' ]);
    expect(mine[0].to).toEqual([ 'Recipient <plain@example.com>' ]);
    expect(mine[0].textBody).toContain('Institutional Authorization Platform');
    expect(mine[0].caughtAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    // Nobody was copied, and the form says so rather than leaving a reader to guess
    expect(mine[0].cc).toEqual([]);
  });

  test('keeps the HTML body of a rich message', async ({ request }) => {
    await sendTo(request, 'rich@example.com', true);

    const mine = addressedTo(await messages(request), 'rich@example.com');
    expect(mine).toHaveLength(1);
    expect(mine[0].htmlBody).toContain('<p>');
    // The plain text part is still there, for the clients that cannot show the rich one
    expect(mine[0].textBody).toContain('Institutional Authorization Platform');
  });

  test('answers newest first', async ({ request }) => {
    await sendTo(request, 'earlier@example.com');
    await sendTo(request, 'later@example.com');

    // Relative to each other, not by index: other tests are filing into the same mailbox at the same time
    const caught = await messages(request);
    const earlier = caught.findIndex(message => (message.to ?? []).some(to => to.includes('earlier@')));
    const later = caught.findIndex(message => (message.to ?? []).some(to => to.includes('later@')));

    expect(later).toBeGreaterThanOrEqual(0);
    expect(later).toBeLessThan(earlier);
  });
});
