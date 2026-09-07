// Keep diagnostic keys and values observable without pulling in Vue's UI.
export const tr = (key: string, values: unknown = {}) => `${key}: ${JSON.stringify(values)}`;
