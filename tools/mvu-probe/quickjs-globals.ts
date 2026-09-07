import lodash from 'lodash';
import YAML from 'yaml';
import * as zod from 'zod';

// Explicit dependencies formerly supplied by the Node host. No browser or Node APIs.
Object.assign(globalThis, { _: lodash, YAML, z: zod });
globalThis.SillyTavern = { chat: [], saveChat() {} };
