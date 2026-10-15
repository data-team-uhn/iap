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

/**
 * A real PDF of one page, showing one line of text.
 *
 * The browser opens a PDF before uploading it, and refuses a file that only starts like one. This one has
 * every part a reader needs: the objects, the table of where each one starts, and the trailer.
 *
 * @param text the line on the page, in ASCII, without parentheses or backslashes
 * @returns the file's bytes
 */
export function onePagePdf(text: string): Buffer {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R'
      + ' /Resources << /Font << /F1 5 0 R >> >> >>',
    contentStream(`BT /F1 12 Tf 72 720 Td (${text}) Tj ET`),
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ].map((object, index) => `${index + 1} 0 obj\n${object}\nendobj\n`);
  const starts = objects.map((_, index) => HEADER.length + objects.slice(0, index).join('').length);
  const body = HEADER + objects.join('');
  const xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
    + starts.map(start => `${String(start).padStart(10, '0')} 00000 n \n`).join('');
  const trailer = `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${body.length}\n%%EOF\n`;
  return Buffer.from(body + xref + trailer, 'latin1');
}

function contentStream(content: string): string {
  return `<< /Length ${content.length} >>\nstream\n${content}\nendstream`;
}
