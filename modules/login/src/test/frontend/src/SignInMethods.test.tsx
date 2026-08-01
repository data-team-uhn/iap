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

import { fireEvent, render, screen } from "@testing-library/react";

import SignInMethods from "@iap/login/SignInMethods";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

const method = (name: string, extra: object = {}) => ({
  "iap:extensionName": name,
  "iap:extensionRender": () => <div>{name} content</div>,
  ...extra,
});

describe("SignInMethods", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the registered method", async () => {
    mockedLoadExtensions.mockResolvedValue([ method("External") ]);
    render(<SignInMethods />);

    expect(await screen.findByText("External content")).toBeInTheDocument();
  });

  it("collapses further methods behind their labels, revealing them on demand", async () => {
    mockedLoadExtensions.mockResolvedValue([
      method("External"),
      method("Local", { "iap:collapsedLabel": "Use a local account instead" }),
    ]);
    render(<SignInMethods />);

    expect(await screen.findByText("External content")).toBeInTheDocument();
    expect(screen.queryByText("Local content")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Use a local account instead" }));
    expect(screen.getByText("Local content")).toBeInTheDocument();
    // The revealed method is its own titled section, so it is not read as part of the
    // primary method above it
    expect(screen.getByRole("heading", { name: "Local" })).toBeInTheDocument();
  });

  it("falls back to the credentials form when no method is registered", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    mockedLoadExtensions.mockResolvedValue([]);
    render(<SignInMethods />);

    expect(await screen.findByLabelText(/Username/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });

  it("labels a collapsed method by its name, then by a generic label, when it declares neither", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { "iap:extensionName": "Primary", "iap:extensionRender": () => <div>Primary form</div> },
      { "iap:extensionName": "Named", "iap:extensionRender": () => <div>Named form</div> },
      { "iap:extensionRender": () => <div>Anonymous form</div> },
    ]);

    render(<SignInMethods />);

    // No iap:collapsedLabel anywhere: the second falls back to its name, the third to the default
    expect(await screen.findByRole("button", { name: "Named" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "More sign-in options" })).toBeInTheDocument();
  });

  it("falls back to the credentials form when the methods cannot be loaded", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const failure = new Error("network error");
    mockedLoadExtensions.mockRejectedValue(failure);

    render(<SignInMethods />);

    expect(await screen.findByLabelText(/Username/)).toBeInTheDocument();
    expect(errorSpy).toHaveBeenCalledWith("Something went wrong loading the sign-in methods", failure);
    errorSpy.mockRestore();
  });
});
