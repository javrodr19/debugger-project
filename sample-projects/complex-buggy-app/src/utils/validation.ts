import { isObject } from 'lodash';

export const isValidUser = (user: any) => {
  return isObject(user) && user.id != null;
};