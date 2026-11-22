// Checks for the reply renderer (markdown.js): structure, escaping, streaming, citations. Run: node app/src/test/js/markdown.test.js
const fs = require('fs');
global.window = {};
eval(fs.readFileSync(require('path').join(__dirname, '../../main/assets/www/markdown.js'), 'utf8'));
const { render, toPlainText } = window.Markdown;
let failed = 0;
const check = (name, cond, extra = '') => { if (!cond) { failed++; console.log('FAIL', name, extra); } else console.log('ok  ', name); };

const doc = `## Plan

Here is **bold**, *italic*, \`code\` and ~~old~~.

1. First step
2. Second step
   - nested a
   - nested b
3. Third

- [x] done
- [ ] todo

| Item | Price |
|:-----|------:|
| Tea | 2.50 |
| Cake | 4 |

> A quote with **bold**

\`\`\`kotlin
val x = "<b>" // not html
\`\`\`

---
Line one
line two`;
const h = render(doc);
check('heading', h.includes('<h4 class="md-h">Plan</h4>'));
check('inline', h.includes('<strong>bold</strong>') && h.includes('<em>italic</em>') && h.includes('<code>code</code>') && h.includes('<del>old</del>'));
check('ordered + nested', /<ol><li>First step<\/li><li>Second step<ul><li>nested a<\/li><li>nested b<\/li><\/ul><\/li><li>Third<\/li><\/ol>/.test(h), h.match(/<ol>.*?<\/ol>/s)?.[0]);
check('tasks', h.includes('md-check is-done') && (h.match(/md-task/g) || []).length === 2);
check('table align', h.includes('<th style="text-align:right">Price</th>') && h.includes('<td style="text-align:right">2.50</td>'));
check('quote', h.includes('<blockquote><p>A quote with <strong>bold</strong></p></blockquote>'));
check('code escaped', h.includes('&lt;b&gt;') && h.includes('<span>kotlin</span>') && !h.includes('<b>'));
check('rule + br', h.includes('<hr>') && h.includes('Line one<br>line two'));

const evil = render('<img src=x onerror=alert(1)> [x](javascript:alert(1)) **<script>**');
check('no html injection', !/<img|<script|javascript:alert\(1\)"/.test(evil) && evil.includes('&lt;img'), evil);

check('unclosed fence streams', render('Text\n```py\nprint(1)').includes('<pre><code>print(1)</code></pre>'));
check('table before separator is text', render('| a | b |').startsWith('<p>'));
check('star bullet vs emphasis', render('*not a list*').includes('<em>not a list</em>') && render('* item').includes('<ul><li>item</li></ul>'));
check('number in sentence is not a list', !render('The year 2024. was good').includes('<ol'));
const cited = render('Fly Friday [1] and stay [1, 2]. Missing [9].', { cite: (n) => (n <= 2 ? `<b class="cite">${n}</b>` : null) });
check('citations', cited.includes('Friday\u00a0<b class="cite">1</b>') && cited.includes('<b class="cite">1</b><b class="cite">2</b>') && cited.includes('[9]'), cited);
check('plain text copy', toPlainText('## Hi\n**bold** and `x`') === 'Hi\nbold and x');
check('link text shown', render('[docs](https://example.com)').includes('<span class="md-link" title="https://example.com">docs</span>'));
process.exit(failed ? 1 : 0);
