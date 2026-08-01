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

import { act } from "@testing-library/react";

vi.mock("@iap/frontend-commons/routes", () => ({
  getRoutes: vi.fn(() => Promise.resolve([])),
}));

// Only that the entry point mounts the router into the page container is of interest here; what
// the router then renders is homepage.test.tsx's business, and mounting it for real would drag the
// whole shell in. Standing in for the root keeps this to the mounting decision itself.
const { render: renderMock, createRootMock } = vi.hoisted(() => {
  const render = vi.fn();
  return { render, createRootMock: vi.fn(() => ({ render, unmount: vi.fn() })) };
});

vi.mock("react-dom/client", async importOriginal => ({
  ...(await importOriginal<typeof import("react-dom/client")>()),
  createRoot: createRootMock,
}));

// The entry point mounts on import, so each test re-imports a fresh copy against the DOM it
// prepared.
describe("homepage entry point", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    document.body.innerHTML = "";
  });

  it("mounts the router into the page container", async () => {
    document.body.innerHTML = '<div id="main-container"></div>';

    await act(async () => {
      await import("@iap/homepage/homepage");
    });

    expect(createRootMock).toHaveBeenCalledWith(document.querySelector("#main-container"));
    expect(renderMock).toHaveBeenCalled();
  });

  it("mounts nothing on a page without the container", async () => {
    await act(async () => {
      await import("@iap/homepage/homepage");
    });

    expect(createRootMock).not.toHaveBeenCalled();
  });
});
