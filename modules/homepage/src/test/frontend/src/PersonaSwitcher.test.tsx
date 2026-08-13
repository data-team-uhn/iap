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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import PersonaSwitcher from "@iap/homepage/PersonaSwitcher";
import { STORE_KEY, availablePersonas, getActivePersona } from "@iap/ui-extension/personas";

// Only availablePersonas is stubbable, so a test can present a user with nothing to switch between;
// everything else is the real store.
vi.mock("@iap/ui-extension/personas", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@iap/ui-extension/personas")>();
  return { ...actual, availablePersonas: vi.fn(actual.availablePersonas) };
});

// The active persona is held on `window`; reset it so tests don't inherit each other's choice.
afterEach(() => {
  delete (window as unknown as Record<string, unknown>)[STORE_KEY];
});

describe("PersonaSwitcher", () => {
  it("shows the persona the user is currently acting as", async () => {
    render(<PersonaSwitcher />);

    expect(await screen.findByRole("button", { name: /Acting as Submitter/ })).toBeInTheDocument();
  });

  it("lists the personas the user may choose between", async () => {
    render(<PersonaSwitcher />);

    fireEvent.click(await screen.findByRole("button", { name: /Acting as Submitter/ }));

    expect(await screen.findByRole("menuitemradio", { name: "Submitter" })).toBeInTheDocument();
    expect(await screen.findByRole("menuitemradio", { name: "Reviewer" })).toBeInTheDocument();
    expect(await screen.findByRole("menuitemradio", { name: "Administrator" })).toBeInTheDocument();
  });

  // The check mark marking the active persona is decorative, so these attributes are the only thing
  // that tells a screen reader the menu is open and which hat is on.
  it("says which persona is checked, and whether the menu is open", async () => {
    render(<PersonaSwitcher />);

    const trigger = await screen.findByRole("button", { name: /Acting as Submitter/ });
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(trigger);

    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(await screen.findByRole("menuitemradio", { name: "Submitter", checked: true })).toBeInTheDocument();
    expect(await screen.findByRole("menuitemradio", { name: "Reviewer", checked: false })).toBeInTheDocument();
  });

  it("puts on the chosen hat, and says so", async () => {
    render(<PersonaSwitcher />);

    fireEvent.click(await screen.findByRole("button", { name: /Acting as Submitter/ }));
    fireEvent.click(await screen.findByRole("menuitemradio", { name: "Reviewer" }));

    expect(await screen.findByRole("button", { name: /Acting as Reviewer/ })).toBeInTheDocument();
    expect(getActivePersona()).toBe("reviewer");
  });

  it("closes the menu once a persona is chosen", async () => {
    render(<PersonaSwitcher />);

    fireEvent.click(await screen.findByRole("button", { name: /Acting as Submitter/ }));
    fireEvent.click(await screen.findByRole("menuitemradio", { name: "Administrator" }));

    expect(screen.queryByRole("menuitemradio", { name: "Submitter" })).not.toBeInTheDocument();
  });

  it("closes the menu when dismissed without choosing, leaving the persona alone", async () => {
    render(<PersonaSwitcher />);
    fireEvent.click(await screen.findByRole("button", { name: /Acting as Submitter/ }));

    fireEvent.keyDown(await screen.findByRole("menu"), { key: "Escape", code: "Escape" });

    await waitFor(() => expect(screen.queryByRole("menuitemradio", { name: "Reviewer" })).not.toBeInTheDocument());
    expect(getActivePersona()).toBe("submitter");
  });

  it("renders nothing when there is only one persona to act as", () => {
    vi.mocked(availablePersonas).mockReturnValueOnce([ "submitter" ]);

    const { container } = render(<PersonaSwitcher />);

    expect(container).toBeEmptyDOMElement();
  });
});
