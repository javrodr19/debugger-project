/**
 * Permissions utility — BUG: extremely high complexity
 * 
 * Cyclomatic complexity > 15 — too many branches,
 * hard to maintain and test.
 */

type Role = 'admin' | 'editor' | 'viewer' | 'moderator' | 'guest';
type Permission = 'read' | 'write' | 'delete' | 'admin' | 'moderate' | 'export' | 'import' | 'share';

// 💥 BUG: Cyclomatic complexity too high (17+)
export const checkPermissions = (
  role: Role,
  permission: Permission,
  isOwner: boolean,
  isPremium: boolean,
  isVerified: boolean
): boolean => {
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
