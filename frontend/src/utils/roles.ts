import { PermissionEntity, PermissionRoot, Role } from '../models/owns/role';
import { getErrorMessage } from './api';

const permissionRoots: PermissionRoot[] = [
  'viewPermissions',
  'viewOtherPermissions',
  'createPermissions',
  'editOtherPermissions',
  'deleteOtherPermissions'
];

/**
 * Same rule as the backend (INC-02, D-6/D-11): a role can be granted, assigned, edited or deleted by a user only if
 * each of its five permission sets is within the same set of the user's role.
 */
export const isRoleWithin = (role: Role, owner: Role): boolean =>
  !!role &&
  !!owner &&
  permissionRoots.every((root) =>
    (role[root] ?? []).every((entity) => owner[root]?.includes(entity))
  );

/**
 * Same rule as the backend (INC-02, D-21): a user manages another user (role, password, disabling) only if the
 * other user is not the company owner and their current role is within the manager's.
 */
export const canManageUser = (
  target: { id: number; ownsCompany: boolean; role: Role },
  manager: { id: number; role: Role }
): boolean =>
  !!target &&
  !!manager &&
  (target.id === manager.id ||
    (!target.ownsCompany && isRoleWithin(target.role, manager.role)));

/**
 * Translated message for a failed role operation (INC-02): 409 means the role has users when deleting and a stale
 * version when editing; 403 means a role superior to the user's.
 */
export const roleErrorMessage = (
  error: any,
  t: (key: string) => string,
  fallback: string,
  action: 'create' | 'edit' | 'delete'
): string => {
  let status: number;
  try {
    status = JSON.parse(error.message)?.status;
  } catch {
    status = undefined;
  }
  if (status === 409)
    return t(
      action === 'delete' ? 'role_delete_has_users' : 'role_changed_by_other'
    );
  if (status === 403) return t('role_superior_forbidden');
  return getErrorMessage(error, fallback);
};

export const holdsPermission = (
  owner: Role,
  root: PermissionRoot,
  entity: PermissionEntity
): boolean => owner?.[root]?.includes(entity) ?? false;

const allPermissionEntities = Object.values(PermissionEntity);

const viewPermissionsExcluded = [PermissionEntity.SETTINGS];
const editOtherPermissionsExcluded = [
  PermissionEntity.PEOPLE_AND_TEAMS,
  PermissionEntity.CATEGORIES
];
const createPermissionsExcluded = [
  PermissionEntity.PEOPLE_AND_TEAMS,
  PermissionEntity.CATEGORIES
];
const deleteOtherPermissionsExcluded = [
  PermissionEntity.WORK_ORDERS,
  PermissionEntity.PREVENTIVE_MAINTENANCES,
  PermissionEntity.LOCATIONS,
  PermissionEntity.ASSETS,
  PermissionEntity.PARTS_AND_MULTIPARTS,
  PermissionEntity.PURCHASE_ORDERS,
  PermissionEntity.METERS,
  PermissionEntity.VENDORS_AND_CUSTOMERS,
  PermissionEntity.CATEGORIES,
  PermissionEntity.FILES,
  PermissionEntity.PEOPLE_AND_TEAMS
];
const viewOtherPermissionsExcluded = [];

export const defaultPermissions: Record<PermissionRoot, PermissionEntity[]> = {
  viewPermissions: allPermissionEntities.filter(
    (permission) => !viewPermissionsExcluded.includes(permission)
  ),
  editOtherPermissions: allPermissionEntities.filter(
    (permission) => !editOtherPermissionsExcluded.includes(permission)
  ),
  createPermissions: allPermissionEntities.filter(
    (permission) => !createPermissionsExcluded.includes(permission)
  ),
  viewOtherPermissions: allPermissionEntities.filter(
    (permission) => !viewOtherPermissionsExcluded.includes(permission)
  ),
  deleteOtherPermissions: allPermissionEntities.filter(
    (permission) => !deleteOtherPermissionsExcluded.includes(permission)
  )
};
