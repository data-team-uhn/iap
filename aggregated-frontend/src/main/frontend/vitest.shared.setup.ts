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

// Gives each test file that shares a process a clean start. Vitest does not apply a test file's vi.mock to a module
// a setup file imported, so the files that mock modules run without this one.
import { cleanup } from "@testing-library/react";

// Testing Library registers its automatic cleanup only for the first file a process loads
afterEach(() => {
  cleanup();
});

// A module-level cache, a stubbed global or environment variable, a spy, fake timers or anything left in the document
// or storage outlives the file that set it up. A spy on a global still holding another file's mock picks up that
// mock's calls, and fake timers left running stall the next file's waits. Resetting the modules makes the next file
// evaluate the sources again; the packages Node loads itself keep their single instance.
afterAll(() => {
  vi.resetModules();
  document.head.querySelectorAll("meta").forEach(meta => meta.remove());
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.useRealTimers();
  vi.restoreAllMocks();
});
