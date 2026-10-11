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
import { findQuote, getSearchText, unquote } from "@iap/frontend-commons/pdfViewer/quoteMatch";

// The text a match covers in the page, for readable assertions.
function matched(page: string, quote: string): string[] {
  const searchText = getSearchText(quote);
  if (searchText === undefined) {
    throw new Error(`"${quote}" is too short to search for`);
  }
  return findQuote(page, searchText).map(({ index, length }) => page.slice(index, index + length));
}

describe("getSearchText", () => {
  it("ignores case, spaces and line breaks", () => {
    expect(getSearchText("The Quick\n brown  fox")).toBe("thequickbrownfox");
  });

  it("is undefined for a quote too short to trust", () => {
    expect(getSearchText("Yes.")).toBeUndefined();
    expect(getSearchText("  a b  ")).toBeUndefined();
  });

  it("leaves out quotation marks around the quote", () => {
    expect(getSearchText("\"a quoted passage\"")).toBe("aquotedpassage");
    expect(getSearchText("“a quoted passage”")).toBe("aquotedpassage");
    expect(getSearchText("'a quoted passage'")).toBe("aquotedpassage");
    expect(getSearchText("«a quoted passage»")).toBe("aquotedpassage");
  });

  it("keeps quotation marks inside the quote", () => {
    expect(getSearchText("it is \"so\" said")).toBe("itis\"so\"said");
  });

  it("counts the length once the quotation marks are gone", () => {
    expect(getSearchText("\"Yes.\"")).toBeUndefined();
  });
});

describe("unquote", () => {
  it("removes quotation marks and spaces around the quote", () => {
    expect(unquote(" “a quoted passage” ")).toBe("a quoted passage");
    expect(unquote("\"a quoted passage\"")).toBe("a quoted passage");
    expect(unquote("«a quoted passage»")).toBe("a quoted passage");
  });

  it("keeps quotation marks inside the quote", () => {
    expect(unquote("it is “so” said")).toBe("it is “so” said");
  });
});

describe("findQuote", () => {
  it("finds a quote the page spaces or breaks differently", () => {
    const page = "Volunteers who, on a casual\nbasis, assist from time to time.";
    expect(matched(page, "Volunteers who, on a casual basis")).toEqual([ "Volunteers who, on a casual\nbasis" ]);
    expect(matched(page, "Volunteers who,on a casualbasis")).toEqual([ "Volunteers who, on a casual\nbasis" ]);
  });

  it("matches typographic quotes and dashes with plain ones", () => {
    const page = "the principal’s “discretion” — always";
    expect(matched(page, "the principal's \"discretion\" - always")).toEqual([ page ]);
  });

  it("ignores hyphens, so a word broken across lines still matches", () => {
    expect(matched("a vulner-\nable sector", "vulnerable sector")).toEqual([ "vulner-\nable sector" ]);
    expect(matched("a vulnerable sector", "- vulnerable sector")).toEqual([ "vulnerable sector" ]);
  });

  it("keeps a minus before a digit", () => {
    expect(matched("from −5 to 5 degrees", "from -5 to")).toEqual([ "from −5 to" ]);
    expect(matched("from 5 to 5 degrees", "from -5 to")).toEqual([]);
  });

  it("ignores accents and soft hyphens, and reads ligatures as letters", () => {
    expect(matched("the café opens", "cafe opens")).toEqual([ "café opens" ]);
    expect(matched("an ex\u00adample here", "an example")).toEqual([ "an ex\u00adample" ]);
    expect(matched("a ﬁne result", "a fine result")).toEqual([ "a ﬁne result" ]);
  });

  it("finds every occurrence", () => {
    const page = "Sign the form. Then sign the form again.";
    expect(findQuote(page, "signtheform")).toEqual([ { index: 0, length: 13 }, { index: 20, length: 13 } ]);
  });

  it("finds nothing when the page does not have the quote", () => {
    expect(matched("Cover page of the document.", "a passage from elsewhere")).toEqual([]);
    expect(matched("", "a passage from elsewhere")).toEqual([]);
  });
});
