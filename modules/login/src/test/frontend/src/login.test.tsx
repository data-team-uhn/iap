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

vi.mock("@iap/login/LoginPage", () => ({
  default: () => <div data-testid="login-page" />,
}));

// The entry point renders when it is imported, so each test imports a fresh copy of it into
// the page it prepared.
describe("login entry point", () => {
  beforeEach(() => {
    vi.resetModules();
    document.body.innerHTML = "";
  });

  // Each test imports the module itself, and transforms its whole module graph. With coverage
  // on a busy machine, that can take longer than the default 5 s.
  it("renders the login page into its container", { timeout: 15_000 }, async () => {
    document.body.innerHTML = '<div id="main-login-container"></div>';

    await act(async () => {
      await import("@iap/login/login");
    });

    expect(document.querySelector('[data-testid="login-page"]')).toBeInTheDocument();
  });

  it("does nothing on a page without the login container", { timeout: 15_000 }, async () => {
    await act(async () => {
      await import("@iap/login/login");
    });

    expect(document.querySelector('[data-testid="login-page"]')).not.toBeInTheDocument();
  });
});
