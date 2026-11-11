// Link real native ES graphs without evaluating DOM, storage or network code.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {fileURLToPath, pathToFileURL} = require('node:url');
if (!vm.SourceTextModule) {
    const result = require('node:child_process').spawnSync(process.execPath,
        ['--experimental-vm-modules', __filename, ...process.argv.slice(2)], {stdio: 'inherit', windowsHide: true});
    process.exit(result.status ?? 1);
}
const root = path.resolve(process.argv[2] || path.join(__dirname, '../..'));
const entries = ['frontend/app.js', 'frontend/product-detail.js', 'frontend/order-detail.js',
    'scripts/control-center/web/app.js', 'frontend/chat-widget.js'];
const modules = new Map(), versions = new Map();
const context = vm.createContext({});
function load(url) {
    const file = fileURLToPath(url);
    const relative = path.relative(root, file);
    if (relative.startsWith('..') || path.isAbsolute(relative) || path.extname(file) !== '.js') throw Error('Invalid module path: ' + relative);
    if (!fs.existsSync(file)) throw Error('Missing module: ' + relative);
    if (url.search) {
        const previous = versions.get(file);
        if (previous && previous !== url.search) throw Error('Conflicting module versions: ' + relative);
        versions.set(file, url.search);
    }
    if (modules.has(file)) return modules.get(file);
    const module = new vm.SourceTextModule(fs.readFileSync(file, 'utf8'), {context, identifier: pathToFileURL(file).href});
    modules.set(file, module);
    return module;
}
async function main() {
    for (const entry of entries) {
        const module = load(pathToFileURL(path.join(root, entry)));
        if (module.status === 'unlinked') await module.link((specifier, parent) => {
            if (!specifier.startsWith('./') && !specifier.startsWith('../')) throw Error('External module dependency: ' + specifier);
            return load(new URL(specifier, parent.identifier));
        });
    }
    for (const directory of ['frontend','scripts/control-center/web']) {
        for (const name of fs.readdirSync(path.join(root,directory)).filter(name => name.endsWith('.js'))) {
            const file = path.join(root,directory,name), source = fs.readFileSync(file,'utf8');
            new vm.SourceTextModule(source, {context}); // Include legacy adapters in syntax validation.
            if (directory === 'frontend' && name !== 'customer-session.js'
                    && (source.includes('minitech-refresh-session') || /['"`]\/customers\/refresh['"`]/.test(source)))
                throw Error('Duplicated customer refresh policy: ' + name);
        }
    }
    // Entry points bind the application; feature implementations belong in modules.
    for (const entry of [entries[0],entries[3]]) {
        const source = fs.readFileSync(path.join(root,entry),'utf8');
        if (/function (submitCheckout|submitAuth|renderProducts|loadOrders|renderInvoicesTable|startTerminal)\(/.test(source))
            throw Error('Feature copied back into composition root: ' + entry);
    }
    console.log(JSON.stringify({status:'PASS', entries:entries.length, linkedModules:modules.size, evaluatedModules:0,
        sharedCustomerRefresh:true}));
}
main().catch(error => {console.error(error.message); process.exitCode = 1;});
