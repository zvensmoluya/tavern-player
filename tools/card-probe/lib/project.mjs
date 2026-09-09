// Isolated display-regex projection only, not the full ST/Player message pipeline.
export function projectOpening(data, surface, source) {
  const index = Number(surface.label.match(/^regex\[(\d+)\]$/)?.[1]);
  const rule = data.extensions?.regex_scripts?.[index];
  if (!rule || rule.disabled || !rule.markdownOnly) throw new Error('Requires an enabled display-only regex');
  const message = source === 'first' ? data.first_mes : /^alt:\d+$/.test(source) ? data.alternate_greetings?.[Number(source.slice(4))] : undefined;
  if (typeof message !== 'string') throw new Error('Missing opening message');
  const literal = rule.findRegex.match(/^\/([\s\S]*)\/([a-z]*)$/);
  const regex = literal ? new RegExp(literal[1],literal[2]) : new RegExp(rule.findRegex);
  if (!regex.test(message)) throw new Error('Selected rule does not match the opening message');
  regex.lastIndex=0;
  return {message, rendered:message.replace(regex,rule.replaceString),source,regexIndex:index};
}
