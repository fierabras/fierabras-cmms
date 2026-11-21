// Stable codes of the 409 answers of the persistence rules (P-10, PPB-26) and the message of each one
const conflictKeys: Record<string, string> = {
  ASSET_IN_USE: 'asset_in_use',
  RECORD_IN_USE: 'record_in_use'
};

/**
 * Translated message for an operation rejected by a persistence rule: the code sent by the API tells which rule
 * rejected it and, for the <ENTITY>_IN_USE codes, the types that block it. Without a known code, the fallback.
 */
export const conflictMessage = (
  error: any,
  t: (key: string, options?: Record<string, string>) => string,
  fallback: string
): string => {
  let body: { code?: string; blockingTypes?: string[] };
  try {
    body = JSON.parse(error.message);
  } catch {
    body = undefined;
  }
  const key = conflictKeys[body?.code];
  if (!key) return fallback;
  const types = (body.blockingTypes ?? []).map((type) =>
    t(`entity_type_${type}`)
  );
  return types.length
    ? `${t(key)} ${t('in_use_blocked_by', { types: types.join(', ') })}`
    : t(key);
};
