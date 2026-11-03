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

// PRETOTYPE. Searching a whole submission, answers and documents, in the browser. Documents are read with PDF.js,
// page by page, once per page load; a real version would search the text the server already extracted.

export interface Hit {
  // The requirement it is in, and the question or page
  requirement: string;
  question?: string;
  page?: number;
  where: string;
  snippet: string;
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

function around(text: string, at: number, length: number): string {
  const start = Math.max(0, at - 50);
  const end = Math.min(text.length, at + length + 70);
  return `${start > 0 ? "…" : ""}${text.slice(start, end).replace(/\s+/g, " ")}${end < text.length ? "…" : ""}`;
}

export function searchAnswers(requirement: string, items: FormItem[], terms: string, answer: (path: string) => string)
  : Hit[] {
  const needle = terms.toLowerCase();
  return items.flatMap(item => {
    if (!isQuestion(item)) {
      return searchAnswers(requirement, item.items, terms, answer);
    }
    const said = `${item.text} ${answer(item.path)}`;
    const at = said.toLowerCase().indexOf(needle);
    return at < 0 ? []
      : [ { requirement, question: item.path, where: item.text, snippet: around(said, at, needle.length) } ];
  });
}

export function searchPages(requirement: string, title: string, pages: string[], terms: string): Hit[] {
  const needle = terms.toLowerCase();
  return pages.flatMap((page, index) => {
    const at = page.toLowerCase().indexOf(needle);
    return at < 0 ? [] : [ { requirement, page: index + 1, where: `${title}, page ${index + 1}`,
      snippet: around(page, at, needle.length) } ];
  });
}
