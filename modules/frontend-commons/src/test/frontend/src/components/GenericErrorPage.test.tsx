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

vi.mock("@iap/frontend-commons/components/ErrorPage", () => ({
  default: ({ errorCode, title }: { errorCode?: string; title?: string }) => (
    <div data-testid="error-page" data-code={errorCode ?? ""} data-title={title ?? ""} />
  ),
}));

// The entry point renders on import, so each test re-imports a fresh copy of the module against the
// DOM it prepared. Only one test per file can actually render: a second one reuses this file's
// React while the re-imported entry point brings its own, and the two then fight over the DOM. The
// metadata-less case therefore lives in GenericErrorPage.withoutStatus.test.tsx.
describe("generic error page entry point", () => {
  beforeEach(() => {
    vi.resetModules();
    document.body.innerHTML = "";
  });

  it("renders the error reported by the server", async () => {
    document.body.innerHTML =
      '<div id="main-error-container" data-status-code="500" data-status-message="Internal Server Error"></div>';

    await act(async () => {
      await import("@iap/frontend-commons/components/GenericErrorPage");
    });

    const page = document.querySelector('[data-testid="error-page"]');
    expect(page).toHaveAttribute("data-code", "500");
    expect(page).toHaveAttribute("data-title", "Internal Server Error");
  });

  it("does nothing on a page without the container", async () => {
    await act(async () => {
      await import("@iap/frontend-commons/components/GenericErrorPage");
    });

    expect(document.querySelector('[data-testid="error-page"]')).not.toBeInTheDocument();
  });
});
