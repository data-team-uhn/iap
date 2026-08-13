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

import { isValidElement, type ReactElement, type ReactNode } from "react";

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

// Finds a component in the element tree handed to the root, by identity. Nothing is mounted for
// real here, so what the entry point decided is only readable off the elements it built.
const find = (node: ReactNode, component: unknown): ReactElement | undefined => {
  if (Array.isArray(node)) {
    return node.map(child => find(child as ReactNode, component)).find(Boolean);
  }
  if (!isValidElement(node)) {
    return undefined;
  }
  return node.type === component
    ? node
    : find((node.props as { children?: ReactNode }).children, component);
};

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

  // Every view talks to the repository, and some of them write to it, so the provider that signs a
  // lapsed session back in belongs above the router: enclosing the views is what lets a request
  // that ran into an expired session be re-sent instead of reported.
  it("mounts the re-login provider around the router", async () => {
    document.body.innerHTML = '<div id="main-container"></div>';

    await act(async () => {
      await import("@iap/homepage/homepage");
    });

    // Both taken from the registry the entry point was just imported into: vi.resetModules() means a
    // copy imported at the top of this file would be a different function, and these are matched by
    // identity
    const { ReLoginProvider } = await import("@iap/login/ReLoginDialog");
    const { RouterProvider } = await import("react-router");

    const provider = find(renderMock.mock.calls[0][0] as ReactNode, ReLoginProvider);
    expect(provider).toBeDefined();
    // Around the router, not beside it: a provider the views are not inside cannot offer them a
    // sign-in
    expect(find(provider, RouterProvider)).toBeDefined();
  });

  it("mounts nothing on a page without the container", async () => {
    await act(async () => {
      await import("@iap/homepage/homepage");
    });

    expect(createRootMock).not.toHaveBeenCalled();
  });
});
