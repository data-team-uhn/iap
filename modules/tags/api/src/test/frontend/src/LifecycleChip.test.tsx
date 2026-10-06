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

import { act, render, screen } from "@testing-library/react";

import LifecycleChip from "@iap/tags/LifecycleChip";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { tagAwareFetch } from "./tagDefinitions.fixture";

describe("LifecycleChip", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("shows where a node stands in its lifecycle, and none of its other tags", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    render(<LifecycleChip tags={["in-progress", "draft"]} />);

    expect(await screen.findByText("Draft")).toBeInTheDocument();
    expect(screen.queryByText("In progress")).not.toBeInTheDocument();
  });

  it("shows nothing for a node outside any lifecycle", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    const { container } = render(<LifecycleChip tags={["in-progress"]} />);

    await act(() => Promise.resolve());
    expect(container).toBeEmptyDOMElement();
  });
});
