import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import lodash from 'lodash';
import YAML from 'yaml';
import * as zod from 'zod';

const bundle = await readFile(fileURLToPath(new URL('./build/mvu.js', import.meta.url)), 'utf8');
const clone = value => structuredClone(value);

// Research host for audited local fixtures, NOT a security sandbox or an Android runtime.
// Keep MVU's operation/parser/schema code intact; bridge message and event APIs only.
export class ProbeHost {
    constructor({ schemaScript, entries = [], greetings = ['Opening.'], savedChat } = {}) {
        this.chat = savedChat ? clone(savedChat) : [{
            is_user: false, mes: greetings[0], swipe_id: 0,
            swipes: [...greetings], variables: greetings.map(() => ({})),
        }];
        this.entries = clone(entries);
        this.events = [];
        this.calls = new Set();
        this.diagnostics = [];
        this.handlers = new Map();
        this.lorebookSettings = { selected_global_lorebooks: [] };
        const api = (name, fn) => (...args) => { this.calls.add(name); return fn(...args); };
        const log = level => (...args) => this.diagnostics.push({ level, text: args.map(String).join(' ') });
        const globals = {
            _: lodash, YAML, z: zod,
            console: Object.fromEntries(['log', 'info', 'warn', 'error'].map(level => [level, log(level)])),
            toastr: Object.fromEntries(['info', 'warning', 'error'].map(level => [level, log(level)])),
            // Only two upstream DOM reads: welcome panel presence and error-notification setting.
            $: api('$', arg => {
                if (typeof arg === 'function') return arg();
                if (arg === '#chat > .welcomePanel') return { length: 0 };
                if (arg === '#mvu_notification_error') return { prop: key => {
                    if (key !== 'checked') throw new Error(`Unexpected property: ${key}`);
                    return true;
                } };
                throw new Error(`Unexpected DOM dependency: ${arg}`);
            }),
            SillyTavern: { chat: this.chat, saveChat: api('saveChat', () => {}) },
            eventOn: api('eventOn', (event, fn) => {
                const list = this.handlers.get(event) ?? [];
                list.push(fn); this.handlers.set(event, list);
            }),
            eventEmit: api('eventEmit', async (event, ...args) => {
                this.events.push(event);
                for (const handler of this.handlers.get(event) ?? []) await handler(...args);
            }),
            // This experiment only needs identity macros in its synthetic update messages.
            substitudeMacros: api('substitudeMacros', text => text),
            registerVariableSchema: api('registerVariableSchema', (schema, options) => {
                this.registeredSchema = { schema, options };
            }),
            getLastMessageId: api('getLastMessageId', () => this.chat.length - 1),
            getChatMessages: api('getChatMessages', (id, options = {}) => {
                if (!Number.isInteger(id)) throw new Error(`Unimplemented message selector: ${id}`);
                const index = id < 0 ? this.chat.length + id : id;
                const message = this.chat[index];
                if (!message) return [];
                const role = message.is_user ? 'user' : 'assistant';
                if (options.role && options.role !== role) return [];
                return [{
                    message_id: index, role, message: message.mes, swipe_id: message.swipe_id,
                    swipes: clone(message.swipes), swipes_data: clone(message.variables),
                }];
            }),
            setChatMessages: api('setChatMessages', async updates => {
                for (const update of updates) {
                    const message = this.chat[update.message_id];
                    if (!message) throw new Error('Missing target message');
                    if (update.message !== undefined) {
                        message.mes = update.message;
                        message.swipes[message.swipe_id] = update.message;
                    }
                    if (update.swipes_data) message.variables = clone(update.swipes_data);
                }
            }),
            updateVariablesWith: api('updateVariablesWith', async (updater, options) => {
                this.checkMessageScope(options);
                const message = this.chat[options.message_id ?? this.chat.length - 1];
                const next = updater(clone(message.variables[message.swipe_id] ?? {}));
                message.variables[message.swipe_id] = clone(next);
            }),
            replaceVariables: api('replaceVariables', async (data, options) => {
                this.checkMessageScope(options);
                const message = this.chat[options.message_id ?? this.chat.length - 1];
                message.variables[message.swipe_id] = clone(data);
            }),
            getLorebookSettings: api('getLorebookSettings', () => clone(this.lorebookSettings)),
            setLorebookSettings: api('setLorebookSettings', settings => { this.lorebookSettings = clone(settings); }),
            getCharLorebooks: api('getCharLorebooks', () => ({ primary: 'sample', additional: [] })),
            getCharWorldbookNames: api('getCharWorldbookNames', () => ({ primary: 'sample', additional: [] })),
            getLorebookEntries: api('getLorebookEntries', name => {
                if (name !== 'sample') throw new Error('Unknown world book');
                return clone(this.entries);
            }),
        };
        this.context = vm.createContext(globals, { name: 'audited-mvu-probe' });
        vm.runInContext(bundle, this.context, { timeout: 10_000 });
        this.mvu = this.context.MvuProbe;
        this.context.registerMvuSchema = this.mvu.registerMvuSchema;
        if (schemaScript) {
            // The fixture's sole import is wired to the pinned dependency already in the bundle.
            const source = schemaScript.replace(
                /import\s*\{\s*registerMvuSchema\s*\}\s*from\s*['"]https:\/\/testingcf\.jsdelivr\.net\/gh\/StageDog\/tavern_resource\/dist\/util\/mvu_zod\.js['"];?/, '',
            ).replace(/export\s+const\s+Schema\b/, 'const Schema');
            if (/\bimport\s/.test(source)) throw new Error('Unmapped card import');
            vm.runInContext(source, this.context, { timeout: 5_000 });
        }
    }

    checkMessageScope(options) {
        if (options.type !== 'message') throw new Error('Chat/global storage is outside this probe');
    }

    async initialize() { await this.mvu.initCheck(); }

    state(id = this.chat.length - 1) {
        const message = this.chat[id];
        return clone(message.variables[message.swipe_id]);
    }

    async reply(operations) {
        const message = `Reply.\n<UpdateVariable>\n<JSONPatch>\n${JSON.stringify(operations)}\n</JSONPatch>\n</UpdateVariable>`;
        this.chat.push({ is_user: false, mes: message, swipe_id: 0, swipes: [message], variables: [{}] });
        await this.mvu.handleVariablesInMessage(this.chat.length - 1);
        return this.state();
    }

    async regenerate(operations) {
        const message = this.chat.at(-1);
        const text = `<UpdateVariable><JSONPatch>${JSON.stringify(operations)}</JSONPatch></UpdateVariable>`;
        message.swipe_id = message.swipes.length;
        message.swipes.push(text); message.variables.push({}); message.mes = text;
        await this.mvu.handleVariablesInMessage(this.chat.length - 1);
        return this.state();
    }

    select(id, swipe) {
        const message = this.chat[id];
        if (!message.swipes[swipe]) throw new Error('Unknown candidate');
        message.swipe_id = swipe; message.mes = message.swipes[swipe];
    }

    save() { return JSON.stringify(this.chat); }
}
