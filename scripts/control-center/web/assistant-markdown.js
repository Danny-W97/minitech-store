/** Small, inert Markdown subset for CC assistant replies. No HTML, links or media. */
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
const runLength = (text, offset, character) => {
    let end = offset;
    while (text[end] === character) end++;
    return end - offset;
};

function inline(text, start = 0, closing = '', depth = 0) {
    if (depth >= 16) return {html: escapeHtml(text.slice(start)).replace(/\n/g, '<br>'), end: text.length, closed: false};
    const parts = [];
    let index = start;
    while (index < text.length) {
        if (text[index] === '\\' && '\\`*_{}[]()#+-.!>|'.includes(text[index + 1] || '\0')) {
            parts.push(escapeHtml(text[index + 1])); index += 2; continue;
        }
        if (text[index] === '`') {
            const length = runLength(text, index, '`');
            let end = text.indexOf('`', index + length);
            while (end >= 0 && runLength(text, end, '`') !== length) end = text.indexOf('`', end + runLength(text, end, '`'));
            if (end >= 0) {
                parts.push(`<code>${escapeHtml(text.slice(index + length, end))}</code>`);
                index = end + length; continue;
            }
            parts.push('`'.repeat(length)); index += length; continue;
        }
        const closes = closing && text.startsWith(closing, index)
            && !(closing === '*' && text.startsWith('**', index) && text[index + 2] !== '*');
        if (closes) return {html: parts.join(''), end: index + closing.length, closed: true};
        const marker = text.startsWith('**', index) ? '**' : text[index] === '*' ? '*' : '';
        if (marker) {
            const content = inline(text, index + marker.length, marker, depth + 1);
            const tag = marker === '**' ? 'strong' : 'em';
            parts.push(content.closed ? `<${tag}>${content.html}</${tag}>` : escapeHtml(marker) + content.html);
            index = content.end; continue;
        }
        parts.push(text[index] === '\n' ? '<br>' : escapeHtml(text[index])); index++;
    }
    return {html: parts.join(''), end: index, closed: false};
}

function tableCells(line) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) return null;
    const body = trimmed.slice(1, -1), cells = [];
    let cell = '', codeRun = 0;
    for (let index = 0; index < body.length; index++) {
        const character = body[index];
        if (character === '\\' && !codeRun && index + 1 < body.length) {cell += character + body[++index]; continue;}
        if (character === '`') {
            const length = runLength(body, index, '`');
            if (!codeRun) codeRun = length; else if (codeRun === length) codeRun = 0;
            cell += '`'.repeat(length); index += length - 1; continue;
        }
        if (character === '|' && !codeRun) {cells.push(cell.trim()); cell = '';}
        else cell += character;
    }
    cells.push(cell.trim());
    return cells;
}

const fence = line => /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
const heading = line => /^ {0,3}#{1,6}[ \t]+(.+)$/.exec(line);
const quote = line => /^ {0,3}>[ \t]?(.*)$/.exec(line);
const bullet = line => /^ {0,3}[-*+][ \t]+(.+)$/.exec(line);

export function renderAssistantMarkdown(value) {
    if (!value) return '';
    const lines = String(value).replace(/\r\n?/g, '\n').split('\n');
    const blocks = [];
    const tableStart = index => {
        const header = tableCells(lines[index] || ''), separator = tableCells(lines[index + 1] || '');
        return header && separator && header.length === separator.length && separator.every(cell => /^:?-{3,}:?$/.test(cell));
    };
    const startsBlock = index => fence(lines[index]) || heading(lines[index]) || quote(lines[index]) || bullet(lines[index]) || tableStart(index);
    for (let index = 0; index < lines.length;) {
        const line = lines[index];
        if (!line.trim()) {index++; continue;}
        const code = fence(line);
        if (code) {
            const contents = [], marker = code[1][0], length = code[1].length;
            const language = code[2].trim().split(/\s/)[0];
            index++;
            while (index < lines.length) {
                const candidate = lines[index].trim(), count = runLength(candidate, 0, marker);
                if (count >= length && !candidate.slice(count).trim()) {index++; break;}
                contents.push(lines[index++]);
            }
            const className = /^[A-Za-z0-9_-]{1,40}$/.test(language) ? ` class="lang-${language}"` : '';
            blocks.push(`<pre><code${className}>${escapeHtml(contents.join('\n'))}</code></pre>`); continue;
        }
        const title = heading(line);
        if (title) {blocks.push(`<h3>${inline(title[1]).html}</h3>`); index++; continue;}
        if (quote(line)) {
            const contents = [];
            while (index < lines.length && quote(lines[index])) contents.push(quote(lines[index++])[1]);
            blocks.push(`<blockquote><p>${inline(contents.join('\n')).html}</p></blockquote>`); continue;
        }
        if (bullet(line)) {
            const items = [];
            while (index < lines.length && bullet(lines[index])) items.push(`<li>${inline(bullet(lines[index++])[1]).html}</li>`);
            blocks.push(`<ul>${items.join('')}</ul>`); continue;
        }
        if (tableStart(index)) {
            const header = tableCells(line), rows = [];
            index += 2;
            while (index < lines.length && tableCells(lines[index])) {
                rows.push(`<tr>${tableCells(lines[index++]).map(cell => `<td>${inline(cell).html}</td>`).join('')}</tr>`);
            }
            blocks.push(`<table><thead><tr>${header.map(cell => `<th>${inline(cell).html}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table>`); continue;
        }
        const paragraph = [line];
        index++;
        while (index < lines.length && lines[index].trim() && !startsBlock(index)) paragraph.push(lines[index++]);
        blocks.push(`<p>${inline(paragraph.join('\n')).html}</p>`);
    }
    return blocks.join('');
}
