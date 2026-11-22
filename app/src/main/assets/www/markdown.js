/* Tune2AI Studio: a small, safe Markdown renderer for model replies.

   Everything is HTML-escaped first, then a fixed set of Markdown constructs become markup, so model
   output can never inject tags or scripts. Built for streaming: it re-renders the whole reply on
   each update, and half-finished input (an unclosed code fence, a table without its separator row
   yet) renders sensibly until the rest arrives.

   Supported: headings, paragraphs and line breaks, **bold**, *italic*, ~~strike~~, `code`, fenced
   code blocks (with a copy button), bullet / numbered / task lists (nested by indent), quotes,
   tables (with column alignment), horizontal rules, [text](https://links), and numbered citations
   like [1] or [1, 2] when a citation callback is given.
   ponytail: a hand-rolled subset, not CommonMark. Swap in a vetted parser (with sanitising) if
   replies need footnotes, images, or HTML blocks. */
(function () {
  const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ESC[c]);

  const LIST = /^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$/;
  const FENCE = /^\s*(```+|~~~+)\s*([\w+#.-]*)\s*$/;
  const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
  const RULE = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
  const TABLE_SEP = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

  function inline(text, opts) {
    const codes = [];
    let s = text.replace(/`([^`\n]+)`/g, (_, c) => {
      codes.push(c);
      return `\u0000${codes.length - 1}\u0000`;
    });
    s = esc(s)
      .replace(/\*\*(?=\S)([\s\S]*?\S)\*\*/g, "<strong>$1</strong>")
      .replace(/__(?=\S)([\s\S]*?\S)__/g, "<strong>$1</strong>")
      .replace(/(^|[^*\w])\*(?=\S)([^*\n]*?\S)\*(?!\*)/g, "$1<em>$2</em>")
      .replace(/(^|[^_\w])_(?=\S)([^_\n]*?\S)_(?!\w)/g, "$1<em>$2</em>")
      .replace(/~~(?=\S)([\s\S]*?\S)~~/g, "<del>$1</del>")
      // Links show their text; the app never navigates away from its own page.
      .replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g, '<span class="md-link" title="$2">$1</span>');
    if (opts.cite) {
      // A no-break space keeps the marker on the line of the word it supports.
      s = s.replace(/(\s*)\[(\d{1,2}(?:\s*,\s*\d{1,2})*)\]/g, (whole, space, list) => {
        const parts = list.split(/\s*,\s*/).map((n) => opts.cite(Number(n)));
        return parts.every(Boolean) ? (space ? "\u00a0" : "") + parts.join("") : whole;
      });
    }
    return s.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${esc(codes[Number(i)])}</code>`);
  }

  function codeBlock(code, lang) {
    return `<div class="md-code"><div class="md-code-head"><span>${esc(lang || "code")}</span>` +
      `<button type="button" class="md-copy" data-copy-code>Copy</button></div><pre><code>${esc(code)}</code></pre></div>`;
  }

  function cells(row) {
    let r = row.trim();
    if (r.startsWith("|")) r = r.slice(1);
    if (r.endsWith("|") && !r.endsWith("\\|")) r = r.slice(0, -1);
    return r.split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
  }

  function render(src, opts = {}) {
    const lines = String(src).replace(/\r\n?/g, "\n").split("\n");
    let html = "";
    const para = [];
    const flush = () => {
      if (!para.length) return;
      html += `<p>${inline(para.join("\n"), opts).replace(/\n/g, "<br>")}</p>`;
      para.length = 0;
    };

    function list(i, indent) {
      const first = lines[i].match(LIST);
      const ordered = /\d/.test(first[2]);
      const start = ordered ? parseInt(first[2], 10) : 1;
      let out = ordered ? `<ol${start !== 1 ? ` start="${start}"` : ""}>` : "<ul>";
      while (i < lines.length) {
        const m = lines[i].match(LIST);
        if (!m) break;
        const ind = m[1].replace(/\t/g, "    ").length;
        if (ind < indent) break;
        if (ind > indent) { // nested list belongs to the previous item
          const [sub, next] = list(i, ind);
          out = out.endsWith("</li>") ? `${out.slice(0, -5)}${sub}</li>` : out + sub;
          i = next;
          continue;
        }
        if (/\d/.test(m[2]) !== ordered) break;
        let text = m[3];
        i++;
        while (i < lines.length && lines[i].trim() && !LIST.test(lines[i]) && /^\s{2,}/.test(lines[i])) text += `\n${lines[i++].trim()}`;
        const task = text.match(/^\[( |x|X)\]\s+(.*)$/s);
        out += task
          ? `<li class="md-task"><span class="md-check${task[1] === " " ? "" : " is-done"}"></span>${inline(task[2], opts).replace(/\n/g, "<br>")}</li>`
          : `<li>${inline(text, opts).replace(/\n/g, "<br>")}</li>`;
        // A single blank line between items keeps the list going.
        if (i + 1 < lines.length && !lines[i].trim() && LIST.test(lines[i + 1])) i++;
      }
      return [out + (ordered ? "</ol>" : "</ul>"), i];
    }

    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      let m;
      if ((m = line.match(FENCE))) {
        flush();
        const fence = m[1];
        const body = [];
        i++;
        while (i < lines.length && !lines[i].trim().startsWith(fence)) body.push(lines[i++]);
        i++; // closing fence (absent while streaming)
        html += codeBlock(body.join("\n"), m[2]);
        continue;
      }
      if (!line.trim()) { flush(); i++; continue; }
      if (line.includes("|") && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1]) && lines[i + 1].includes("-")) {
        flush();
        const head = cells(line);
        const align = cells(lines[i + 1]).map((c) => (c.startsWith(":") && c.endsWith(":") ? "center" : c.endsWith(":") ? "right" : ""));
        const style = (k) => (align[k] ? ` style="text-align:${align[k]}"` : "");
        i += 2;
        let body = "";
        while (i < lines.length && lines[i].includes("|") && lines[i].trim()) {
          body += `<tr>${cells(lines[i]).map((c, k) => `<td${style(k)}>${inline(c, opts)}</td>`).join("")}</tr>`;
          i++;
        }
        html += `<div class="md-table"><table><thead><tr>${head.map((c, k) => `<th${style(k)}>${inline(c, opts)}</th>`).join("")}</tr></thead><tbody>${body}</tbody></table></div>`;
        continue;
      }
      if ((m = line.match(HEADING))) {
        flush();
        const level = m[1].length; // # → h3 … in a chat bubble, smaller than the app's own titles
        html += `<h${Math.min(level + 2, 6)} class="md-h">${inline(m[2], opts)}</h${Math.min(level + 2, 6)}>`;
        i++;
        continue;
      }
      if (RULE.test(line)) { flush(); html += "<hr>"; i++; continue; }
      if (/^\s{0,3}>/.test(line)) {
        flush();
        const quoted = [];
        while (i < lines.length && /^\s{0,3}>/.test(lines[i])) quoted.push(lines[i++].replace(/^\s{0,3}>\s?/, ""));
        html += `<blockquote>${render(quoted.join("\n"), opts)}</blockquote>`;
        continue;
      }
      if (LIST.test(line) && (para.length === 0 || /^\s*([-*+]|1[.)])\s/.test(line))) {
        flush();
        const [out, next] = list(i, line.match(LIST)[1].length);
        html += out;
        i = next;
        continue;
      }
      para.push(line.trim());
      i++;
    }
    flush();
    return html;
  }

  /** Plain text of a reply, for copying: Markdown markers removed, structure kept. */
  function toPlainText(src) {
    return String(src)
      .replace(/^\s*(```+|~~~+).*$/gm, "")
      .replace(/^\s{0,3}#{1,6}\s+/gm, "")
      .replace(/\*\*([^*]+)\*\*/g, "$1")
      .replace(/__([^_]+)__/g, "$1")
      .replace(/(^|\W)\*([^*\n]+)\*/g, "$1$2")
      .replace(/`([^`\n]+)`/g, "$1")
      .replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g, "$1 ($2)")
      .trim();
  }

  window.Markdown = { render, toPlainText, escape: esc };
})();
