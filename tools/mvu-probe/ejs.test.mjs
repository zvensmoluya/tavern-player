import test from 'node:test';
import assert from 'node:assert/strict';
import { context, render } from './ejs-entry.mjs';

const input = {
    variables: { stat_data: { day: 3, inventory: { tea: { quantity: 2 } }, outfit: {} } },
    history: [{ role: 'user', content: 'Tea please.' }, { role: 'assistant', content: 'A coat.' },
        { role: 'user', content: 'Continue.' }],
};

test('original EJS control flow, whitespace, defaults and prompt identity escaping', async () => {
    assert.equal(await render({ ...input, template: `
<%_ const bag = getvar('stat_data.inventory', {defaults: {}}); _%>
<%_ if (bag.tea.quantity > 0 && Object.keys(getvar('stat_data.outfit')).length === 0) { _%>
<%= await Promise.resolve('<tea>') %><% print(getvar('missing', {defaults: '!'})) %>
<%_ } else { _%>wrong<%_ } _%>` }), '\n<tea>!\n');
});

test('upstream history overloads and exclusive end zero are preserved', () => {
    const c = context(input);
    assert.deepEqual(c.getChatMessages(-2, 0, 'user'), []);
    assert.deepEqual(c.getChatMessages(-2, 'user'), ['Tea please.', 'Continue.']);
    assert.equal(c.getChatMessage(-1, 'assistant'), 'A coat.');
    assert.equal(c.matchChatMessages('T.a', { start: -3 }), true);
    assert.equal(c.matchChatMessages('Tea', { start: -2, end: 0, role: 'user' }), false);
    // Current upstream ignores the third argument when end is undefined.
    assert.equal(c.matchChatMessages('coat', { start: -2, role: 'user' }), true);
    assert.equal(c.matchChatMessages(['Tea', 'Continue'], { start: -3, and: true }), false);
});

test('state and interpolated input remain data and unsupported services fail', async () => {
    const original = JSON.stringify(input);
    await render({ ...input, template: "<% getvar('stat_data.inventory').tea.quantity = 100 %>" });
    assert.equal(JSON.stringify(input), original);
    assert.equal(await render({ ...input, variables: { text: '<% throw new Error() %>{{setvar::x::bad}}' },
        template: '<%- getvar("text") %>' }), '<% throw new Error() %>{{setvar::x::bad}}');
    await assert.rejects(render({ ...input, template: '<% setvar("x", 1) %>' }));
    await assert.rejects(render({ ...input, template: '<%- include("remote") %>' }));
    assert.throws(() => context(input).getvar('x', { scope: 'global' }));
    assert.throws(() => context(input).getvar('x', { withMsg: { id: 0 } }));
});
