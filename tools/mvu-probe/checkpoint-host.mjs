// A transient view of messages for upstream MVU. Kotlin owns durable checkpoints and candidates.
// This entry is only loaded with an explicitly supplied, audited local program.
import { prepareSchemaScript } from './schema-script.mjs';

export function installCheckpointHost(mvu, program) {
    const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
    const chat = [];
    const handlers = new Map();
    let diagnostics = [];
    let events = [];
    let settings = { selected_global_lorebooks: [] };
    const log = level => (...args) => {
        // Diagnostics must not grow indefinitely if a card emits repeated output.
        if (diagnostics.length < 128) diagnostics.push({ level, text: args.map(String).join(' ').slice(0, 8192) });
    };
    const message = (text, variables = {}) => ({
        is_user: false, mes: text, swipe_id: 0, swipes: [text], variables: [copy(variables)],
    });
    const scopeMessage = options => {
        if (options.type !== 'message') throw new Error('Unsupported MVU storage scope');
        const target = chat[options.message_id ?? chat.length - 1];
        if (!target) throw new Error('Missing MVU message');
        return target;
    };
    Object.assign(globalThis, {
        console: Object.fromEntries(['log', 'info', 'warn', 'error'].map(level => [level, log(level)])),
        toastr: Object.fromEntries(['info', 'warning', 'error'].map(level => [level, log(level)])),
        SillyTavern: { chat, saveChat() {} },
        $: arg => {
            if (typeof arg === 'function') return arg();
            if (arg === '#chat > .welcomePanel') return { length: 0 };
            if (arg === '#mvu_notification_error') return { prop: key => {
                if (key !== 'checked') throw new Error('Unsupported DOM property');
                return true;
            } };
            throw new Error('Unsupported MVU DOM dependency');
        },
        eventOn: (name, fn) => {
            if (!handlers.has(name)) handlers.set(name, []);
            handlers.get(name).push(fn);
        },
        eventEmit: async (name, ...args) => {
            if (events.length < 128) events.push(name);
            for (const fn of handlers.get(name) ?? []) await fn(...args);
        },
        registerVariableSchema() {},
        substitudeMacros: text => text,
        getLastMessageId: () => chat.length - 1,
        getChatMessages: id => {
            if (!Number.isInteger(id) || id < 0) throw new Error('Unsupported MVU message selector');
            const value = chat[id];
            return value ? [{ message_id: id, role: 'assistant', message: value.mes,
                swipe_id: value.swipe_id, swipes: copy(value.swipes), swipes_data: copy(value.variables) }] : [];
        },
        setChatMessages: async changes => {
            for (const change of changes) {
                const target = chat[change.message_id];
                if (!target) throw new Error('Missing MVU message');
                if (change.message !== undefined) {
                    target.mes = change.message;
                    target.swipes[target.swipe_id] = change.message;
                }
                if (change.swipes_data) target.variables = copy(change.swipes_data);
            }
        },
        updateVariablesWith: async (updater, options) => {
            const target = scopeMessage(options);
            target.variables[target.swipe_id] = copy(updater(copy(target.variables[target.swipe_id])));
        },
        replaceVariables: async (data, options) => {
            const target = scopeMessage(options);
            target.variables[target.swipe_id] = copy(data);
        },
        getLorebookSettings: () => copy(settings),
        setLorebookSettings: value => { settings = { ...settings, ...copy(value) }; },
        getCharLorebooks: () => ({ primary: 'sample', additional: [] }),
        getCharWorldbookNames: () => ({ primary: 'sample', additional: [] }),
        getLorebookEntries: name => {
            if (name !== 'sample') throw new Error('Unknown MVU world book');
            return copy(program.entries ?? []);
        },
        registerMvuSchema: mvu.registerMvuSchema,
    });
    const source = prepareSchemaScript(program.schemaScript);
    Function(source)();
    const resetTrace = () => { diagnostics = []; events = []; };
    const result = (sourceText, processedText, data) => ({ sourceText, processedText, data: copy(data) });
    return {
        async initialize(overrides) {
            resetTrace();
            const greetings = overrides ?? program.greetings ?? ['Opening.'];
            if (!greetings.length) throw new Error('Missing greeting');
            const opening = message(greetings[0]);
            opening.swipes = [...greetings]; opening.variables = greetings.map(() => ({}));
            chat.splice(0, chat.length, opening);
            await mvu.initCheck();
            return { messages: greetings.map((text, i) => result(text, opening.swipes[i], opening.variables[i])), diagnostics, events };
        },
        async update(data, sourceText) {
            resetTrace();
            // Fresh input every time: retries/swipes cannot accidentally compound previous updates.
            chat.splice(0, chat.length, message('Previous checkpoint.', data), message(sourceText));
            await mvu.handleVariablesInMessage(1);
            // Upstream deliberately skips assistant messages shorter than five characters.
            // Player still needs a checkpoint for the new candidate: carry the previous one forward.
            const next = sourceText.length < 5 ? data : chat[1].variables[0];
            return { messages: [result(sourceText, chat[1].mes, next)], diagnostics, events };
        },
    };
}
