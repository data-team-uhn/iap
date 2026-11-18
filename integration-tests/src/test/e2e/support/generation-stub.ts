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

import { createServer, type Server } from 'node:http';

import { onePagePdf } from './pdf';

/**
 * Where the stub listens. Maven reserves the port and points the demo instance's `generation.url` at it.
 * Absent when the suites run against instances started by hand, which reach a real worker or none.
 */
const PORT_VARIABLE = 'IAP_GENERATION_STUB_PORT';

/** What every generation answers with: the same one-page PDF, and a .docx nobody opens. */
const REPLY = JSON.stringify({
  pdf: onePagePdf('Filled in by the generation stub').toString('base64'),
  docx: Buffer.from('PK stub docx').toString('base64'),
});

/**
 * A stand-in for the generation worker, so that sending a demo request can fill its form in without
 * LibreOffice. It answers `POST /generate` the way the worker does, whatever the template, and counts the
 * requests on `GET /calls` for a test that needs to know one was made. The worker's own tests cover real
 * conversion.
 *
 * @returns the running server, or nothing when no port was given
 */
export async function startGenerationStub(): Promise<Server | undefined> {
  const port = Number(process.env[PORT_VARIABLE]);
  if (!port) {
    return undefined;
  }
  let calls = 0;
  const server = createServer((request, response) => {
    // Read the body to the end, as the real worker does, before answering
    request.resume();
    request.on('end', () => {
      if (request.method === 'POST' && request.url === '/generate') {
        calls += 1;
        response.writeHead(200, { 'Content-Type': 'application/json' }).end(REPLY);
      } else if (request.method === 'GET' && request.url === '/calls') {
        response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ calls }));
      } else {
        response.writeHead(404).end();
      }
    });
  });
  await new Promise<void>(resolve => server.listen(port, '127.0.0.1', resolve));
  return server;
}

/**
 * How many documents the stub has been asked for so far, across every test.
 *
 * @returns the count
 */
export async function generationCalls(): Promise<number> {
  const response = await fetch(`http://127.0.0.1:${process.env[PORT_VARIABLE]}/calls`);
  return ((await response.json()) as { calls: number }).calls;
}
