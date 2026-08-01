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

// A page the server rendered without status metadata. This is a file of its own because the entry
// point renders once, on import, and only one such render survives per file -- see the note in
// GenericErrorPage.test.tsx.
describe("generic error page entry point, without status metadata", () => {
  it("renders with no status at all", async () => {
    document.head.querySelectorAll("meta").forEach(meta => meta.remove());
    document.body.innerHTML = '<div id="main-error-container"></div>';

    await act(async () => {
      await import("@iap/frontend-commons/components/GenericErrorPage");
    });

    const page = document.querySelector('[data-testid="error-page"]');
    expect(page).toHaveAttribute("data-code", "");
    expect(page).toHaveAttribute("data-title", "");
  });
});
