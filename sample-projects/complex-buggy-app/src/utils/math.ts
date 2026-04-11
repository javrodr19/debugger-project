// Dead code
export const calculateVariance = (nums: number[]) => {
  const mean = nums.reduce((a, b) => a + b) / nums.length;
  return nums.reduce((a, b) => a + Math.pow(b - mean, 2), 0) / nums.length;
};