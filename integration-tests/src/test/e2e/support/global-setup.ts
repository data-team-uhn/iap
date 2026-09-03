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

import { activeInstances, type ActiveInstance } from './instances';

/**
 * How long to wait for an instance to become ready. Generous, because a first boot has to install every
 * bundle and run repoinit; a warm one is far quicker.
 */
const READY_TIMEOUT_MS = 300_000;

const POLL_INTERVAL_MS = 1_000;

/** The credentials the launched instances start with. */
const AUTHORIZATION = `Basic ${Buffer.from('admin:admin').toString('base64')}`;

/** Health check outcomes that mean the instance is not usable yet. */
const BLOCKING_STATUSES = ['CRITICAL', 'HEALTH_CHECK_ERROR', 'TEMPORARILY_UNAVAILABLE'];

/**
 * The tag the startup gate itself watches. Every check carrying it has to be OK — a WARN included —
 * before the gate stops serving the startup page, so this is the one question whose answer decides
 * whether the instance serves anything at all.
 */
const GATE_TAG = 'systemalive';

/**
 * How long the gate's checks must have been passing before an instance counts as ready.
 *
 * The gate is deliberately not a one-way latch: it opens as soon as its checks have held briefly, and only
 * *retires* - stops being able to refuse anything - once they have held for its settle period. In between,
 * a single blip closes it again and answers whatever is in flight with the "starting up" page, which a test
 * sees as a bare 503 from a request that has nothing wrong with it.
 *
 * So the question worth asking is not "do the gate's checks pass" but "have they passed for longer than the
 * gate needs", which is the same rule the gate applies to itself. Comfortably above the gate's own 30s
 * settle period, since the two are polling independently.
 */
const GATE_SETTLED_MS = 40_000;

/**
 * Whether an instance is ready to be tested.
 *
 * The launcher plugin only waits for the OSGi framework to come up, which happens well before the
 * repository is initialized and the application is servable. So this asks for two things: that the
 * platform's own health checks report nothing broken, and that the sign-in page actually renders. Neither
 * alone is enough — the health endpoint answers before the frontend is wired up, and a page can be served
 * while the repository behind it is still being initialized.
 *
 * A WARN is deliberately not blocking. It means something is worth looking at, not that the instance is
 * unusable, and treating it as fatal here would turn a passing suite into a startup timeout.
 *
 * Except for the checks the startup gate watches, where a WARN means exactly "not yet". The gate holds
 * until every `systemalive` check is OK and answers 503 to everything meanwhile, so an instance judged
 * ready on the looser rule serves its pages while every asset those pages import fails: the dashboard
 * renders without its widgets, and the test that waits for one of them times out — with `Bundle Content
 * Loaded is WARN` in the gate's log at the moment the browser was refused the shared chunks. Asking the
 * gate's own question is what keeps the two from disagreeing.
 */
const isReady = async (instance: ActiveInstance): Promise<boolean> => {
  try {
    const health = await fetch(`${instance.baseURL}/system/health.json?tags=iap`, {
      headers: { Authorization: AUTHORIZATION },
    });
    if (!health.ok) {
      return false;
    }
    const body = (await health.json()) as { results?: { status?: string }[] };
    if ((body.results ?? []).some(result => BLOCKING_STATUSES.includes(result.status ?? ''))) {
      return false;
    }
    const gate = await fetch(`${instance.baseURL}/system/health.json?tags=${GATE_TAG}`, {
      headers: { Authorization: AUTHORIZATION },
    });
    if (!gate.ok) {
      return false;
    }
    const gateBody = (await gate.json()) as { results?: { status?: string }[] };
    if ((gateBody.results ?? []).some(result => result.status !== 'OK')) {
      return false;
    }
    const loginPage = await fetch(`${instance.baseURL}/login`);
    if (!loginPage.ok) {
      return false;
    }
    // Whatever content this suite's pages depend on. Anonymously, exactly as the browser will read it —
    // see Instance.readyPaths for why checking as admin here would defeat the purpose.
    const content = await Promise.all(
      (instance.readyPaths ?? []).map(path => fetch(`${instance.baseURL}${path}`)),
    );
    return content.every(response => response.ok);
  } catch {
    // Connection refused while the HTTP service is still coming up
    return false;
  }
};

const waitFor = async (instance: ActiveInstance): Promise<void> => {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  let lastError = 'no response';
  // When the current unbroken stretch of readiness began, forgotten the moment anything stops passing
  let readySince: number | undefined;
  while (Date.now() < deadline) {
    if (await isReady(instance)) {
      readySince ??= Date.now();
      if (Date.now() - readySince >= GATE_SETTLED_MS) {
        return;
      }
      lastError = 'the startup gate had not settled';
    } else {
      readySince = undefined;
      lastError = 'health checks not passing';
    }
    await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
  }
  throw new Error(
    `${instance.description} at ${instance.baseURL} was not ready within `
      + `${READY_TIMEOUT_MS / 1000}s (${lastError}).`,
  );
};

export default async function globalSetup(): Promise<void> {
  const instances = activeInstances();
  if (instances.length === 0) {
    throw new Error(
      'No instances to test. Maven passes their URLs in; to run by hand, set e.g. IAP_TESTDATA_URL.',
    );
  }
  // Concurrently, since the instances boot independently and waiting for them in turn would add up
  await Promise.all(
    instances.map(async instance => {
      await waitFor(instance);
      console.log(`ready: ${instance.description} at ${instance.baseURL}`);
    }),
  );
}
