const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../control-center/web/assistant-markdown.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const render = async input => (await modulePromise).renderAssistantMarkdown(input);

test('paragraphs preserve Unicode and line boundaries while normalizing CRLF', async () => {
    assert.equal(await render('Zażółć & <tekst>\r\nDruga linia\r\n\r\nNowy akapit'), '<p>Zażółć &amp; &lt;tekst&gt;<br>Druga linia</p><p>Nowy akapit</p>');
    assert.equal(await render(null), ''); assert.equal(await render(''), '');
});

test('assistant headings remain at h3 without being wrapped in invalid paragraphs', async () => {
    assert.equal(await render('# One\n## Two\n### Three'), '<h3>One</h3><h3>Two</h3><h3>Three</h3>');
    assert.equal(await render('# **Title** <tag>'), '<h3><strong>Title</strong> &lt;tag&gt;</h3>');
});

test('nested emphasis and inline code do not reinterpret literal code contents', async () => {
    assert.equal(await render('**bold *em*** and *em **bold***'), '<p><strong>bold <em>em</em></strong> and <em>em <strong>bold</strong></em></p>');
    assert.equal(await render('***both*** and `**literal**`'), '<p><strong><em>both</em></strong> and <code>**literal**</code></p>');
    assert.equal(await render('**a `**literal**` end**'), '<p><strong>a <code>**literal**</code> end</strong></p>');
});

test('fenced code preserves indentation, blank lines and all markdown markers literally', async () => {
    const code = '  **literal**\n\n# heading\n> quote\n|a|b|\n- item\n<script>x</script>';
    assert.equal(await render('```bash\n' + code + '\n```'), '<pre><code class="lang-bash">  **literal**\n\n# heading\n&gt; quote\n|a|b|\n- item\n&lt;script&gt;x&lt;/script&gt;</code></pre>');
});

test('unclosed fences stay literal and unsafe language information cannot create attributes', async () => {
    assert.equal(await render('~~~\n**literal**\n<tag>'), '<pre><code>**literal**\n&lt;tag&gt;</code></pre>');
    assert.equal(await render('```" onmouseover="x\nvalue\n```'), '<pre><code>value</code></pre>');
    assert.equal(await render('````js\n```\n**value**\n````'), '<pre><code class="lang-js">```\n**value**</code></pre>');
});

test('multiple inline backticks protect a literal backtick and unclosed spans remain text', async () => {
    assert.equal(await render('before ``a ` b`` after'), '<p>before <code>a ` b</code> after</p>');
    assert.equal(await render('before `unclosed'), '<p>before `unclosed</p>');
});

test('only contiguous bullet items are grouped and paragraphs remain outside their lists', async () => {
    assert.equal(await render('- One\n* Two\n\nMiddle\n\n+ Three'), '<ul><li>One</li><li>Two</li></ul><p>Middle</p><ul><li>Three</li></ul>');
});

test('quotes are recognized before escaping while literal entity text stays literal', async () => {
    assert.equal(await render('> One\n> **Two**\n\nOutside'), '<blockquote><p>One<br><strong>Two</strong></p></blockquote><p>Outside</p>');
    assert.equal(await render('&gt; literal'), '<p>&amp;gt; literal</p>');
});

test('tables require a separator and keep data containing three hyphens', async () => {
    assert.equal(await render('| Name | Value |\n| --- | :---: |\n| release---candidate | **ok** |'), '<table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody><tr><td>release---candidate</td><td><strong>ok</strong></td></tr></tbody></table>');
});

test('table pipes escaped or inside code do not split a cell', async () => {
    assert.equal(await render('| Expression | Value |\n| --- | --- |\n| `a|b` | x\\|y |'), '<table><thead><tr><th>Expression</th><th>Value</th></tr></thead><tbody><tr><td><code>a|b</code></td><td>x|y</td></tr></tbody></table>');
});

test('plain pipes and malformed separators are not converted into tables', async () => {
    assert.equal(await render('a | b'), '<p>a | b</p>');
    assert.equal(await render('| a | b |\n| -- | --- |'), '<p>| a | b |<br>| -- | --- |</p>');
});

test('HTML payloads remain escaped in paragraphs, headings, quotes, list items and cells', async () => {
    for (const input of ['<script>alert(1)</script>', '# <svg onload=x>', '> <iframe src=x>', '- <img src=x onerror=x>', '| H |\n| --- |\n| <input autofocus> |']) {
        const html = await render(input);
        assert.doesNotMatch(html, /<(script|svg|iframe|img|input)\b/i);
        assert.match(html, /&lt;/);
    }
});

test('markdown links and media are inert text and backslash escapes do not create markup', async () => {
    const html = await render('[open](javascript:alert(1)) ![photo](https://example.invalid/a.png)\n\\*literal\\*');
    assert.doesNotMatch(html, /<(a|img)\b|href=|src=/i);
    assert.match(html, /\[open\]\(javascript:alert\(1\)\)/);
    assert.match(html, /\*literal\*/);
});

test('long malformed markup cannot cause recursive stack overflow or introduce active HTML', async () => {
    const html = await render('**a *b '.repeat(10000) + '<script>bad</script>');
    assert.match(html, /&lt;script&gt;bad&lt;\/script&gt;/);
    assert.doesNotMatch(html, /<script/);
});
