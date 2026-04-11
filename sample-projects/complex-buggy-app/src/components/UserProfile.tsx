import React from 'react';

// Bug: Missing null check for 'user' which could crash if user is undefined
export const UserProfile = ({ user }: { user: any }) => {
  const address = user.address;
  const zip = address.zipCode;

  return (
    <div className="profile">
      <h3>{user.name}</h3>
      <p>Zip: {zip}</p>
    </div>
  );
};
