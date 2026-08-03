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

import TagChip from "@iap/tags/TagChip";
import { clearTagDefinitionsCache, tagValueOptions } from "@iap/tags/tagDefinitions";

import { tagAwareFetch } from "./tagDefinitions.fixture";

describe("TagChip", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("labels, colors and decorates the tag from its definition", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    render(<TagChip tags={["in-review"]} category="lifecycle" />);

    // The soft styling is derived color-mix()/light-dark() CSS that jsdom cannot compute
    // (chipStyle' own tests pin it down exactly); what the DOM can vouch for is the label
    // and the declared icon
    expect(await screen.findByText("In review")).toBeInTheDocument();
    expect(screen.getByTestId("VisibilityOutlinedIcon")).toBeInTheDocument();
  });

  it("fills the chip with the raw color for the filled variant", async () => {
    const tags = [{ name: "loud", label: "Loud", color: "#673ab7", variant: "filled" }];
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve({ tags, total: tags.length }) } as unknown as Response)));

    render(<TagChip tags={["loud"]} category="custom" />);

    expect((await screen.findByText("Loud")).closest(".MuiChip-root"))
      .toHaveStyle({ backgroundColor: "#673ab7", color: "#fff" });
  });

  it("only shows tags actually belonging to the requested category", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    // in-progress is a review state; from a lifecycle point of view this node has no state,
    // and a category-scoped chip must not present a mis-categorized tag as one
    const { container } = render(<TagChip tags={["in-progress"]} category="lifecycle" />);

    // Flush the resolved definitions, so emptiness means "decided to show nothing", not
    // "still loading"
    await act(() => Promise.resolve());
    expect(container).toBeEmptyDOMElement();
  });

  it("displays all tags when no category is requested, muting the unrecognized ones", async () => {
    const fetchMock = vi.fn(tagAwareFetch({}));
    vi.stubGlobal("fetch", fetchMock);

    render(<TagChip tags={["never-defined", "submitted", "in-progress"]} />);

    // Defined tags come first, in definition order, styled by their definitions
    expect(await screen.findByText("Submitted")).toBeInTheDocument();
    expect(screen.getByText("In progress")).toBeInTheDocument();
    // The unrecognized tag trails as a muted outlined chip with its raw name
    expect(screen.getByText("never-defined").closest(".MuiChip-root")).toHaveClass("MuiChip-outlined");
    expect(screen.getByText("Submitted").closest(".MuiChip-root")).not.toHaveClass("MuiChip-outlined");
    // The whole catalogue was fetched, not one category
    expect(fetchMock.mock.calls[0][0]).toBe("/Tags.search.json");
  });

  it("shows the first defined tag in definition order when several apply", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    render(<TagChip tags={["rejected", "submitted", "not-a-state"]} category="lifecycle" />);

    // "submitted" (order 20) is defined before "rejected" (order 70)
    expect(await screen.findByText("Submitted")).toBeInTheDocument();
    expect(screen.queryByText("Rejected")).toBeNull();
  });

  it("tolerates a single string instead of an array", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    render(<TagChip tags="approved" category="lifecycle" />);

    expect(await screen.findByText("Approved")).toBeInTheDocument();
  });

  it("fetches each category's definitions only once, across chips", async () => {
    const fetchMock = vi.fn(tagAwareFetch({}));
    vi.stubGlobal("fetch", fetchMock);

    render(
      <>
        <TagChip tags={["draft"]} category="lifecycle" />
        <TagChip tags={["approved"]} category="lifecycle" />
        <TagChip tags={["in-progress"]} category="review" />
      </>
    );

    expect(await screen.findByText("Draft")).toBeInTheDocument();
    expect(await screen.findByText("Approved")).toBeInTheDocument();
    expect(await screen.findByText("In progress")).toBeInTheDocument();
    expect(fetchMock.mock.calls.length).toBe(2);
  });

  it("renders nothing for an untagged node", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    const { container } = render(
      <>
        <TagChip category="lifecycle" />
        <TagChip tags={[]} category="lifecycle" />
        <TagChip tags={[42, { complex: "tag" }]} category="lifecycle" />
      </>
    );

    await vi.waitFor(() => {
      expect(vi.mocked(fetch).mock.calls.length).toBeGreaterThan(0);
    });
    expect(container).toBeEmptyDOMElement();
  });

  it("hides undefined tags in category mode", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    const { container } = render(<TagChip tags={["never-defined"]} category="lifecycle" />);

    await act(() => Promise.resolve());
    expect(container).toBeEmptyDOMElement();
  });

  it("tolerates definitions without labels or with unusable colors", async () => {
    // A minimal definition falls back to the tag name; colors outside the hex/rgb/hsl
    // whitelist — malformed ones, named ones, or attempts at smuggling CSS through the
    // generated styles — fall back to a plain chip instead of breaking (or styling) anything
    const tags = [
      { name: "odd", icon: "constructor" },
      { name: "broken", label: "Broken", color: "not-a-color" },
      { name: "sneaky", label: "Sneaky", color: "#fff;background:url(https://evil.example/x)" },
    ];
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve({ tags, total: tags.length }) } as unknown as Response)));

    render(
      <>
        <TagChip tags={["odd"]} category="custom" />
        <TagChip tags={["broken"]} category="custom" />
        <TagChip tags={["sneaky"]} category="custom" />
      </>
    );

    expect(await screen.findByText("odd")).toBeInTheDocument();
    // An icon name outside the curated set — even one naming an inherited Object.prototype
    // member — shows no icon rather than breaking
    expect(screen.getByText("odd").closest(".MuiChip-root")?.querySelector("svg")).toBeNull();
    // The chips still render — as plain default chips — despite the unusable colors
    expect((await screen.findByText("Broken")).closest(".MuiChip-root")).toBeInTheDocument();
    const sneaky = (await screen.findByText("Sneaky")).closest(".MuiChip-root");
    expect(sneaky).toBeInTheDocument();
    expect(sneaky).not.toHaveStyle({ background: "url(https://evil.example/x)" });
    // Filter choices from the same definitions: the label falls back to the name too, and
    // the color rides along raw — consumers whitelist it at the point of styling
    expect(tagValueOptions("custom")()).toEqual([
      { value: "odd", label: "odd" },
      { value: "broken", label: "Broken", color: "not-a-color" },
      { value: "sneaky", label: "Sneaky", color: "#fff;background:url(https://evil.example/x)" },
    ]);
  });

  it("treats a search result without a tag list as no definitions", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve({ total: 0 }) } as unknown as Response)));

    const { container } = render(<TagChip tags={["draft"]} category="lifecycle" />);

    await act(() => Promise.resolve());
    expect(container).toBeEmptyDOMElement();
  });

  it("ignores definitions arriving after the chip is gone", async () => {
    const settlers: ((response: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => {
      settlers.push(resolve);
    })));

    const { unmount } = render(<TagChip tags={["draft"]} category="lifecycle" />);
    unmount();
    settlers[0]({ ok: true, json: () => Promise.resolve({ tags: [] }) } as unknown as Response);
    await act(() => Promise.resolve());
  });

  it("offers the defined tags as filter choices through tagValueOptions", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));
    const options = tagValueOptions("review");

    // Synchronous by contract: empty until the fetch it triggers has resolved
    expect(options()).toEqual([]);
    await vi.waitFor(() => {
      expect(options()).toEqual([
        { value: "in-progress", label: "In progress", color: "#0b5b85" },
        { value: "changes-requested", label: "Changes requested", color: "#8a5410" },
        { value: "approved", label: "Approved", color: "#1d6a3a" },
        { value: "rejected", label: "Rejected", color: "#8e1b29" },
      ]);
    });
    // The chips' own fetches share the same cache, so no second request was needed
    expect(vi.mocked(fetch).mock.calls.length).toBe(1);
  });

  it("degrades gracefully when the definitions cannot be fetched", async () => {
    // A category-scoped chip has nothing trustworthy to show without definitions
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.reject(new Error("network down"))));
    const failed = render(<TagChip tags={["draft"]} category="lifecycle" />);
    await act(() => Promise.resolve());
    expect(failed.container).toBeEmptyDOMElement();
    failed.unmount();

    // The failure is not cached: the next consumer retries the fetch on its own
    const retried = render(<TagChip tags={["draft"]} category="lifecycle" />);
    await vi.waitFor(() => {
      expect(vi.mocked(fetch).mock.calls.length).toBe(2);
    });
    retried.unmount();
    clearTagDefinitionsCache();

    // The full listing still shows the raw names, just muted
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.resolve({ ok: false, status: 500 } as unknown as Response)));
    render(<TagChip tags={["draft"]} />);
    expect((await screen.findByText("draft")).closest(".MuiChip-root")).toHaveClass("MuiChip-outlined");
  });
});
