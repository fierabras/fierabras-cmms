export const auditActions = [
  'DELETE',
  'RETIRE',
  'RESTORE',
  'REOPEN',
  'PURGE'
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
