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

vi.mock("@iap/frontend-commons/components/PageNotFound", () => ({
  default: ({ deletedAt, deletedBy, entryUrl }: { deletedAt?: string; deletedBy?: string; entryUrl?: string }) => (
    <div data-testid="page-not-found" data-at={deletedAt ?? ""} data-by={deletedBy ?? ""}
      data-entry={entryUrl ?? ""} />
  ),
}));

// The entry point renders on import, so each test re-imports a fresh copy of the module
// against the DOM it prepared.
describe("404 entry point", () => {
  beforeEach(() => {
    vi.resetModules();
    document.body.innerHTML = "";
  });

  // What the server found out about the requested path is on the container, not fetched, so the page is
  // right the first time it paints rather than after a round trip.
  it("hands the page what the server left on its container", async () => {
    document.body.innerHTML = '<div id="main-404-container" data-deleted-at="2026-08-20T14:00:00Z" '
      + 'data-deleted-by="alice" data-entry-url="/admin/archive/abc"></div>';

    await act(async () => {
      await import("@iap/frontend-commons/components/404");
    });

    const page = document.querySelector('[data-testid="page-not-found"]');
    expect(page).toHaveAttribute("data-at", "2026-08-20T14:00:00Z");
    expect(page).toHaveAttribute("data-by", "alice");
    expect(page).toHaveAttribute("data-entry", "/admin/archive/abc");
  });

  it("does nothing on a page without the container", async () => {
    await act(async () => {
      await import("@iap/frontend-commons/components/404");
    });

    expect(document.querySelector('[data-testid="page-not-found"]')).not.toBeInTheDocument();
  });
});
