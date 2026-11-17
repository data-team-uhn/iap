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

const HEADER = '%PDF-1.4\n';

/** How a written PDF's pages are laid out, in points. */
export interface PageLayout {
  width: number;
  height: number;
  fontSize: number;
}

/** A US Letter page, in 12-point type. */
const LETTER: PageLayout = { width: 612, height: 792, fontSize: 12 };

/** The space left of the text and above its first line. */
const MARGIN = 72;

/**
 * A real PDF whose pages show lines of text in Helvetica, named but not embedded, so its letters have known
 * widths. Each line sits one and a half lines below the one before it.
 *
 * The browser opens a PDF before uploading it, and refuses a file that only starts like one. This one has
 * every part a reader needs: the objects, the table of where each one starts, and the trailer.
 *
 * @param pages the lines of each page, in ASCII, without parentheses or backslashes
 * @param layout the page size and type size, US Letter in 12-point type when absent
 * @returns the file's bytes
 */
export function writePdf(pages: string[][], layout: Partial<PageLayout> = {}): Buffer {
  const { width, height, fontSize } = { ...LETTER, ...layout };
  const pageIds = pages.map((_, index) => 4 + 2 * index);
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Kids [${pageIds.map(id => `${id} 0 R`).join(' ')}] /Count ${pages.length} >>`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    ...pages.flatMap((lines, index) => [
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${width} ${height}] /Contents ${pageIds[index] + 1} 0 R`
        + ' /Resources << /Font << /F1 3 0 R >> >> >>',
      contentStream(`BT /F1 ${fontSize} Tf ${MARGIN} ${height - MARGIN} Td `
        + lines.map(line => `(${line}) Tj`).join(` 0 -${1.5 * fontSize} Td `) + ' ET'),
    ]),
  ].map((object, index) => `${index + 1} 0 obj\n${object}\nendobj\n`);
  const starts = objects.map((_, index) => HEADER.length + objects.slice(0, index).join('').length);
  const body = HEADER + objects.join('');
  const xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
    + starts.map(start => `${String(start).padStart(10, '0')} 00000 n \n`).join('');
  const trailer = `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${body.length}\n%%EOF\n`;
  return Buffer.from(body + xref + trailer, 'latin1');
}

/**
 * A real PDF of one US Letter page, showing one line of text.
 *
 * @param text the line on the page, in ASCII, without parentheses or backslashes
 * @returns the file's bytes
 */
export function onePagePdf(text: string): Buffer {
  return writePdf([ [ text ] ]);
}

function contentStream(content: string): string {
  return `<< /Length ${content.length} >>\nstream\n${content}\nendstream`;
}
