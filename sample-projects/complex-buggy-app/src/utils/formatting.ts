import moment from 'moment';

export const formatCurrency = (amount: number) => {
  // Bug: Could crash if amount is undefined
  return "$" + amount.toFixed(2);
};

export const formatDate = (dateValue: string) => {
  return moment(dateValue).format('YYYY-MM-DD');
};