// Demo sample for Aegis Debug — see ../DEMO.md.
// Demonstrates AEG-CPX-001: a single exported function with eleven decision points (six
// `if`, one `for`, one `while`, two `&&`, one `||`), well past the default complexity
// threshold of 10.
export function evaluateShippingRate(
  itemCount: number,
  weightKg: number,
  isFragile: boolean,
  isInternational: boolean,
  hasCoupon: boolean,
  destinationZone: number
): number {
  let rate = 5

  if (itemCount > 10) {
    rate += 2
  }
  if (weightKg > 20) {
    rate += 3
  }
  if (isFragile && weightKg > 5) {
    rate += 4
  }
  if (isInternational || destinationZone > 3) {
    rate += 6
  }
  if (hasCoupon && !isInternational) {
    rate -= 2
  }
  for (let zone = 0; zone < destinationZone; zone++) {
    if (zone % 2 === 0) {
      rate += 1
    }
  }
  while (rate > 50) {
    rate -= 5
  }

  return rate
}
