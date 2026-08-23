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
 */
test.describe('the email catcher', () => {
  const caught = async (request: APIRequestContext) => {
    const response = await request.get('/CaughtMail.messages.json', { headers: asAdmin });
    expect(response.ok()).toBeTruthy();
    return (await response.json()) as { total: number; messages: CaughtMessage[] };
  };

  test('files a message that would have been sent, instead of sending it', async ({ request }) => {
    const before = (await caught(request)).total;

    const sent = await request.get('/content.emailtest.html'
      + '?fromEmail=platform@example.com&fromName=The%20Platform'
      + '&toEmail=someone@example.com&toName=Someone', { headers: asAdmin });
    expect(sent.ok()).toBeTruthy();

    // The far end: the endpoint says it prepared a message, and this is whether one exists
    const after = await caught(request);
    expect(after.total).toBe(before + 1);

    const message = after.messages[0];
    expect(message.subject).toBe('IAP test message');
    expect(message.from).toEqual([ 'The Platform <platform@example.com>' ]);
    expect(message.to).toEqual([ 'Someone <someone@example.com>' ]);
    expect(message.textBody).toContain('Institutional Authorization Platform');
    expect(message.caughtAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  test('keeps the HTML body of a rich message', async ({ request }) => {
    await request.get('/content.emailtest.html'
      + '?fromEmail=platform@example.com&fromName=The%20Platform'
      + '&toEmail=rich@example.com&toName=Rich&isHtml=true', { headers: asAdmin });

    const message = (await caught(request)).messages[0];
    expect(message.to).toEqual([ 'Rich <rich@example.com>' ]);
    expect(message.htmlBody).toContain('<p>');
    // The plain text part is still there, for the clients that cannot show the rich one
    expect(message.textBody).toContain('Institutional Authorization Platform');
  });

  test('answers newest first', async ({ request }) => {
    await request.get('/content.emailtest.html'
      + '?fromEmail=a@example.com&fromName=A&toEmail=first@example.com&toName=First', { headers: asAdmin });
    await request.get('/content.emailtest.html'
      + '?fromEmail=a@example.com&fromName=A&toEmail=second@example.com&toName=Second', { headers: asAdmin });

    const messages = (await caught(request)).messages;
    expect(messages[0].to).toEqual([ 'Second <second@example.com>' ]);
    expect(messages[1].to).toEqual([ 'First <first@example.com>' ]);
  });
});
