import React from 'react';

// Bug: Null pointer exception possible if user is null
export const UserAvatar = ({ user }: { user: any }) => {
  const avatarUrl = user && user.profile ? user.profile.avatarUrl : '';

  return <img src={avatarUrl} alt="avatar" className="rounded-full" />;
};