export const auditActions = [
  'DELETE',
  'RETIRE',
  'RESTORE',
  'REOPEN',
  'PURGE',
  'SKIP_ARCHIVED_ASSET',
  'GENERATION_FAILED',
  'SCHEDULING_FAILED',
  'MAIL_FAILED',
  'NOTIFICATION_FAILED',
  'WEBHOOK_FAILED',
  'MAIL_DISCARDED',
  'NOTIFICATION_DISCARDED',
  'WEBHOOK_DISCARDED'
] as const;
export type AuditAction = typeof auditActions[number];

export interface AuditLogFilter {
  from: string;
  to: string;
  action: AuditAction | null;
  userId: number | null;
}

export interface AuditLogRecord {
  id: number;
  occurredAt: string;
  actorId: number | null;
  actorName: string | null;
  actorEmail: string | null;
  action: AuditAction;
  entityType: string;
  entityId: number;
  entityFolio: string | null;
  entityName: string | null;
  source: string;
  cascadeRows: Record<string, number>;
  storageObjects: number;
}

export interface AuditLogSearchResult {
  filter: AuditLogFilter;
  content: AuditLogRecord[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
