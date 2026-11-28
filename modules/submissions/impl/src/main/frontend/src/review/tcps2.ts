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
// PRETOTYPE. Where TCPS 2 (2022) says what an issue's policy cites: the page of the copy kept on the platform, made from
// the official PDF's text, and the place on the official site. A citation such as "TCPS 2, Art. 5.5A(c); PHIPA" is
// read for its articles, chapters and chapter sections; anything else in it stays plain text.

// The copy kept on the platform, and the official site, whose chapters each have a page of their own
export const TCPS2_PDF = "/Policies/tcps2-2022-en.pdf";
export const TCPS2_TITLE = "TCPS 2 (2022)";
const SITE = "https://srcr.canada.ca/policies/tcps/tri-council-policy-statement-ethical-conduct-research-involving-humans-tcps";

// Each chapter's page on the site, which follows no single pattern, and the page of the PDF it starts on
const CHAPTERS: Partial<Record<number, { slug: string; page: number }>> = {
  1: { slug: "chapter-1-ethics", page: 12 },
  2: { slug: "chapter-2-scope-a", page: 21 },
  3: { slug: "chapter-3-consent", page: 39 },
  4: { slug: "chapter-4-fairnes", page: 74 },
  5: { slug: "chapter-5-privacy", page: 85 },
  6: { slug: "chapter-6-governa", page: 101 },
  7: { slug: "chapter-7-conflic", page: 132 },
  8: { slug: "chapter-8-multi-j", page: 142 },
  9: { slug: "chapter-9-researc", page: 154 },
  10: { slug: "chapter-10-qualit", page: 191 },
  11: { slug: "chapter-11-clinic", page: 205 },
  12: { slug: "chapter-12-human", page: 235 },
  13: { slug: "chapter-13-human", page: 262 },
};

// The page of the PDF each article starts on, by its number
const ARTICLES: Partial<Record<string, number>> = {
  "1.1": 14, "2.1": 21, "2.2": 25, "2.3": 26, "2.4": 27, "2.5": 29, "2.6": 29, "2.7": 29, "2.8": 31, "2.9": 35,
  "2.10": 36, "2.11": 37, "3.1": 40, "3.2": 43, "3.3": 47, "3.4": 49, "3.5": 51, "3.6": 51, "3.7A": 53,
  "3.7B": 58, "3.8": 61, "3.9": 64, "3.10": 48, "3.11": 66, "3.12": 68, "3.13": 69, "4.1": 75, "4.2": 76,
  "4.3": 77, "4.4": 78, "4.5": 78, "4.6": 79, "4.7": 80, "4.8": 82, "5.1": 89, "5.2": 91, "5.3": 93, "5.4": 95,
  "5.5A": 96, "5.5B": 98, "5.6": 98, "5.7": 99, "6.1": 101, "6.2": 103, "6.3": 104, "6.4": 105, "6.5": 108,
  "6.6": 109, "6.7": 109, "6.8": 110, "6.9": 111, "6.10": 111, "6.11": 113, "6.12": 114, "6.13": 117,
  "6.14": 118, "6.15": 119, "6.16": 121, "6.17": 122, "6.18": 123, "6.19": 123, "6.20": 124, "6.21": 126,
  "6.22": 128, "6.23": 128, "6.24": 130, "7.1": 135, "7.2": 136, "7.3": 137, "7.4": 138, "8.1": 143,
  "8.2": 147, "8.3": 149, "8.4": 151, "9.1": 161, "9.2": 162, "9.3": 166, "9.4": 167, "9.5": 168, "9.6": 169,
  "9.7": 170, "9.8": 171, "9.9": 173, "9.10": 175, "9.11": 176, "9.12": 178, "9.13": 179, "9.14": 180,
  "9.15": 181, "9.16": 182, "9.17": 183, "9.18": 184, "9.19": 186, "9.20": 187, "9.21": 188, "9.22": 188,
  "10.1": 196, "10.2": 197, "10.3": 199, "10.4": 201, "10.5": 196, "11.1": 211, "11.2": 211, "11.3": 220,
  "11.4": 221, "11.5": 223, "11.6": 225, "11.7": 227, "11.8": 229, "11.9": 231, "11.10": 232, "11.11": 233,
  "12.1": 238, "12.2": 239, "12.3A": 240, "12.3B": 242, "12.4": 243, "12.5": 244, "12.6": 246, "12.7": 247,
  "12.8": 247, "12.9": 248, "12.10": 249, "12.11": 252, "12.12": 253, "12.13": 253, "12.14": 254, "12.15": 254,
  "12.16": 255, "12.17": 255, "12.18": 255, "12.19": 256, "12.20": 256, "12.21": 256, "12.22": 259,
  "13.1": 263, "13.2": 263, "13.3": 264, "13.4": 266, "13.5": 266, "13.6": 267, "13.7": 268,
};

// The page each chapter's lettered section starts on, such as "5A"
const SECTIONS: Partial<Record<string, number>> = {
  "1A": 12, "1B": 13, "1C": 18, "2A": 21, "2B": 31, "3A": 40, "3B": 53, "3C": 63, "3D": 68, "3E": 69, "4A": 75,
  "4B": 76, "5A": 86, "5B": 89, "5C": 93, "5D": 95, "5E": 99, "6A": 101, "6B": 113, "6C": 123, "6D": 125,
  "6E": 129, "7A": 133, "7B": 135, "7C": 137, "7D": 138, "8A": 142, "8B": 149, "9A": 157, "9B": 159, "9C": 161,
  "10A": 192, "10B": 196, "11A": 206, "11B": 211, "11C": 224, "11D": 232, "12A": 236, "12B": 237, "12C": 240,
  "12D": 244, "12E": 245, "12F": 249, "12G": 256, "13A": 262, "13B": 263, "13C": 266, "13D": 266, "13E": 267,
  "13F": 268, "13G": 269,
};

// A place in TCPS 2 a citation names: what to call it, the PDF's page, the words to mark there, and the place on the
// official site
export interface PolicyPlace {
  label: string;
  page: number;
  find?: string;
  web: string;
}

// A citation cut into what it names in TCPS 2, each with its place, and the plain text between
export interface CitationPart {
  text: string;
  place?: PolicyPlace;
}

const REFERENCE = /(Art(?:icles?|s?\.)\s*)?(\d{1,2})\.(\d{1,2}[A-Z]?)|Ch(?:apter|\.)\s*(\d{1,2})(?:\s*§\s*([A-H]))?/g;

function placeOf(match: RegExpExecArray): PolicyPlace | undefined {
  const [ , , chapter, article, wholeChapter, section ] = match;
  if (chapter && article) {
    const number = `${chapter}.${article}`;
    const page = ARTICLES[number];
    const slug = CHAPTERS[Number(chapter)]?.slug;
    return page === undefined || !slug ? undefined : { label: `Article ${number}`, page, find: `Article ${number}`,
      web: `${SITE}-${slug}.html#${article.toLowerCase()}` };
  }
  const known = CHAPTERS[Number(wholeChapter)];
  if (!known) {
    return undefined;
  }
  const page = section ? SECTIONS[`${wholeChapter}${section}`] ?? known.page : known.page;
  return { label: section ? `Chapter ${wholeChapter}, Section ${section}` : `Chapter ${wholeChapter}`, page,
    web: `${SITE}-${known.slug}.html${section ? `#${section.toLowerCase()}` : ""}` };
}

// The parts of a citation, its TCPS 2 references with their places; a citation naming no TCPS 2 is all plain text
export function citationParts(citation: string): CitationPart[] {
  if (!/TCPS\s*2/.test(citation)) {
    return [ { text: citation } ];
  }
  const parts: CitationPart[] = [];
  let at = 0;
  for (const match of citation.matchAll(REFERENCE)) {
    const place = placeOf(match);
    if (!place) {
      continue;
    }
    if (match.index > at) {
      parts.push({ text: citation.slice(at, match.index) });
    }
    parts.push({ text: match[0], place });
    at = match.index + match[0].length;
  }
  if (at < citation.length) {
    parts.push({ text: citation.slice(at) });
  }
  return parts;
}
