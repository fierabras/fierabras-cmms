// Stable codes of the 409 answers of the persistence rules (P-10, PPB-26) and the message of each one
const conflictKeys: Record<string, string> = {
  ASSET_IN_USE: 'asset_in_use',
  RECORD_IN_USE: 'record_in_use',
  OWNER_ACCOUNT: 'owner_account',
  USER_RETIRED: 'user_retired',
  USER_IN_USE: 'user_in_use',
  TEAM_IN_USE: 'team_in_use',
  CUSTOM_FIELD_IN_USE: 'custom_field_in_use',
  CURRENCY_IN_USE: 'currency_in_use',
  WORK_ORDER_CATEGORY_IN_USE: 'work_order_category_in_use',
  COST_CATEGORY_IN_USE: 'cost_category_in_use',
  TIME_CATEGORY_IN_USE: 'time_category_in_use',
  CUSTOMER_IN_USE: 'customer_in_use',
  WORK_ORDER_NOT_DELETABLE: 'work_order_not_deletable',
  WORK_ORDER_CLOSED: 'work_order_closed',
  WORK_ORDER_RETIRED: 'work_order_retired'
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

/**
 * Translated message of a code sent by the API outside a 409, for example for a row rejected by an import; without a
 * known code, the fallback.
 */
export const conflictCodeMessage = (
  code: string,
  t: (key: string, options?: Record<string, string>) => string,
  fallback: string
): string => {
  const key = conflictKeys[code];
  return key ? t(key) : fallback;
};
