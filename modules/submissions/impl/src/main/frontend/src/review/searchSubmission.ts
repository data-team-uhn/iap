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

import { loadPdfjs } from "@iap/frontend-commons/pdfViewer/pdfjsClient";
import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type FormItem, isQuestion } from "../submissionForm";

// PRETOTYPE. Searching a whole submission, answers and documents, in the browser, the way a browser finds in a
// page: every occurrence counts. Documents are read with PDF.js, page by page, once per page load; a real version
// would search the text the server already extracted.

// One occurrence in a form: in a question's text or its answer, and which one there it is
export interface FormMatch {
  question: string;
  field: "question" | "answer";
  nth: number;
}

const texts = new Map<string, Promise<string[]>>();

async function readPages(doFetch: AuthenticatedFetch, url: string): Promise<string[]> {
  const response = await doFetch(url);
  const data = new Uint8Array(await response.arrayBuffer());
  const { pdfjs, worker, dataUrls } = await loadPdfjs();
  const doc = await pdfjs.getDocument({ data, worker, useSystemFonts: false, ...dataUrls }).promise;
  const pages: string[] = [];
  for (let number = 1; number <= doc.numPages; number++) {
    const content = await (await doc.getPage(number)).getTextContent();
    pages.push(content.items.map(item => "str" in item ? item.str + (item.hasEOL ? " " : "") : "").join(""));
  }
  return pages;
}

export function documentText(doFetch: AuthenticatedFetch, url: string): Promise<string[]> {
  let read = texts.get(url);
  if (!read) {
    read = readPages(doFetch, url).catch(() => []);
    texts.set(url, read);
  }
  return read;
}

// Where the terms occur in a text, ignoring case: the start of each occurrence
export function occurrences(text: string, terms: string): number[] {
  const needle = terms.toLowerCase();
  const haystack = text.toLowerCase();
  const found: number[] = [];
  if (needle === "") {
    return found;
  }
  for (let at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length)) {
    found.push(at);
  }
  return found;
}

// Every occurrence in a form's questions and answers, in the order the form asks them
export function formMatches(items: FormItem[], terms: string, answer: (path: string) => string): FormMatch[] {
  return items.flatMap(item => {
    if (!isQuestion(item)) {
      return formMatches(item.items, terms, answer);
    }
    return [
      ...occurrences(item.text, terms).map((_at, nth) => ({ question: item.path, field: "question" as const, nth })),
      ...occurrences(answer(item.path), terms).map((_at, nth) => ({ question: item.path, field: "answer" as const,
        nth })),
    ];
  });
}

// How many occurrences each page of a document has
export function pageCounts(pages: string[], terms: string): number[] {
  return pages.map(page => occurrences(page.replace(/\s+/g, " "), terms).length);
}
