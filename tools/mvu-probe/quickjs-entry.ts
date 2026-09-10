import './quickjs-globals';
import * as mvu from './entry';
import { installCheckpointHost } from './checkpoint-host.mjs';

export const createSession = (program, macroValues) => installCheckpointHost(mvu, program, macroValues);
