import ejs from 'ejs/ejs.js';
import get from 'lodash/get.js';

// Read-only Prompt Template subset, audited against d6f520d149aba146305b0b781ddd691d449c28d2.
// In particular end=0 is Array.slice's exclusive zero, not an alias for the latest message.
export function context(input) {
    const variables = JSON.parse(JSON.stringify(input.variables));
    const freeze = value => {
        if (value && typeof value === 'object') {
            Object.values(value).forEach(freeze);
            Object.freeze(value);
        }
        return value;
    };
    freeze(variables);
    const history = input.history;
    const byRole = role => history.filter(m => !role || m.role === role);
    const getChatMessages = (start = history.length, end, role) => {
        if (!Number.isInteger(start) || (typeof end === 'number' && !Number.isInteger(end)))
            throw new Error('Invalid message range');
        if (start === 0) return [];
        if (end == null) return (start > 0 ? history.slice(0, start) : history.slice(start)).map(m => m.content);
        if (typeof end === 'string') {
            const messages = byRole(end);
            return (start > 0 ? messages.slice(0, start) : messages.slice(start)).map(m => m.content);
        }
        return byRole(role).slice(start, end).map(m => m.content);
    };
    return {
        variables,
        getvar(key, options = {}) {
            if (typeof options === 'string') options = { scope: options };
            if (!options || typeof options !== 'object' || Object.keys(options).some(k => !['defaults', 'clone', 'scope'].includes(k)) ||
                (options.scope && !['cache', 'message'].includes(options.scope)))
                throw new Error('Unsupported getvar options');
            const value = key == null ? variables : get(variables, key, options.defaults);
            return options.clone && value !== undefined ? JSON.parse(JSON.stringify(value)) : value;
        },
        getChatMessages,
        getChatMessage(index, role) {
            const messages = byRole(role);
            return messages[index > -1 ? index : messages.length + index]?.content ?? '';
        },
        matchChatMessages(pattern, options = {}) {
            if (Object.keys(options).some(k => !['start', 'end', 'role', 'and'].includes(k)))
                throw new Error('Unsupported matchChatMessages options');
            const patterns = Array.isArray(pattern) ? pattern : [pattern];
            return getChatMessages(options.start ?? -2, options.end, options.role).some(text =>
                options.and ? patterns.every(p => text.match(p)) : patterns.some(p => text.match(p)));
        },
    };
}

export async function render(input) {
    const fn = ejs.compile(input.template, {
        client: true, async: true, outputFunctionName: 'print', compileDebug: false,
        // Prompt Template uses identity escaping for generation, unlike stock EJS HTML output.
        escape: function (value) { return value == null ? '' : String(value); },
    });
    const result = await fn(context(input), undefined, () => { throw new Error('EJS include is unsupported'); });
    if (result.length > 262144) throw new Error('EJS output exceeds limit');
    return result;
}
