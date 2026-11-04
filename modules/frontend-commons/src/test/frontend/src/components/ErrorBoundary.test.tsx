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

import { render, screen } from "@testing-library/react";

import ErrorBoundary from "@iap/frontend-commons/components/ErrorBoundary";

const FAILURE = new Error("this component is broken");

function Broken(): never {
  throw FAILURE;
}

describe("ErrorBoundary", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows its children while they render", () => {
    render(<ErrorBoundary fallback="Cannot be shown"><p>Working</p></ErrorBoundary>);

    expect(screen.getByText("Working")).toBeInTheDocument();
    expect(screen.queryByText("Cannot be shown")).toBeNull();
  });

  it("puts the fallback in place of children that throw, and leaves the rest of the page", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);

    render(
      <>
        <ErrorBoundary fallback="Cannot be shown"><Broken /></ErrorBoundary>
        <p>The rest of the page</p>
      </>
    );

    expect(screen.getByText("Cannot be shown")).toBeInTheDocument();
    expect(screen.getByText("The rest of the page")).toBeInTheDocument();
  });

  it("has the error logged", () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);

    render(<ErrorBoundary fallback="Cannot be shown"><Broken /></ErrorBoundary>);

    expect(logged.mock.calls.flat()).toContain(FAILURE);
  });
});
