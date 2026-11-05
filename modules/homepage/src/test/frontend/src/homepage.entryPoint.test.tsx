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

// A stand-in for React's root, so that a test sees what the entry point mounts without rendering
// the whole shell
const { render: renderMock, createRootMock } = vi.hoisted(() => {
  const render = vi.fn();
  return { render, createRootMock: vi.fn(() => ({ render, unmount: vi.fn() })) };
});

vi.mock("react-dom/client", async importOriginal => ({
  ...(await importOriginal<typeof import("react-dom/client")>()),
  createRoot: createRootMock,
}));

// The first element of the given component in the tree handed to the root, which is never mounted
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

// The entry point mounts on import, so each test imports a fresh copy against the DOM it
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

  // Only a view inside the provider can offer a sign-in when the session has expired
  it("mounts the re-login provider around the router", async () => {
    document.body.innerHTML = '<div id="main-container"></div>';

    await act(async () => {
      await import("@iap/homepage/homepage");
    });

    // Imported after vi.resetModules(), so that they are the functions the entry point used. A copy
    // imported at the top of the file would be another function.
    const { ReLoginProvider } = await import("@iap/login/ReLoginDialog");
    const { RouterProvider } = await import("react-router");

    const provider = find(renderMock.mock.calls[0][0] as ReactNode, ReLoginProvider);
    expect(provider).toBeDefined();
    expect(find(provider, RouterProvider)).toBeDefined();
  });

  it("mounts nothing on a page without the container", async () => {
    await act(async () => {
      await import("@iap/homepage/homepage");
    });

    expect(createRootMock).not.toHaveBeenCalled();
  });
});
