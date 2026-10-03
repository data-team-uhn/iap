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

import { MenuList } from "@mui/material";
import { fireEvent, render, screen } from "@testing-library/react";

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
  Reflect.deleteProperty(window, STORE_KEY);
});

const renderInMenu = (onChoose?: () => void) =>
  render(<MenuList><PersonaSwitcher onChoose={onChoose} /></MenuList>);

describe("PersonaSwitcher", () => {
  // The check mark marking the active persona is decorative, so aria-checked is the only thing that
  // tells a screen reader which hat is on.
  it("lists the personas the user may act as, checking the active one", () => {
    renderInMenu();

    expect(screen.getByText("Acting as")).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: "Submitter", checked: true })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: "Reviewer", checked: false })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: "Administrator", checked: false })).toBeInTheDocument();
  });

  it("puts on the chosen hat, and says so", () => {
    const onChoose = vi.fn();
    renderInMenu(onChoose);

    fireEvent.click(screen.getByRole("menuitemradio", { name: "Reviewer" }));

    expect(getActivePersona()).toBe("reviewer");
    expect(onChoose).toHaveBeenCalledOnce();
    expect(screen.getByRole("menuitemradio", { name: "Reviewer", checked: true })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: "Submitter", checked: false })).toBeInTheDocument();
  });

  it("renders nothing when there is only one persona to act as", () => {
    vi.mocked(availablePersonas).mockReturnValueOnce([ "submitter" ]);

    renderInMenu();

    expect(screen.getByRole("menu")).toBeEmptyDOMElement();
  });
});
