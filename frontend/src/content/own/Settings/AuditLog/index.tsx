import { ReactNode, useContext, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box,
  Button,
  Grid,
  MenuItem,
  TextField,
  Typography
} from '@mui/material';
import DatePicker from '@mui/lab/DatePicker';
import { endOfDay, startOfDay } from 'date-fns';
import { formatInTimeZone } from 'date-fns-tz';
import api from '../../../../utils/api';
import useAuth from '../../../../hooks/useAuth';
import { useDispatch, useSelector } from '../../../../store';
import { getUsersMini } from '../../../../slices/user';
import { CustomSnackBarContext } from '../../../../contexts/CustomSnackBarContext';
import CustomDatagrid2, {
  CustomDatagridColumn2
} from '../../components/CustomDatagrid2';
import {
  AuditAction,
  auditActions,
  AuditLogFilter,
  AuditLogRecord,
  AuditLogSearchResult
} from '../../../../models/owns/auditLog';

const ALL = 'ALL';
const PAGE_SIZES = [10, 20, 50, 100];

interface FilterForm {
  from: Date | null;
  to: Date | null;
  action: AuditAction | typeof ALL;
  userId: number | typeof ALL;
}

const toForm = (filter: AuditLogFilter): FilterForm => ({
  from: new Date(filter.from),
  to: new Date(filter.to),
  action: filter.action ?? ALL,
  userId: filter.userId ?? ALL
});

/**
 * Read-only audit log of the organization (P-10, PPB-24, PPB-36): opens with the filter preloaded by the API and
 * lets the user edit the period, the operation and the user.
 */
function AuditLog() {
  const { t }: { t: any } = useTranslation();
  const dispatch = useDispatch();
  const { companySettings } = useAuth();
  const { showSnackBar } = useContext(CustomSnackBarContext);
  const { usersMini } = useSelector((state) => state.users);
  const timeZone =
    companySettings?.generalPreferences?.timeZone ??
    Intl.DateTimeFormat().resolvedOptions().timeZone;
  const [form, setForm] = useState<FilterForm | null>(null);
  const [result, setResult] = useState<AuditLogSearchResult | null>(null);
  const [loading, setLoading] = useState(false);

  /**
   * Loads one page; without a filter, the API applies and returns the preloaded one.
   */
  const load = (filter: AuditLogFilter | null, page: number, size: number) => {
    const params = new URLSearchParams();
    if (filter) {
      params.set('from', filter.from);
      params.set('to', filter.to);
      if (filter.action) params.set('action', filter.action);
      if (filter.userId) params.set('userId', String(filter.userId));
      params.set('page', String(page));
      params.set('size', String(size));
    }
    setLoading(true);
    api
      .get<AuditLogSearchResult>(`audit-logs?${params.toString()}`)
      .then((response) => {
        setResult(response);
        if (!filter) setForm(toForm(response.filter));
      })
      .catch((error) => {
        let code: string;
        try {
          code = JSON.parse(error.message)?.code;
        } catch {
          code = undefined;
        }
        showSnackBar(
          t(
            code === 'AUDIT_FILTER_INVALID'
              ? 'audit_filter_invalid'
              : 'audit_load_failure'
          ),
          'error'
        );
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    dispatch(getUsersMini());
    load(null, 0, 0);
  }, []);

  const onApply = () => {
    if (!form?.from || !form?.to) {
      showSnackBar(t('audit_filter_invalid'), 'error');
      return;
    }
    load(
      {
        from: startOfDay(form.from).toISOString(),
        to: endOfDay(form.to).toISOString(),
        action: form.action === ALL ? null : form.action,
        userId: form.userId === ALL ? null : form.userId
      },
      0,
      result?.size ?? PAGE_SIZES[1]
    );
  };

  const formatDateTime = (value: string) =>
    formatInTimeZone(new Date(value), timeZone, 'dd/MM/yyyy HH:mm');

  // The grid cuts every cell to one line; long values wrap inside their cell instead
  const wrapped = (content: ReactNode) => (
    <Box sx={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{content}</Box>
  );

  const columns: CustomDatagridColumn2<AuditLogRecord>[] = [
    {
      header: t('audit_occurred_at'),
      accessorKey: 'occurredAt',
      size: 150,
      cell: (info) => formatDateTime(info.getValue() as string)
    },
    {
      header: t('audit_user'),
      accessorKey: 'actorName',
      size: 210,
      cell: (info) => {
        const record = info.row.original;
        return wrapped(
          record.actorName ? (
            <>
              <Typography variant="body2">{record.actorName}</Typography>
              <Typography variant="body2" color="text.secondary">
                {record.actorEmail}
              </Typography>
            </>
          ) : (
            t('audit_system_user')
          )
        );
      }
    },
    {
      header: t('audit_action'),
      accessorKey: 'action',
      size: 120,
      cell: (info) => wrapped(t(`audit_action_${info.getValue()}`))
    },
    {
      header: t('audit_entity_type'),
      accessorKey: 'entityType',
      size: 150,
      cell: (info) => wrapped(t(`entity_type_${info.getValue()}`))
    },
    {
      header: t('audit_entity'),
      accessorKey: 'entityName',
      size: 190,
      cell: (info) => {
        const record = info.row.original;
        return wrapped(
          [record.entityFolio, record.entityName].filter(Boolean).join(' · ') ||
            `#${record.entityId}`
        );
      }
    },
    {
      header: t('audit_source'),
      accessorKey: 'source',
      size: 100,
      cell: (info) => wrapped(t(`audit_source_${info.getValue()}`))
    },
    {
      header: t('audit_summary'),
      accessorKey: 'cascadeRows',
      size: 260,
      cell: (info) => {
        const record = info.row.original;
        const rows = Object.entries(record.cascadeRows ?? {}).map(
          ([table, count]) => `${table}: ${count}`
        );
        if (record.storageObjects)
          rows.push(`${t('audit_storage_objects')}: ${record.storageObjects}`);
        return rows.length
          ? wrapped(rows.map((row) => <div key={row}>{row}</div>))
          : '—';
      }
    }
  ];

  return (
    <Grid item xs={12}>
      <Box p={4}>
        {form && (
          <Box
            sx={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: 2,
              alignItems: 'center',
              mb: 3
            }}
          >
            <DatePicker
              label={t('audit_from')}
              inputFormat="dd/MM/yyyy"
              value={form.from}
              onChange={(value: Date | null) =>
                setForm({ ...form, from: value })
              }
              renderInput={(params) => (
                <TextField {...params} sx={{ minWidth: 170 }} />
              )}
            />
            <DatePicker
              label={t('audit_to')}
              inputFormat="dd/MM/yyyy"
              value={form.to}
              onChange={(value: Date | null) => setForm({ ...form, to: value })}
              renderInput={(params) => (
                <TextField {...params} sx={{ minWidth: 170 }} />
              )}
            />
            <TextField
              select
              label={t('audit_action')}
              value={form.action}
              onChange={(event) =>
                setForm({
                  ...form,
                  action: event.target.value as FilterForm['action']
                })
              }
              sx={{ minWidth: 200 }}
            >
              <MenuItem value={ALL}>{t('audit_all_actions')}</MenuItem>
              {auditActions.map((action) => (
                <MenuItem key={action} value={action}>
                  {t(`audit_action_${action}`)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label={t('audit_user')}
              value={form.userId}
              onChange={(event) =>
                setForm({
                  ...form,
                  userId: event.target.value as FilterForm['userId']
                })
              }
              sx={{ minWidth: 220 }}
            >
              <MenuItem value={ALL}>{t('audit_all_users')}</MenuItem>
              {usersMini.map((user) => (
                <MenuItem key={user.id} value={user.id}>
                  {`${user.firstName} ${user.lastName}`}
                </MenuItem>
              ))}
            </TextField>
            <Button variant="contained" onClick={onApply} disabled={loading}>
              {t('audit_apply')}
            </Button>
            <Button onClick={() => load(null, 0, 0)} disabled={loading}>
              {t('audit_reset')}
            </Button>
          </Box>
        )}
        <CustomDatagrid2
          columns={columns}
          data={result?.content ?? []}
          loading={loading}
          notClickable
          enableColumnReordering={false}
          pagination={{
            pageIndex: result?.page ?? 0,
            pageSize: result?.size ?? PAGE_SIZES[1]
          }}
          pageSizeOptions={PAGE_SIZES}
          onPaginationChange={(pagination) =>
            result &&
            load(result.filter, pagination.pageIndex, pagination.pageSize)
          }
          totalRows={result?.totalElements ?? 0}
          noRowsMessage={t('audit_no_records')}
        />
      </Box>
    </Grid>
  );
}

export default AuditLog;
