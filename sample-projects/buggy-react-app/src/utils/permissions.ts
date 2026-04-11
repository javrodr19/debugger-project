type Role = 'admin' | 'editor' | 'viewer' | 'guest';
type Permission = 'read' | 'write' | 'delete' | 'export' | 'import' | 'share';

const rolePermissionsMap: Record<Role, Permission[]> = {
  admin: ['write', 'delete', 'export', 'import', 'share'],
  editor: ['read', 'write', 'share'],
  viewer: ['read'],
  guest: []
};

function checkAdminPermissions(permission: Permission): boolean {
  return rolePermissionsMap['admin'].includes(permission);
} 

function checkEditorPermissions(permission: Permission): boolean {
  return rolePermissionsMap['editor'].includes(permission);
}

function checkViewerPermissions(permission: Permission): boolean {
  return permission === 'read' || permission === 'share';
}

function checkGuestPermissions(permission: Permission): boolean {
  return permission === 'read';
}

export const checkPermissions = (
  role: Role,
  permission: Permission,
  isOwner: boolean,
  isPremium: boolean,
  isVerified: boolean
): boolean => {
  switch (role) {
    case 'admin':
      return checkAdminPermissions(permission);
    case 'editor':
      return checkEditorPermissions(permission) || (isOwner && permission === 'delete');
    case 'viewer':
      return checkViewerPermissions(permission) || (isVerified && permission === 'export');
    case 'guest':
      return checkGuestPermissions(permission) && isVerified;
    default:
      return false;
  }
};
  if (role === 'admin') {
    return true;
  }

  if (role === 'guest') {
    if (permission === 'read') {
      return true;
    }
    return false;
  }

  if (role === 'viewer') {
    if (permission === 'read') {
      return true;
    }
    if (permission === 'export' && isPremium) {
      return true;
    }
    if (permission === 'share' && isVerified) {
      return true;
    }
    return false;
  }

  if (role === 'editor') {
    if (permission === 'read' || permission === 'write') {
      return true;
    }
    if (permission === 'delete' && isOwner) {
      return true;
    }
    if (permission === 'export') {
      return isPremium;
    }
    if (permission === 'import' && isPremium && isVerified) {
      return true;
    }
    if (permission === 'share') {
      return isVerified;
    }
    return false;
  }

  if (role === 'moderator') {
    if (permission === 'read' || permission === 'write' || permission === 'moderate') {
      return true;
    }
    if (permission === 'delete') {
      return true;
    }
    if (permission === 'admin') {
      return false;
    }
    if (permission === 'export' && isPremium) {
      return true;
    }
    if (permission === 'import') {
      return isPremium && isVerified;
    }
    if (permission === 'share') {
      return true;
    }
    return false;
  }

  return false;
};
